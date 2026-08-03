package com.gtsn.terrain.noise;

/**
 * 2D 高度图管线（纯 Java，零 Minecraft 依赖）。
 *
 * <p>内部五层合成：大陆度 → 山脊 → 细节 → 河网侵蚀 → 海陆合成。
 * 每个噪声实例使用独立种子偏移（seed+1, seed+2, ...）。
 *
 * <p>大陆度层为真正的 2D 大陆度驱动（重构：彻底移除 M2 的 s=x+z 一维对角线剖面）：
 * OpenSimplex2 + FBm 低频采样 c ∈ [-1,1]（板块尺度 ~数百格，2 八度 + 低分形增益
 * 控制海岸梯度），加一层域扭曲让海岸线自然弯曲。海陆阈值 = 0：c &gt; 0 陆地，
 * c &lt;= 0 海洋。大陆度同时决定海床深度（c 越负越深）与陆地基础海拔（c 越大越高）。
 *
 * <p>高度合成公式：
 * <pre>
 * oceanY = 62 - 121·smoothstep(-c/0.4) + 细节×半振幅   (c ≤ 0，钳制 [-59, 62])
 * landY  = 62 + c·大陆海拔增益 + smoothstep(ridge01)·山体增益·内陆因子 + 细节 - 河流下挖
 * height = c ≤ 0 ? clamp(oceanY, -59, 62) : clamp(landY, 62, 580)
 * </pre>
 * 海岸线过渡：内陆因子在 c=0 处值为 0 且导数为 0（smoothstep 端点性质），海岸无山；
 * 海陆两侧在 c=0 处同汇海平面 62，天然连续无悬崖。
 * 山脊用 smoothstep(ridge01) 而非 pow 锐化：smoothstep 在 ridge01=0/1 处导数为 0，
 * 杀死 Ridged 噪声 V 型山脊线的陡峭尖点（连续性 <=8 的关键）。
 *
 * <p>海陆判定 {@link #isLand(int, int)} = 大陆度 c &gt; 0，大陆度采样
 * {@link #continentalness(int, int)} = c（clamp [-1,1]）——与
 * {@link #getHeight(int, int)} 共用同一大陆度噪声实例与同一公式，
 * 保证海陆划分与地形严格一致（M3-A 契约）。
 *
 * <p>{@link #getHeight(int, int)} 为纯函数：同一种子同坐标结果恒定，线程安全。</p>
 */
public class HeightMapBuilder {

    private final TerrainConfig config;

    private final FastNoiseLite continentNoise;
    private final FastNoiseLite continentWarp;

    private final FastNoiseLite ridgeNoise;

    private final FastNoiseLite detailNoise;

    private final FastNoiseLite riverNoise;

    public HeightMapBuilder(TerrainConfig config) {
        this.config = config;
        long s = config.seed;

        this.continentNoise = noise(s + 1, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.FBm, config.continentFrequency, config.continentOctaves,
            config.continentFractalGain);
        this.continentWarp = warp(s + 2, config.continentWarpFrequency, config.continentWarpAmplitude);

        this.ridgeNoise = noise(s + 3, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.Ridged, config.ridgeFrequency, config.ridgeOctaves, 0.5f);

        this.detailNoise = noise(s + 5, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.detailFrequency, config.detailOctaves, 0.5f);

        this.riverNoise = noise(s + 6, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.FBm, config.riverFrequency, config.riverOctaves, 0.5f);
    }

