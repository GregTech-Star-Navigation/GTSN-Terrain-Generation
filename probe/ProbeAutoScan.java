package com.gtsn.terrain.noise;

/**
 * M6 自动化参数扫描器：网格扫描关键参数组合，对 3 验收窗口跑分析器指标，
 * 自动找满足契约的组合。纯调试工具，不进模组 jar。
 *
 * 用法：java -cp build\classes\java\main com.gtsn.terrain.noise.ProbeAutoScan
 */
public class ProbeAutoScan {

    static final long SEED = 20260803L;

    public static void main(String[] args) {
        // 扫描维度：massifGain × 门控(lo→hi，上限必须≤mask峰值~0.75) × mask 频率
        int[] massifGains = {350, 450, 550, 650, 750};
        float[] maskLos = {0.38f, 0.45f, 0.52f};
        float[] maskHis = {0.65f, 0.72f, 0.78f};
        float[] maskFreqs = {0.0004f, 0.0006f};

        System.out.printf("massifGain | maskLo | maskHi | maskFreq | win0(max,land,maxD,avgS,st30,alp) | win1 | win2 | 达标数%n");
        System.out.println("-----------+--------+--------+----------+-------------------------------+------+------+--------");

        int bestPass = -1;
        String bestLine = null;

        for (int mg : massifGains) {
            for (float lo : maskLos) {
                for (float hi : maskHis) {
                    if (hi <= lo) continue;
                    for (float mf : maskFreqs) {
                        TerrainConfig cfg = new TerrainConfig(SEED);
                        cfg.massifGain = mg;
                        cfg.mountainMaskFrequency = mf;
                        cfg.mountainGateLo = lo;
                        cfg.mountainGateHi = hi;
                        HeightMapBuilder b = new HeightMapBuilder(cfg);

                        // win0 = origin (0,0)
                        int[] m0 = measure(b, 0, 0);
                        int[] m1 = measure(b, -1024, 0);
                        int[] m2 = measure(b, 512, 512);

                        // 达标判定（契约）：win0 max>=480, land 25-45, maxD<=8, avgS<22, st30<15, alp>0
                        int pass = 0;
                        if (m0[0] >= 480) pass++;
                        if (m0[1] >= 25 && m0[1] <= 45) pass++;
                        if (m0[2] <= 8) pass++;
                        if (m0[3] < 22) pass++;
                        if (m0[4] < 15) pass++;
                        if (m0[5] > 0) pass++;

                        String line = String.format(
                            "%7d | %6.2f | %6.2f | %9.5f | %d,%d,%d,%d,%d,%d | %d,%d | %d,%d | %d/6",
                            mg, lo, hi, mf,
                            m0[0], m0[1], m0[2], m0[3], m0[4], m0[5],
                            m1[0], m1[2], m2[0], m2[2], pass);
                        System.out.println(line);
                        if (pass > bestPass) {
                            bestPass = pass;
                            bestLine = line;
                        }
                    }
                }
            }
        }
        System.out.println("\n=== 最佳组合: " + bestPass + "/6 达标 ===");
        System.out.println(bestLine);
    }

    /** 测量窗口：返回 [max, land%, maxDelta, avgSlope, steep30%, alpine%] */
    static int[] measure(HeightMapBuilder b, int winX, int winZ) {
        int N = 256;
        int[][] h = new int[N][N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                h[z][x] = b.getHeight(winX + x, winZ + z);
            }
        }
        int max = Integer.MIN_VALUE, land = 0, maxD = 0, alpine = 0;
        double slopeSum = 0; int slopeCount = 0, steep30 = 0;
        int base = 8;
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int v = h[z][x];
                max = Math.max(max, v);
                if (v > 62) land++;
                if (v > 400) alpine++;
                if (x + 1 < N) maxD = Math.max(maxD, Math.abs(v - h[z][x + 1]));
                if (z + 1 < N) maxD = Math.max(maxD, Math.abs(v - h[z + 1][x]));
                if (z >= base && z < N - base && x >= base && x < N - base) {
                    int dx = Math.abs(h[z][x + base] - h[z][x - base]);
                    int dz = Math.abs(h[z + base][x] - h[z - base][x]);
                    double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / (2.0 * base)));
                    slopeSum += deg; slopeCount++;
                    if (deg > 30) steep30++;
                }
            }
        }
        return new int[]{
            max, (int) Math.round(100.0 * land / (N * N)), maxD,
            (int) Math.round(10.0 * slopeSum / Math.max(1, slopeCount)),
            (int) Math.round(100.0 * steep30 / Math.max(1, slopeCount)),
            (int) Math.round(100.0 * alpine / (N * N))};
    }
}
