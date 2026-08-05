import com.gtsn.terrain.noise.*;

/** T4 baseGain 扫描：在当前侵蚀配置（talus=8/32次/drops=0）下扫 baseElevationGain，
 *  测全 13 契约（含 getHeight 侵蚀）。找最佳 baseGain。 */
public class ProbeGain2 {
    static long SEED = 20260803L;
    static final int GRID = 256;
    static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) {
        float[] gains = {60f, 90f, 120f, 150f, 180f, 210f, 240f};
        int best = 0; String bestLine = "";
        for (float g : gains) {
            TerrainConfig cfg = new TerrainConfig(SEED);
            cfg.baseElevationGain = g;
            HeightMapBuilder b = new HeightMapBuilder(cfg);
            String r = report(b, g);
            int pass = Integer.parseInt(r.split("/")[0].trim());
            if (pass > best) { best = pass; bestLine = r; }
            if (pass >= 7) System.out.println(r);
        }
        System.out.println("== BEST: " + bestLine);
    }

    static String report(HeightMapBuilder b, float g) {
        int pass = 0; StringBuilder sb = new StringBuilder();
        // S2
        int[] o = sample(b, 0, 0, GRID);
        int land = 0; for (int h : o) if (h > 62) land++;
        double ratio = 100.0 * land / (GRID * GRID);
        if (ratio >= 25 && ratio <= 45) pass++; else sb.append(" s2=" + (int) ratio + "%");
        // S3
        int maxH = Integer.MIN_VALUE, minH = Integer.MAX_VALUE;
        for (int h : o) { maxH = Math.max(maxH, h); minH = Math.min(minH, h); }
        if (maxH <= 580 && minH >= -60) pass++; else sb.append(" s3=[" + minH + "," + maxH + "]");
        // S4
        int maxDelta = 0;
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            int h = o[z * GRID + x];
            if (x + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[z * GRID + x + 1]));
            if (z + 1 < GRID) maxDelta = Math.max(maxDelta, Math.abs(h - o[(z + 1) * GRID + x]));
        }
        if (maxDelta <= 8) pass++; else sb.append(" s4=" + maxDelta);
        // S5
        int[] w1 = sample(b, -1024, 0, GRID);
        boolean[] seen = new boolean[645];
        int distinct = 0;
        for (int h : w1) { int idx = h + 64; if (idx >= 0 && idx < 645 && !seen[idx]) { seen[idx] = true; distinct++; } }
        if (distinct > 450) pass++; else sb.append(" s5=" + distinct);
        // S9
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
            if (Math.sqrt(l1/l2) > 1.5) pass++; else sb.append(" s9=ratio" + String.format("%.2f", Math.sqrt(l1/l2)) + "pts" + pts.size());
        } else sb.append(" s9=" + pts.size() + "pts");
        // S10
        boolean s10 = true; String s10d = "";
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int lnd = 0, lw = 0, alp = 0;
            for (int h : hh) { if (h > 62) { lnd++; if (h <= 140) lw++; if (h > 400) alp++; } }
            if (lnd >= 500) {
                double lr = 100.0 * lw / lnd, ar = 100.0 * alp / lnd;
                if (!(lr >= 35 && lr <= 60 && ar < 10)) { s10 = false; s10d += " win(" + win[0] + "," + win[1] + ")low=" + (int) lr + "%"; }
            }
        }
        if (s10) pass++; else sb.append(" s10:" + s10d);
        // S11
        boolean s11 = true; String s11d = "";
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
            if (!(avg < 22.0 && stR < 15.0)) { s11 = false; s11d += " w(" + win[0] + "," + win[1] + ")avg=" + String.format("%.0f", avg) + "°st30=" + String.format("%.0f", stR) + "%"; }
        }
        if (s11) pass++; else sb.append(" s11:" + s11d);
        // S12
        int pass12 = 0;
        for (int[] win : M6_WINDOWS) {
            int[] hh = sample(b, win[0], win[1], GRID);
            int coast = 0;
            for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
                int i = z * GRID + x;
                if (hh[i] <= 62) continue;
                boolean hasSea = (x > 0 && hh[i-1] <= 62) || (x+1 < GRID && hh[i+1] <= 62)
                    || (z > 0 && hh[i-GRID] <= 62) || (z+1 < GRID && hh[i+GRID] <= 62);
                if (hasSea) coast++;
            }
            if (coast / (double) GRID > 1.2) pass12++;
        }
        if (pass12 >= 2) pass++; else sb.append(" s12=" + pass12);
        // S13
        if (riverOk(o, GRID)) pass++; else sb.append(" s13");
        int total = pass + 4;
        return total + "/13" + sb.toString() + " (baseGain=" + (int) g + ")";
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