    private static FastNoiseLite noise(long seed, FastNoiseLite.NoiseType type,
                                       FastNoiseLite.FractalType fractal, float frequency, int octaves,
                                       float fractalGain) {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) seed);
        n.SetNoiseType(type);
        n.SetFractalType(fractal);
        n.SetFrequency(frequency);
        n.SetFractalOctaves(octaves);
        n.SetFractalGain(fractalGain);
        return n;
    }

    private static FastNoiseLite warp(long seed, float frequency, float amplitude) {
        FastNoiseLite w = new FastNoiseLite();
        w.SetSeed((int) seed);
        w.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        w.SetFractalType(FastNoiseLite.FractalType.None);
        w.SetFrequency(frequency);
        w.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        w.SetDomainWarpAmp(amplitude);
        return w;
    }

    /**
     * 计算 (x, z) 处的方块 Y 坐标（海床 -59 ~ 峰顶 580）。
     *
     * @param x 世界 X 坐标（方块）
     * @param z 世界 Z 坐标（方块）
     * @return 地表方块 Y 坐标
     */
    public int getHeight(int x, int z) {
        float fx = x;
        float fz = z;

        // 1. 大陆度层：2D 低频采样（域扭曲后），海陆阈值 0
        float c = continentalnessAt(fx, fz);

        // 2. 山脊层：Ridged 归一化到 [0,1] 后 smoothstep（导数为 0 端点杀死尖点悬崖）
        float ridgeIntensity = smoothstep01(ridgeAt(fx, fz));

        // 3. 细节层（中小起伏）
        float detail = detailNoise.GetNoise(fx, fz); // ~[-1, 1]

        float height;
        if (c <= 0) {
            // 海洋：c 越负越深，smoothstep 深度映射（c <= -oceanDepthScale 处达海床）
            float depth = smoothstep01(Math.min(1f, -c / config.oceanDepthScale));
            height = TerrainConfig.SEA_LEVEL
                + (TerrainConfig.MIN_LAND_Y - TerrainConfig.SEA_LEVEL) * depth
                + detail * config.detailAmplitude * 0.5f;
            // 海洋高度钳制到 [海床, 海平面]（c 略负也可能被细节顶到 62，属海岸浅滩）
            height = Math.max(Math.min(height, TerrainConfig.SEA_LEVEL), TerrainConfig.MIN_LAND_Y);
        } else {
            // 4. 陆地基础海拔：大陆度越高海拔越高（海岸平原 → 大陆内陆丘陵）
            float base = TerrainConfig.SEA_LEVEL + c * config.continentElevationGain;
            // 5. 山体调制：只在陆地且偏向内陆（海岸无山，向内陆渐高）
            float inland = inlandFactor(c);
            height = base + ridgeIntensity * config.ridgeGain * inland + detail * config.detailAmplitude;

            // 6. 河网侵蚀：河流噪声低于阈值处平滑下挖（避免悬崖断裂）
            float river = riverNoise.GetNoise(fx, fz);
            if (river < config.riverThreshold) {
                float t = (config.riverThreshold - river) / config.riverWidth;
                t = Math.min(t, 1f);
                float smooth = t * t * (3f - 2f * t); // smoothstep
                height -= config.riverCutDepth * smooth;
            }

            // 钳制到 [海平面, 峰顶]
            height = Math.max(Math.min(height, TerrainConfig.MAX_HEIGHT), TerrainConfig.SEA_LEVEL);
        }
        return (int) Math.round(height);
    }

    /**
     * 海陆判定：2D 大陆度 c &gt; 0 为陆，c &lt;= 0 为海。
     * 与 {@link #getHeight(int, int)} 共用同一大陆度噪声实例与同一公式，
     * 保证海陆划分与地形严格一致。
     *
     * @param x 世界 X 坐标（方块）
     * @param z 世界 Z 坐标（方块）
     * @return true 为陆地，false 为海洋
     */
    public boolean isLand(int x, int z) {
        return continentalnessAt(x, z) > 0f;
    }

    /**
     * 大陆度值（域扭曲后采样，约 [-1,1]），供群系温度/湿度计算参考。
     * 与 {@link #getHeight(int, int)} 的大陆度层完全同源。
     *
     * @param x 世界 X 坐标（方块）
     * @param z 世界 Z 坐标（方块）
     * @return [-1, 1]，-1 深海洋，+1 大陆核心
     */
    public double continentalness(int x, int z) {
        return clampUnit(continentalnessAt(x, z));
    }

    /** 大陆度层采样（域扭曲后），getHeight / isLand / continentalness 的唯一入口 */
    private float continentalnessAt(float fx, float fz) {
        FastNoiseLite.Vector2 coord = new FastNoiseLite.Vector2(fx, fz);
        continentWarp.DomainWarp(coord);
        return continentNoise.GetNoise(coord.x, coord.y);
    }

    /** 山脊层采样，Ridged 输出约 [-1,1]，归一化到 [0,1] */
    private float ridgeAt(float fx, float fz) {
        float r = ridgeNoise.GetNoise(fx, fz);
        return Math.max(0f, Math.min(1f, (r + 1f) * 0.5f));
    }

    /** 内陆因子：c ∈ (0, inlandRamp] 从 0 平滑升至 1（smoothstep，c=0 处值为 0 导数为 0） */
    private float inlandFactor(float c) {
        return smoothstep01(Math.min(1f, c / config.inlandRamp));
    }

    private static float smoothstep01(float t) {
        return t * t * (3f - 2f * t);
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
