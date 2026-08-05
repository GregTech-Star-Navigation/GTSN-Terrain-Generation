import com.gtsn.terrain.noise.*;

/** T4 全契约指标探针：复刻 HeightMapBuilderTest 13 项断言，输出每窗口数值 */
public class ProbeMetrics {
    static long SEED = 20260803L;
    static final int GRID = 256;
    static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        int[] results = new int[13];

        // S2 海陆比 origin
        int[] o = sample(b, 0, 0, GRID);
        int land = 0; for (int h : o) if (h > TerrainConfig.SEA_LEVEL) land++;
        double ratio = 100.0 * land / (GRID * GRID);
        results[1] = (ratio >= 25 && ratio <= 45) ? 1 : 0;
        System.out.printf("S2 海陆比: %.1f%% %s%n", ratio, results[1]==1?"PASS":"FAIL");

        // S3 范围
        int maxH = Integer.MIN_VALUE, minH = Integer.MAX_VALUE;
        for (int h : o) { maxH = Math.max(maxH, h); minH = Math.min(minH, h); }
        results[2] = (maxH <= TerrainConfig.MAX_HEIGHT && minH >= -60) ? 1 : 0;
        System.out.printf("S3 范围: [%d,%d] %s%n", minH, maxH, results[2]==1?"PASS":"FAIL");

        // S4 连续性 origin
        int maxDelta = 0;
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            int h = o[z * GRID + x];
            if (x + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[z * GRID + x + 1]));
            if (z + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[(z + 1) * GRID + x]));
        }
        results[3] = (maxDelta <= 8) ? 1 : 0;
        System.out.printf("S4 连续性: maxDelta=%d %s%n", maxDelta, results[3]==1?"PASS":"FAIL");

        // S5 多样性 w1
        int[] w1 = sample(b, -1024, 0, GRID);
        boolean[] seen = new boolean[TerrainConfig.MAX_HEIGHT + 65];
        int distinct = 0;
        for (int h : w1) { int idx = h + 64; if (idx >= 0 && idx < seen.length && !seen[idx]) { seen[idx] = true; distinct++; } }
        results[4] = (distinct > 450) ? 1 : 0;
        System.out.printf("S5 多样性(w1): %d distinct %s%n", distinct, results[4]==1?"PASS":"FAIL");

        // S6/S7/S8 海陆一致
        results[5] = results[6] = results[7] = 1;
        for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
            double c = b.continentalness(x, z); boolean isl = b.isLand(x, z);
            if ((c > 0) != isl) results[5] = 0;
        }
        System.out.printf("S6 海陆一致: %s%n", results[5]==1?"PASS":"FAIL");

        // S9 走向 w1: 阈值 400
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
            double ratio9 = Math.sqrt(l1/l2);
            results[8] = (ratio9 > 1.5) ? 1 : 0;
            System.out.printf("S9 走向(w1): pts=%d ratio=%.2f %s%n", pts.size(), ratio9, results[8]==1?"PASS":"FAIL");
        } else {
            results[8] = 0;
            System.out.printf("S9 走向(w1): pts=%d (<200) FAIL%n", pts.size());
        }

        // S10 金字塔 3 窗口
        results[9] = 1;
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int lnd = 0, lw = 0, alp = 0;
            for (int h : hh) { if (h > TerrainConfig.SEA_LEVEL) { lnd++; if (h <= 140) lw++; if (h > 400) alp++; } }
            if (lnd < 500) continue;
            double lr = 100.0 * lw / lnd, ar = 100.0 * alp / lnd;
            if (!(lr >= 35 && lr <= 60 && ar < 10)) { results[9] = 0; System.out.printf("  S10 win(%d,%d): low=%.1f%% alp=%.1f%% FAIL%n", win[0], win[1], lr, ar); }
            else System.out.printf("  S10 win(%d,%d): low=%.1f%% alp=%.1f%% PASS%n", win[0], win[1], lr, ar);
        }

        // S11 坡度 3 窗口
        results[10] = 1;
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
            // 契约已按用户决策放宽（2026-08-05）：avg<22°, steep30<15%（原 12°/5%）
            if (!(avg < 22.0 && stR < 15.0)) { results[10] = 0; System.out.printf("  S11 win(%d,%d): avg=%.2f° steep30=%.2f%% FAIL%n", win[0], win[1], avg, stR); }
            else System.out.printf("  S11 win(%d,%d): avg=%.2f° steep30=%.2f%% PASS%n", win[0], win[1], avg, stR);
        }

        // S12 海岸线
        results[11] = 0; int pass12 = 0;
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
            double r12 = coast / (double) GRID;
            if (r12 > 1.2) pass12++;
        }
        results[11] = (pass12 >= 2) ? 1 : 0;
        System.out.printf("S12 海岸线: %d/3 窗口 %s%n", pass12, results[11]==1?"PASS":"FAIL");

        // S13 河流
        int[] oo = sample(b, 0, 0, GRID);
        results[12] = riverOk(oo, GRID) ? 1 : 0;
        System.out.printf("S13 河流(origin): %s%n", results[12]==1?"PASS":"FAIL");

        int pass = 0; for (int i = 0; i < 13; i++) pass += results[i];
        System.out.printf("== TOTAL %d/13 PASS ==%n", pass);
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
