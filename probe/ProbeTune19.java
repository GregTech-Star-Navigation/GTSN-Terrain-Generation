import com.gtsn.terrain.noise.*;

/** T4 调参扫描：固定地理（plate=600/-300, mountain=-2000/-1600, base=1500/0），
 *  扫 baseElevationGain × massifGain × ridgeGain × chainGain，getHeight(侵蚀后) 测全 13 契约。
 *  目标：找 13/13 全绿的增益组合。 */
public class ProbeTune19 {
    static long SEED = 20260803L;
    static final int GRID = 256;
    static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) {
        // 固定地理
        TerrainConfig base = new TerrainConfig(SEED);
        base.plateOffsetX = 600f; base.plateOffsetZ = -300f;
        base.mountainOffsetX = -2000f; base.mountainOffsetZ = -1600f;
        base.baseOffsetX = 1500f; base.baseOffsetZ = 0f;
        base.mountainMaskFrequency = 0.004f;

        float[] baseGains = {120f, 160f, 200f};
        float[] massifs = {150f, 200f, 250f, 300f};
        float[] ridges = {15f, 25f, 35f};
        int best = 0; String bestLine = "";
        int total = baseGains.length * massifs.length * ridges.length; int done = 0;
        for (float bg : baseGains) for (float mg : massifs) for (float rg : ridges) {
            TerrainConfig cfg = new TerrainConfig(SEED);
            cfg.plateOffsetX = 600f; cfg.plateOffsetZ = -300f;
            cfg.mountainOffsetX = -2000f; cfg.mountainOffsetZ = -1600f;
            cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
            cfg.mountainMaskFrequency = 0.004f;
            cfg.baseElevationGain = bg;
            cfg.massifGain = mg;
            cfg.ridgeGain = rg;
            cfg.chainGain = 40f;
            HeightMapBuilder b = new HeightMapBuilder(cfg);
            int pass = metrics(b);
            done++;
            String line = String.format("bg=%3.0f massif=%3.0f ridge=%3.0f -> %d/13", bg, mg, rg, pass);
            if (pass > best) { best = pass; bestLine = line; }
            if (pass >= 8) System.out.println(line);
        }
        System.out.println("== BEST: " + bestLine);
    }

    /** 返回 13 契约通过数（S1/S6/S7/S8 假定恒过） */
    static int metrics(HeightMapBuilder b) {
        int pass = 0;
        // S2 海陆比 origin
        int[] o = sample(b, 0, 0, GRID);
        int land = 0; for (int h : o) if (h > TerrainConfig.SEA_LEVEL) land++;
        double ratio = 100.0 * land / (GRID * GRID);
        if (ratio >= 25 && ratio <= 45) pass++;
        // S3 范围
        int maxH = Integer.MIN_VALUE, minH = Integer.MAX_VALUE;
        for (int h : o) { maxH = Math.max(maxH, h); minH = Math.min(minH, h); }
        if (maxH <= TerrainConfig.MAX_HEIGHT && minH >= -60) pass++;
        // S4 连续性
        int maxDelta = 0;
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            int h = o[z * GRID + x];
            if (x + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[z * GRID + x + 1]));
            if (z + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[(z + 1) * GRID + x]));
        }
        if (maxDelta <= 8) pass++;
        // S5 多样性 w1
        int[] w1 = sample(b, -1024, 0, GRID);
        boolean[] seen = new boolean[TerrainConfig.MAX_HEIGHT + 65];
        int distinct = 0;
        for (int h : w1) { int idx = h + 64; if (idx >= 0 && idx < seen.length && !seen[idx]) { seen[idx] = true; distinct++; } }
        if (distinct > 450) pass++;
        // S9 走向 w1
        java.util.List<int[]> pts = new java.util.ArrayList<>();
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) if (w1[z * GRID + x] > 400) pts.add(new int[]{x, z});
        if (pts.size() >= 200) {
            int n = pts.size(); double mx = 0, mz = 0;
            for (int[] p : pts) { mx += p[0]; mz += p[1]; }
            mx /= n; mz /= n;
            double cxx = 0, czz = 0, cxz = 0;
            for (int[] p : pts) { double dx = p[0]-mx, dz = p[1]-mz; cxx += dx*dx; czz += dz*dz; cxz += dx*dz; }
            cxx /= n; czz /= n; cxz /= n;
            double tr = cxx + czz, det = cxx * czz - cxz * cxz;
            double disc = Math.sqrt(Math.max(0, tr*tr/4 - det));
            double l1 = Math.max(1e-9, tr/2 + disc), l2 = Math.max(1e-9, tr/2 - disc);
            if (Math.sqrt(l1/l2) > 1.5) pass++;
        }
        // S10 金字塔
        boolean s10 = true;
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int lnd = 0, lw = 0, alp = 0;
            for (int h : hh) { if (h > TerrainConfig.SEA_LEVEL) { lnd++; if (h <= 140) lw++; if (h > 400) alp++; } }
            if (lnd >= 500) {
                double lr = 100.0 * lw / lnd, ar = 100.0 * alp / lnd;
                if (!(lr >= 35 && lr <= 60 && ar < 10)) s10 = false;
            }
        }
        if (s10) pass++;
        // S11 坡度（放宽契约 avg<22° steep30<15%）
        boolean s11 = true;
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int base = 8; double sum = 0; int cnt = 0, st30 = 0;
            for (int z = base; z < GRID - base; z++) for (int x = base; x < GRID - base; x++) {
                int i = z * GRID + x;
                int dx = Math.abs(hh[i + base] - hh[i - base]);
                int dz = Math.abs(hh[(z + base) * GRID + x] - hh[(z - base) * GRID + x]);
                double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / (2.0 * base)));
                sum += deg; cnt++; if (deg > 30.0) st30++;
            }
            double avg = sum / cnt, stR = 100.0 * st30 / cnt;
            if (!(avg < 22.0 && stR < 15.0)) s11 = false;
        }
        if (s11) pass++;
        // S12 海岸线
        int pass12 = 0;
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int coast = 0;
            for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
                int i = z * GRID + x;
                if (hh[i] <= TerrainConfig.SEA_LEVEL) continue;
                boolean hasSea = (x > 0 && hh[i - 1] <= TerrainConfig.SEA_LEVEL) || (x + 1 < GRID && hh[i + 1] <= TerrainConfig.SEA_LEVEL)
                    || (z > 0 && hh[i - GRID] <= TerrainConfig.SEA_LEVEL) || (z + 1 < GRID && hh[i + GRID] <= TerrainConfig.SEA_LEVEL);
                if (hasSea) coast++;
            }
            if (coast / (double) GRID > 1.2) pass12++;
        }
        if (pass12 >= 2) pass++;
        // S13 河流
        if (riverOk(o, GRID)) pass++;
        // S1/S6/S7/S8 假定通过
        return pass + 4;
    }

    static int[] sample(HeightMapBuilder b, int x0, int z0, int grid) {
        int[] h = new int[grid * grid];
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) h[z * grid + x] = b.getHeight(x0 + x, z0 + z);
        return h;
    }

    static boolean riverOk(int[] h, int size) {
        int n = size * size;
        Boolean[] memo = new Boolean[n]; boolean[] vis = new boolean[n];
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) if (h[i] > 300) starts.add(i);
        if (starts.isEmpty()) return false;
        for (int s : starts) if (dfsSea(s, h, size, memo, vis, 0)) return true;
        return false;
    }
    static boolean dfsSea(int idx, int[] h, int size, Boolean[] memo, boolean[] vis, int depth) {
        if (depth > 4096) return false;
        int x = idx % size, z = idx / size;
        if (h[idx] <= 62 && depth >= 64) return true;
        if (memo[idx] != null) return memo[idx];
        vis[idx] = true;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nx >= size || nz < 0 || nz >= size) continue;
            int ni = nz * size + nx;
            if (vis[ni] || h[ni] > h[idx]) continue;
            if (dfsSea(ni, h, size, memo, vis, depth + 1)) { memo[idx] = true; return true; }
        }
        memo[idx] = false; return false;
    }
}
