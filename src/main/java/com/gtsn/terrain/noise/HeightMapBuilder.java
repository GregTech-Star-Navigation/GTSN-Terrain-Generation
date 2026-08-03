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
 * landY  = 62 + c·大陆海拔增益
 *          + 山链骨架层 + 尖峰细节层 + 细节 - 河流下挖   (c &gt; 0，钳制 [62, 580])
 * </pre>
 * 海岸线过渡：内陆因子在 c=0 处值为 0 且导数为 0（smoothstep 端点性质），海岸无山；
 * 海陆两侧在 c=0 处同汇海平面 62，天然连续无悬崖。
 *
 * <p>M5 山链系统（替换 M4 的单层 smoothstep(ridge01) 平台山），三尺度分离保证
 * 「峰顶尖 + 连续<=8 + 高度480」可兼得（各层独立坡度预算）：
 * <ul>
 *   <li>走向角度场：低频 OpenSimplex2（chainAngleFrequency）给出每处山脉走向角 θ∈[0,π)，
 *       链随位置缓慢转向，天然弯曲；山体层与链脊层共用。</li>
 *   <li>山体层：平滑 FBm 经域扭曲 + 旋转到走向角 + 各向异性采样（沿向低频率、垂向高频率）
 *       → 带状山体带；大增益（massifGain）提供大尺度山体质量，平滑噪声梯度平缓，
 *       连续性预算安全。</li>
 *   <li>链脊层：低频 Ridged 同样各向异性采样，V 型尖脊（Ridged 在 |n|=0 处为尖点）
 *       骑在山体带上，中等增益（chainGain）。</li>
 *   <li>尖峰细节层：中频 Ridged，小增益（ridgeGain），叠加出山峰/垭口的尖峰形态。</li>
 *   <li>三层均用 pow(·, p&gt;1) 曲线：峰顶尖、山脚缓（mask=1 处导数非零，保留 V 型尖点；
 *       M4 的 smoothstep 在该处导数为 0，把峰顶磨成平台）。</li>
 *   <li>山链与大陆度耦合：门控用线性斜坡 min(1, c/inlandRamp=0.65)（海岸无山、内陆起山）。
 *       M4 的 smoothstep 门控在过渡带相对导数 ~4.3/单位 c，乘上山体增益即 4-5 格/格坡度
 *       （origin maxDelta 10 的根因）；线性斜坡坡度恒为 1/ramp，比 smoothstep 低 ~3 倍，
 *       连续预算释放后 origin 高度可达 480+ 且相邻差 ≤8。origin c_max≈0.59 时门控 0.91。</li>
 * </ul>
 *
 * <p>海陆判定 {@link #isLand(int, int)} = 大陆度 c &gt; 0，大陆度采样
 * {@link #continentalness(int, int)} = c（clamp [-1,1]）——与
 * {@link #getHeight(int, int)} 共用同一大陆度噪声实例与同一公式，
 * 保证海陆划分与地形严格一致（M3-A 契约）。
 *
 * <p>{@link #getHeight(int, int)} 为纯函数：同一种子同坐标结果恒定，线程安全。</p>
 */
public class HeightMapBuilder {

    /**
     * raw 高度纯函数接口（M6：侵蚀前的高度采样，供 {@link HeightCache} 在区块粒度调用）。
     * 同种子同坐标必然恒定（纯函数），是跨区块一致性的根基。
     */
    public interface RawHeight {
        float rawHeight(int x, int z);
    }

    private final TerrainConfig config;

    private final FastNoiseLite continentNoise;
    private final FastNoiseLite continentWarp;

    private final FastNoiseLite ridgeNoise;

    private final FastNoiseLite chainAngleNoise;
    private final FastNoiseLite massifNoise;
    private final FastNoiseLite massifWarp;
    private final FastNoiseLite chainSkeletonNoise;
    private final FastNoiseLite chainWarp;

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

        this.chainAngleNoise = noise(s + 10, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.None, config.chainAngleFrequency, 1, 0.5f);

        this.massifNoise = noise(s + 13, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.massifFrequency, config.massifOctaves, 0.5f);

        this.massifWarp = warp(s + 14, config.massifWarpFrequency, config.massifWarpAmplitude);

        this.chainSkeletonNoise = noise(s + 11, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.Ridged, config.chainFrequency, config.chainOctaves, 0.5f);

        this.chainWarp = warp(s + 12, config.chainWarpFrequency, config.chainWarpAmplitude);

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

        // 2. 山体层：山体（平滑大尺度质量）+ 链脊（V 型尖脊）+ 尖峰细节（中尺度尖峰）。
        //    pow(·, p>1) 曲线：峰顶尖、山脚缓；在 mask=1 处导数非零，保留 V 型尖点（M4 smoothstep 磨平了它）。
        //    门控用线性斜坡 min(1, c/ramp)（非 smoothstep）：smoothstep 在过渡带相对导数 ~4.3/单位 c，
        //    乘上山体增益即 4-5 格/格坡度（origin maxDelta 10 的根因）；线性斜坡坡度恒为 1/ramp，
        //    比 smoothstep 低 ~3 倍，连续预算大幅释放。
        float inland = inlandGate(c);
        float massifHeight = config.massifGain
            * (float) Math.pow(massifMaskAt(fx, fz), config.massifCurvePower) * inland;
        float chainHeight = config.chainGain
            * (float) Math.pow(chainMaskAt(fx, fz), config.chainCurvePower) * inland;
        float ridgeHeight = config.ridgeGain
            * (float) Math.pow(ridgeAt(fx, fz), config.ridgeCurvePower) * inland;

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
            // 5. 山体合成：山体 + 链脊 + 尖峰（均已被内陆因子门控，海岸无山）
            height = base + massifHeight + chainHeight + ridgeHeight + detail * config.detailAmplitude;

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

    /**
     * 旋转到走向角 θ 后的各向异性采样坐标（θ 由低频角度场给出，链随位置缓慢转向）。
     *
     * <p>先对 (fx,fz) 做域扭曲（链自然弯曲），再按走向角旋转到 (沿向, 垂向) 坐标系，
     * 最后各向异性缩放：沿向×alongScale（低频率 → 链更长）、垂向×crossScale（高频率 → 链更窄）。
     */
    private float[] chainSampledCoords(float fx, float fz, FastNoiseLite warp,
                                       float alongScale, float crossScale) {
        FastNoiseLite.Vector2 p = new FastNoiseLite.Vector2(fx, fz);
        warp.DomainWarp(p);
        float theta = (chainAngleNoise.GetNoise(fx, fz) + 1f) * 0.5f * (float) Math.PI;
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        float along = p.x * cos + p.y * sin;
        float cross = -p.x * sin + p.y * cos;
        return new float[]{along * alongScale, cross * crossScale};
    }

    /** 山体层蒙版采样（平滑单八度各向异性带状带，无重缩放避免坡度放大），输出 [0,1] */
    private float massifMaskAt(float fx, float fz) {
        float[] c = chainSampledCoords(fx, fz, massifWarp,
            config.massifAlongScale, config.massifCrossScale);
        float r = massifNoise.GetNoise(c[0], c[1]);
        return Math.max(0f, Math.min(1f, (r + 1f) * 0.5f));
    }

    /**
     * 链脊蒙版采样（低频 Ridged 各向异性带状链），输出 [0,1]。
     * Ridged 的脊线为 V 型尖点（|n|=0 处），由链脊曲线 pow&gt;1 保留尖峰形态。
     */
    private float chainMaskAt(float fx, float fz) {
        float[] c = chainSampledCoords(fx, fz, chainWarp,
            config.chainAlongScale, config.chainCrossScale);
        float r = chainSkeletonNoise.GetNoise(c[0], c[1]);
        return Math.max(0f, Math.min(1f, (r + 1f) * 0.5f));
    }

    /** 内陆门控：线性斜坡 min(1, c/ramp)。坡度恒为 1/ramp（非 smoothstep 的 ~4.3/单位 c 相对导数） */
    private float inlandGate(float c) {
        return Math.min(1f, Math.max(0f, c / config.inlandRamp));
    }

    private static float smoothstep01(float t) {
        return t * t * (3f - 2f * t);
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
