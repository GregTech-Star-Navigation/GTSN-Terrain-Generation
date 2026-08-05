import com.gtsn.terrain.noise.*;

/** T4 综合扫描：扫 plateOffset × mountainOffset × baseGain，用 getHeight(侵蚀后) 测全 13 契约。
 *  目标：找同时满足 s2(origin 25-45% 陆) + s4(≤8) + s5(w1>450 distinct) + s9(w1≥200 峰点) +
 *  s10(每窗低地 35-60%) + s11(avg<22° steep30<15%) + s13(origin 河流) 的组合。 */
public class ProbeFull {
    static long SEED = 20260803L;
    static final int GRID = 256;
    static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
        int[][] PLATES = {{-300,-300},{-600,-1200},{90,900},{600,-300},{-300,-1800},{-2400,-1200},{-1200,300},{300,800},{1500,400},{-800,1500}};
        int[][] MOUNTAINS = {{1000,0},{-2500,1000},{-2000,-1600},{-800,-800},{1600,400},{-800,3200},{1500,-1500},{0,0},{2500,0},{-1200,600}};
        float[] BASE_GAINS = {80f, 120f, 150f};
        int best = 0; String bestLine = "";
        int total = PLATES.length * MOUNTAINS.length * BASE_GAINS.length; int done = 0;
        for (float bg : BASE_GAINS) for (int[] po : PLATES) for (int[] mo : MOUNTAINS) {
            cfg.baseElevationGain = bg;
            cfg.plateOffsetX = po[0]; cfg.plateOffsetZ = po[1];
            cfg.mountainOffsetX = mo[0]; cfg.mountainOffsetZ = mo[1];
            int[] r = metrics(b, cfg);
            int pass = 0; for (int i = 1; i <= 13; i++) pass += r[i];
            done++;
            String line = String.format("bg=%3.0f p=(%5d,%4d) m=(%5d,%4d) | %d/13 | s2=%.1f%% s4=%d s5=%d s9=%d s10o=%.0f%% s10w1=%.0f%% s10w2=%.0f%% s11o=%.1f° s11w1=%.1f° s11w2=%.1f° st30o=%.1f%% s13=%s",
                bg, po[0], po[1], mo[0], mo[1], pass, r[2]*100.0/65536, r[4], r[5], r[9], r[10], r[11], r[12], r[13], r[14], r[15], r[16], r[17]==1?"Y":"N");
            if (pass >= 10) System.out.println(line);
            if (pass > best) { best = pass; bestLine = line; }
            if (done % 30 == 0) System.out.println("  ...progress " + done + "/" + total);
        }
        System.out.println("== BEST: " + bestLine);
    }

    /** r[1..13]=契约 1-13 pass(1/0)；r[2]..r[17] 为诊断数值 */
    static int[] metrics(HeightMapBuilder b, TerrainConfig cfg) {
        int[] r = new int[20];
        // S2 海陆比 origin
        int[] o = sample(b, 0, 0, GRID);
        int land = 0; for (int h : o) if (h > TerrainConfig.SEA_LEVEL) land++;
        double ratio = 100.0 * land / (GRID * GRID);
        r[1] = (ratio >= 25 && ratio <= 45) ? 1 : 0; r[2] = (int) ratio;
        // S3 范围
        int maxH = Integer.MIN_VALUE, minH = Integer.MAX_VALUE;
        for (int h : o) { maxH = Math.max(maxH, h); minH = Math.min(minH, h); }
        r[3] = (maxH <= TerrainConfig.MAX_HEIGHT && minH >= -60) ? 1 : 0;
        // S4 连续性
        int maxDelta = 0;
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            int h = o[z * GRID + x];
            if (x + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[z * GRID + x + 1]));
            if (z + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[(z + 1) * GRID + x]));
        }
        r[4] = maxDelta; r[3] = r[3] & (maxDelta <= 8 ? 1 : 0); r[4] = (maxDelta <= 8) ? 1 : 0; r[4] = (maxDelta <= 8) ? 1 : 0;
        // S5 多样性 w1
        int[] w1 = sample(b, -1024, 0, GRID);
        boolean[] seen = new boolean[TerrainConfig.MAX_HEIGHT + 65];
        int distinct = 0;
        for (int h : w1) { int idx = h + 64; if (idx >= 0 && idx < seen.length && !seen[idx]) { seen[idx] = true; distinct++; } }
        r[5] = distinct; r[4] = r[4]; // keep s4 flag
        int s4ok = (maxDelta <= 8) ? 1 : 0;
        // S9 走向 w1
        java.util.List<int[]> pts = new java.util.ArrayList<>();
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) if (w1[z * GRID + x] > 400) pts.add(new int[]{x, z});
        int s9ok = 0;
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
            if (Math.sqrt(l1/l2) > 1.5) s9ok = 1;
        }
        r[6] = (distinct > 450) ? 1 : 0; r[7] = s9ok; r[9] = pts.size();
        // S10 金字塔 3 窗口
        int s10ok = 1; double[] lows = new double[3], alps = new double[3];
        for (int wi = 0; wi < 3; wi++) {
            int[] hh = sample(b, M6_WINDOWS[wi][0], M6_WINDOWS[wi][1], GRID);
            int lnd = 0, lw = 0, alp = 0;
            for (int h : hh) { if (h > TerrainConfig.SEA_LEVEL) { lnd++; if (h <= 140) lw++; if (h > 400) alp++; } }
            if (lnd < 500) { lows[wi] = -1; continue; }
            lows[wi] = 100.0 * lw / lnd; alps[wi] = 100.0 * alp / lnd;
            if (!(lows[wi] >= 35 && lows[wi] <= 60 && alps[wi] < 10)) s10ok = 0;
        }
        r[10] = (int) Math.round(lows[0]); r[11] = (int) Math.round(lows[1]); r[12] = (int) Math.round(lows[2]);
        r[8] = s10ok;
        // S11 坡度 3 窗口
        int s11ok = 1; double[] avgs = new double[3], sts = new double[3];
        for (int wi = 0; wi < 3; wi++) {
            int[] hh = sample(b, M6_WINDOWS[wi][0], M6_WINDOWS[wi][1], GRID);
            int base = 8; double sum = 0; int cnt = 0, st30 = 0;
            for (int z = base; z < GRID - base; z++) for (int x = base; x < GRID - base; x++) {
                int i = z * GRID + x;
                int dx = Math.abs(hh[i + base] - hh[i - base]);
                int dz = Math.abs(hh[(z + base) * GRID + x] - hh[(z - base) * GRID + x]);
                double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / (2.0 * base)));
                sum += deg; cnt++; if (deg > 30.0) st30++;
            }
            avgs[wi] = sum / cnt; sts[wi] = 100.0 * st30 / cnt;
            if (!(avgs[wi] < 22.0 && sts[wi] < 15.0)) s11ok = 0;
        }
        r[13] = (int) Math.round(avgs[0]); r[14] = (int) Math.round(avgs[1]); r[15] = (int) Math.round(avgs[2]);
        r[16] = (int) Math.round(sts[0]); r[17] = s11ok;
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
        // S13 河流
        int s13ok = riverOk(o, GRID) ? 1 : 0;
        // 汇总：S1/S6/S7/S8 假定恒过（确定性/海陆一致），单独验证
        int pass = r[1] + r[3] + s4ok + r[6] + r[7] + r[8] + s11ok + (pass12 >= 2 ? 1 : 0) + s13ok;
        r[0] = pass; r[4] = s4ok; r[17] = s13ok;
        r[18] = (int) Math.round(sts[1]); r[19] = (int) Math.round(sts[2]);
        return r;
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
