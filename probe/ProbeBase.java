package com.gtsn.terrain.noise;

/**
 * 拆解 avgSlope/steep30 来源：海洋斜坡 vs 陆地基础 vs 分带噪声。
 * 通过反射调用私有 rawHeight 不便，改用 cfg 参数消除法：分别设 oceanDepthScale 大/小、分带 amp=0、gain=0 看坡度变化。
 */
public class ProbeBase {
    static final long SEED = 20260803L;

    public static void main(String[] args) {
        // 场景1：当前全部（分带 amp 保持默认，但 base gain 30）
        report("当前全部", cfg(30, 8f, 0f, 0f, 0f));
        // 场景2：baseGain=0（只剩海洋+内陆lift，无 base 抬升）
        report("baseGain=0", cfg(0, 8f, 0f, 0f, 0f));
        // 场景3：海洋 scale 变大（海岸坡更缓）
        report("海洋scale=4", cfg(30, 4f, 0f, 0f, 0f));
        // 场景4：山体关掉（massifGain=0 → 只剩 base+分带）
        report("山体=0", cfg(30, 8f, 1, 0, 0));
        // 场景5：山体+分带全关
        report("山体=0分带=0", cfg(30, 8f, 1, 1, 1));
    }

    static TerrainConfig cfg(int gain, float oceanScale, int noMassif, int noBand, int noRiver) {
        TerrainConfig c = new TerrainConfig(SEED);
        c.baseElevationGain = gain;
        c.oceanDepthScale = oceanScale;
        if (noMassif == 1) c.massifGain = 0;
        if (noBand == 1) { c.plainsAmplitude = 0; c.hillsAmplitude = 0; c.foothillAmplitude = 0; }
        if (noRiver == 1) c.riverCutDepth = 0;
        return c;
    }

    static void report(String name, TerrainConfig cfg) {
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        int N = 256, base = 8;
        int[][] h = new int[N][N];
        for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) h[z][x] = b.getHeight(x, z);
        // 也统计海/陆分开的坡度
        double slopeSum = 0; int slopeCount = 0, steep30 = 0;
        double landSum = 0; int landCount = 0, landSteep = 0;
        for (int z = base; z < N - base; z++) for (int x = base; x < N - base; x++) {
            int dx = Math.abs(h[z][x + base] - h[z][x - base]);
            int dz = Math.abs(h[z + base][x] - h[z - base][x]);
            double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / (2.0 * base)));
            slopeSum += deg; slopeCount++;
            if (deg > 30) steep30++;
            if (h[z][x] > 62) { landSum += deg; landCount++; if (deg > 30) landSteep++; }
        }
        System.out.printf("%s: 全avg=%.1f° 全st30=%.1f%% | 陆avg=%.1f° 陆st30=%.1f%% (陆格%d)%n",
            name, slopeSum / slopeCount, 100.0 * steep30 / slopeCount,
            landSum / Math.max(1, landCount), 100.0 * landSteep / Math.max(1, landCount), landCount);
    }
}
