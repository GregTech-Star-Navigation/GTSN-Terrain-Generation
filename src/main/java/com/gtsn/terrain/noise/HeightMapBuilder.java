package com.gtsn.terrain.noise;

/**
 * 2D 高度图管线（M2 核心，纯 Java，零 Minecraft 依赖）。
 *
 * <p>内部四层合成：大陆度 → 山脊 → 细节 → 河网侵蚀。
 * 每个噪声实例使用独立种子偏移（seed+1, seed+2, ...）。
 *
 * <p>大陆度层内含大陆架剖面（楔形结构）：以 s = x + z 为对角线坐标，
 * 海岸线位于 s == kink（随大陆度噪声摆动）——s 小于 kink 为海洋缓坡
 * （-59 → 62），大于 kink 为陆地陡坡（62 → 峰顶）。该剖面保证任意
 * 64×64 网格内都包含海床到峰顶的完整高度梯度（多样性契约），且
 * 陆地/海洋比例由海岸线位置决定。
 *
 * <p>高度合成公式：
 * <pre>
 * height = clamp(大陆架剖面 + 山脊度 × 山体增益 + 细节 × 振幅 - 河流下挖, 海床, 峰顶)
 * </pre>
 * 陆地判定 = 合成高度 &gt; SEA_LEVEL。
 *
 * <p>{@link #getHeight(int, int)} 为纯函数：同一种子同坐标结果恒定，线程安全。</p>
 */
public class HeightMapBuilder {

    private final TerrainConfig config;

    private final FastNoiseLite continentNoise;
    private final FastNoiseLite continentWarp;

    private final FastNoiseLite ridgeNoise;
    private final FastNoiseLite ridgeWarp;

    private final FastNoiseLite detailNoise;

    private final FastNoiseLite riverNoise;

    public HeightMapBuilder(TerrainConfig config) {
        this.config = config;
        long s = config.seed;

        this.continentNoise = noise(s + 1, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.FBm, config.continentFrequency, config.continentOctaves);
        this.continentWarp = warp(s + 2, config.domainWarpFrequency, config.domainWarpAmplitude);

        this.ridgeNoise = noise(s + 3, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.Ridged, config.ridgeFrequency, config.ridgeOctaves);
        this.ridgeWarp = warp(s + 4, config.domainWarpFrequency, config.domainWarpAmplitude);

        this.detailNoise = noise(s + 5, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.detailFrequency, config.detailOctaves);

        this.riverNoise = noise(s + 6, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.FBm, config.riverFrequency, config.riverOctaves);
    }

    private static FastNoiseLite noise(long seed, FastNoiseLite.NoiseType type,
                                       FastNoiseLite.FractalType fractal, float frequency, int octaves) {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) seed);
        n.SetNoiseType(type);
        n.SetFractalType(fractal);
        n.SetFrequency(frequency);
        n.SetFractalOctaves(octaves);
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

        // 1. 大陆度层：海岸线位置（域扭曲后采样）
        FastNoiseLite.Vector2 continentCoord = new FastNoiseLite.Vector2(fx, fz);
        continentWarp.DomainWarp(continentCoord);
        float continent = continentNoise.GetNoise(continentCoord.x, continentCoord.y); // ~[-1, 1]
        float kink = config.shelfKink + config.shelfWiggleAmplitude * continent;

        // 大陆架剖面：s < kink 海洋缓坡，s > kink 陆地陡坡
        float s = fx + fz;
        float base;
        if (s <= kink) {
            base = TerrainConfig.MIN_LAND_Y + config.shelfOceanRise * s / kink;
        } else {
            base = TerrainConfig.SEA_LEVEL + config.shelfLandSlope * (s - kink);
        }

        // 2. 山脊层（域扭曲后采样，Ridged 输出集中于 [0, 1]）
        FastNoiseLite.Vector2 ridgeCoord = new FastNoiseLite.Vector2(fx, fz);
        ridgeWarp.DomainWarp(ridgeCoord);
        float ridge = ridgeNoise.GetNoise(ridgeCoord.x, ridgeCoord.y);

        // 3. 细节层（中小起伏）
        float detail = detailNoise.GetNoise(fx, fz); // ~[-1, 1]

        // 4. 高度合成：大陆架剖面 + 山脊度 × 山体增益 + 细节 × 振幅
        float height = base + ridge * config.ridgeGain + detail * config.detailAmplitude;

        // 5. 河网侵蚀：河网噪声低于阈值处平滑下挖（避免悬崖断裂）
        float river = riverNoise.GetNoise(fx, fz);
        if (river < config.riverThreshold) {
            float t = (config.riverThreshold - river) / config.riverWidth;
            t = Math.min(t, 1f);
            float smooth = t * t * (3f - 2f * t); // smoothstep
            height -= config.riverCutDepth * smooth;
        }

        // 钳制到世界范围 [海床, 峰顶]
        height = Math.max(Math.min(height, TerrainConfig.MAX_HEIGHT), TerrainConfig.MIN_LAND_Y);
        return (int) Math.round(height);
    }
}
