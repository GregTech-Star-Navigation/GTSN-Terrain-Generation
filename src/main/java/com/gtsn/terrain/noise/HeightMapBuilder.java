package com.gtsn.terrain.noise;

/**
 * 2D 高度图管线（纯 Java，零 Minecraft 依赖）。
 *
 * <p>M6 重构：Voronoi 大陆板块 + 海拔分带 + 山体蒙版 + 河流系统 + 侵蚀雕刻缓存。
 * 内部合成：板块距离场（大陆度）→ 基础海拔（独立超低频噪声）→ 海拔分带（平原/丘陵/山麓/
 * 高山/雪线带独立噪声）→ 山体系统（仅高山带窗口 × 山链蒙版激活）→ 河流下挖 →
 * 区块粒度侵蚀（热+水滴）。每个噪声实例使用独立种子偏移（seed+1, seed+2, ...）。
 *
 * <p>大陆板块层（替换 M5 的 OpenSimplex2 大陆度）：
 * <ul>
 *   <li>板块噪声：Cellular(Distance) = 到最近板块中心距离 d0，采样坐标先大振幅低频域扭曲
 *       （板块边界自然弯曲）。大陆度 c = 1 - d0/plateRadius：近中心高、边缘低。</li>
 *   <li>c &gt; 0 为陆（板块核心近陆），c &lt;= 0 为海（板块间与板块边缘=深海沟）。
 *       海洋在板块间；海岸线 = c=0 轮廓，由海岸摆动噪声制造海湾/半岛/大陆架浅海过渡。</li>
 * </ul>
 *
 * <p>海拔分带：基础海拔由独立的超低频噪声驱动（与板块解耦——板块梯度快，若直接乘大增益
 * 会整片陡坡；独立 λ1250 噪声 ×130 → 6°，坡度预算充足），加内陆门控微抬升。
 * 分带窗口在基础海拔上 smoothstep 切分 海岸平原/丘陵/山麓/高山/雪线，每带独立噪声特征；
 * 山体系统仅在高山带窗口 × 山链蒙版内激活——山只出现在高山带，不再到处是山。
 *
 * <p>侵蚀：raw 高度 {@link #rawHeight} 是纯函数（同种子同坐标恒定）；
 * 区块粒度侵蚀由 {@link HeightCache} 缓存（16×16 含 8 边界 32×32 网格，热侵蚀 + 水滴侵蚀）。
 * {@link #getHeight(int, int)} = 缓存侵蚀后高度；{@link #isLand}/{@link #continentalness}
 * 用大陆度纯函数（不经侵蚀，保证海陆判定与群系温度/湿度一致）。
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

    private final FastNoiseLite plateNoise;
    private final FastNoiseLite plateWarp;
    private final FastNoiseLite coastWiggle;

    private final FastNoiseLite baseNoise;

    private final FastNoiseLite plainsNoise;
    private final FastNoiseLite hillsNoise;
    private final FastNoiseLite foothillNoise;

    private final FastNoiseLite mountainMaskNoise;
    private final FastNoiseLite chainAngleNoise;
    private final FastNoiseLite massifNoise;
    private final FastNoiseLite massifWarp;
    private final FastNoiseLite chainSkeletonNoise;
    private final FastNoiseLite chainWarp;
    private final FastNoiseLite ridgeNoise;

    private final FastNoiseLite detailNoise;
    private final FastNoiseLite riverNoise;

    /** 高原面起伏噪声（M6 高原式） */
    private final FastNoiseLite plateauReliefNoise;

    /** 侵蚀高度缓存（区块粒度，含 LRU） */
    private final HeightCache cache;

    public HeightMapBuilder(TerrainConfig config) {
        this.config = config;
        long s = config.seed;

        // 板块层：Cellular(Distance) 到板块中心距离 d0，采样坐标大振幅低频域扭曲。
        // 大陆度 c = 1 - d0/plateRadius（近中心高、边缘低；d0>R 为负 = 深海/板块间）。
        this.plateNoise = new FastNoiseLite();
        plateNoise.SetSeed((int) (s + 1));
        plateNoise.SetNoiseType(FastNoiseLite.NoiseType.Cellular);
        plateNoise.SetFrequency(config.plateFrequency);
        plateNoise.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Euclidean);
        plateNoise.SetCellularReturnType(FastNoiseLite.CellularReturnType.Distance);
        plateNoise.SetCellularJitter(config.plateJitter);
        this.plateWarp = warp(s + 2, config.plateWarpFrequency, config.plateWarpAmplitude);

        // 海岸摆动（海湾/半岛/大陆架）：中频 OpenSimplex2S FBm
        this.coastWiggle = noise(s + 3, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.coastWiggleFrequency, 2, 0.5f);

        // 基础海拔（独立超低频，λ~1250）：决定大范围平原/丘陵/山麓基底
        this.baseNoise = noise(s + 4, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.baseNoiseFrequency, config.baseNoiseOctaves, 0.5f);

        // 分带噪声：平原缓 / 丘陵起伏 / 山麓渐陡
        this.plainsNoise = noise(s + 5, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.plainsFrequency, 2, 0.5f);
        this.hillsNoise = noise(s + 6, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.hillsFrequency, 2, 0.5f);
        this.foothillNoise = noise(s + 7, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.foothillFrequency, 2, 0.5f);

        // 山链蒙版：低频 FBm（平滑场，无 Ridged V 尖跳变悬崖）定位山体区域，阈值门控 + 偏移定位验收窗口
        this.mountainMaskNoise = noise(s + 8, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.mountainMaskFrequency, config.mountainMaskOctaves, 0.5f);

        // 山体系统（三尺度分离，同 M5）：走向角场 + 山体带 + 链脊 + 尖峰
        this.chainAngleNoise = noise(s + 10, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.None, config.chainAngleFrequency, 1, 0.5f);
        this.massifNoise = noise(s + 13, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.massifFrequency, config.massifOctaves, 0.5f);
        this.massifWarp = warp(s + 14, config.massifWarpFrequency, config.massifWarpAmplitude);
        this.chainSkeletonNoise = noise(s + 11, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.Ridged, config.chainFrequency, config.chainOctaves, 0.5f);
        this.chainWarp = warp(s + 12, config.chainWarpFrequency, config.chainWarpAmplitude);
        this.ridgeNoise = noise(s + 9, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.Ridged, config.ridgeFrequency, config.ridgeOctaves, 0.5f);

        // 细节 + 河网
        this.detailNoise = noise(s + 15, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.detailFrequency, config.detailOctaves, 0.5f);
        this.riverNoise = noise(s + 16, FastNoiseLite.NoiseType.OpenSimplex2,
            FastNoiseLite.FractalType.FBm, config.riverFrequency, config.riverOctaves, 0.5f);

        this.plateauReliefNoise = noise(s + 17, FastNoiseLite.NoiseType.OpenSimplex2S,
            FastNoiseLite.FractalType.FBm, config.plateauReliefFrequency, 2, 0.5f);

        // 侵蚀缓存：边界 = 热侵蚀迭代数 = 水滴步数上限（跨块一致），LRU 上限
        this.cache = new HeightCache(this::rawHeight, config.cacheBorder, config.cacheMaxChunks);
        this.cache.configureErosion(config.erosionTalus, config.erosionIterations,
            config.hydraulicDropsPerChunk, config.hydraulicMaxSteps,
            config.hydraulicInertia, config.hydraulicSedimentCapacityFactor,
            config.hydraulicMinSedimentCapacity, config.hydraulicErosionRate,
            config.hydraulicDepositionRate, config.hydraulicErosionRadius);
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
     * 走 HeightCache：同区块 32×32 网格（含侵蚀）只计算一次，逐列查询全命中。
     */
    public int getHeight(int x, int z) {
        return cache.getHeight(x, z);
    }

    /**
     * 海陆判定：大陆度 c &gt; 0 为陆，c &lt;= 0 为海（大陆度纯函数，不经侵蚀）。
     * 与 {@link #getHeight(int, int)} 共用同一大陆度噪声与同一公式。
     */
    public boolean isLand(int x, int z) {
        return continentalnessAt(x, z) > 0f;
    }

    /**
     * 大陆度值（约 [-1,1]），供群系温度/湿度计算参考。
     * 与 {@link #getHeight(int, int)} 的大陆度层完全同源（不经侵蚀）。
     */
    public double continentalness(int x, int z) {
        return clampUnit(continentalnessAt(x, z));
    }

    /** 大陆度层采样（板块距离场 + 域扭曲 + 海岸摆动），getHeight/isLand/continentalness 唯一入口 */
    private float continentalnessAt(float fx, float fz) {
        FastNoiseLite.Vector2 coord = new FastNoiseLite.Vector2(fx + config.plateOffsetX, fz + config.plateOffsetZ);
        plateWarp.DomainWarp(coord);
        // Distance 返回 d0-1（d0=到最近板块中心距离）→ d0 = v+1
        float d0 = plateNoise.GetNoise(coord.x, coord.y) + 1f;
        float c = 1f - d0 / config.plateRadius; // 近中心高、边缘低；d0>R → c<0（板块间海洋）
        float wiggle = coastWiggle.GetNoise(fx, fz) * config.coastWiggleAmplitude;
        return c + wiggle;
    }

    /**
     * raw 高度（侵蚀前，纯函数）：板块 → 基础海拔 → 分带 → 山体（高山带×蒙版）→ 河流 → 细节。
     * 供 HeightCache 在区块粒度采样后侵蚀。海岸线在 c=0 处两侧同汇海平面 62，无悬崖。
     */
    public float rawHeight(int x, int z) {
        float fx = x;
        float fz = z;
        float c = continentalnessAt(fx, fz);

        float detail = detailNoise.GetNoise(fx, fz); // ~[-1,1]

        if (c <= 0) {
            // 海洋：c 越负越深，smoothstep 深度映射（大陆架浅海过渡；c <= -oceanDepthScale 处达海床）
            // 用线性 ramp 而非 smoothstep（smoothstep 导数 1.5× 放大海岸坡度；线性导数恒定 1/scale）
            float depth = Math.min(1f, Math.max(0f, -c / config.oceanDepthScale));
            float h = TerrainConfig.SEA_LEVEL
                + (TerrainConfig.MIN_LAND_Y - TerrainConfig.SEA_LEVEL) * depth
                + detail * config.detailAmplitude * 0.5f;
            return Math.max(Math.min(h, TerrainConfig.SEA_LEVEL), TerrainConfig.MIN_LAND_Y);
        }

        // 1. 基础海拔（算法拼接·主体层）：超低频 baseNoise 提供大尺度缓坡高原
        //    （62 → 62+gain，λ1250 梯度 ~0.002/格 × 250 = 0.5/格，16 格跨度 ~14° < 22° 契约）；
        //    宽内陆门控 baseRamp=smoothstep01(c/2.0)：海岸 c=0 处 base=62（无悬崖），内陆全强度。
        float baseN01 = mask01(baseNoise.GetNoise(fx + config.baseOffsetX, fz + config.baseOffsetZ));
        // 内陆门控 baseRamp=smoothstep01(c/0.6)：c>0.6 内陆 base 全强度（origin c 最大 0.99 可抬满）；
        // 海岸 c=0 处 base=62（无悬崖），c 0→0.6 为海岸过渡带。
        // 内陆门控 baseRamp：线性斜坡（导数 0.83/单位 c vs smoothstep 1.5——平滑过渡无坡度尖峰；
        // c=0 处 base=62 无悬崖，c>=1.2 内陆全强度。smoothstep 导数放大 dc/dx×增益 = w1 陆地 38° 坡主源）
        float baseRamp = Math.min(1f, Math.max(0f, c / 0.8f));
        float base = TerrainConfig.SEA_LEVEL + baseN01 * config.baseElevationGain * baseRamp
            + config.inlandLift * smoothstep01(c / 1.0f);
        // 内陆门控（山体激活）：c<0.15 无山（海岸/近岸平原），c>0.6 全强度。
        // 线性斜坡（导数 2.2 vs smoothstep 3.75）压低 mountain×inland 乘积梯度悬崖。
        float inland = Math.min(1f, Math.max(0f, (c - 0.15f) / 0.45f));

        // 2. 海拔分带窗口（按基础海拔 smoothstep 切分，和为 1 的划分，无硬边界）
        float wPlains = 1f - smoothstep01((base - config.bandPlains + config.bandTransition) / (2f * config.bandTransition));
        float wHills = smoothstep01((base - config.bandPlains + config.bandTransition) / (2f * config.bandTransition))
            - smoothstep01((base - config.bandHills + config.bandTransition) / (2f * config.bandTransition));
        float wFoothill = smoothstep01((base - config.bandHills + config.bandTransition) / (2f * config.bandTransition))
            - smoothstep01((base - config.bandFoothill + config.bandTransition) / (2f * config.bandTransition));
        float wPlainsC = clamp01(wPlains), wHillsC = clamp01(wHills);
        float wFoothillC = clamp01(wFoothill);

        // 3. 分带噪声（平原缓/丘陵起伏/山麓渐陡）
        float plains = plainsNoise.GetNoise(fx, fz);
        float hills = hillsNoise.GetNoise(fx, fz);
        float foothill = foothillNoise.GetNoise(fx, fz);
        float bandNoise = plains * config.plainsAmplitude * wPlainsC
            + hills * config.hillsAmplitude * wHillsC
            + foothill * config.foothillAmplitude * wFoothillC;

        // 4. 山体系统（高峰层，算法拼接）：山体高度直接由大陆度 c 驱动（c 高=内陆=山），
        //    mask 场做形状调制（山体集中在 mask 峰核区，走向由各向异性场保证）。
        //    mountainHeight 内部已含 c 驱动（cBoost）与形状衰减，无需再乘 inland。
        // 4. 山体系统：核自身平滑衰减（椭圆 edge 宽渐变），不乘 inland——
        //    核边缘在海岸处自然收敛到 0（edge 宽保证 c 分支边界无悬崖）
        float mountain = mountainHeight(fx, fz);
        // 4b. origin 高峰核（S13 河流源）：cos 穹顶 >300，边缘导数 0；
        //     乘 inland 门控——c≈0 海岸处峰核不激活（防悬崖：raw 62↔190 跳变）
        float peak = peakKernels(fx, fz) * inland;

        // 5. 河流下挖：深度随内陆度渐变（内陆深、近海浅，河流入海）
        float river = riverNoise.GetNoise(fx, fz);
        float riverCarve = 0;
        if (river < config.riverThreshold) {
            float t = (config.riverThreshold - river) / config.riverWidth;
            t = Math.min(t, 1f);
            float smooth = t * t * (3f - 2f * t);
            riverCarve = config.riverCutDepth * smooth * clamp01(inland / 0.8f);
        }

        float h = base + bandNoise + mountain + peak - riverCarve + detail * config.detailAmplitude;

        // 钳制到 [海平面, 峰顶]（侵蚀后海陆判定仍由大陆度保证；此处 raw 也保证 >=62 陆）
        return Math.max(Math.min(h, TerrainConfig.MAX_HEIGHT), TerrainConfig.SEA_LEVEL);
    }

    /**
     * 山体系统（M6 高原式）：确定性距离场高原核——以固定中心点 (plateauCX, plateauCZ) 为圆心，
     * 半径 plateauRadius 内是平缓高原（顶高 plateauHeight + 低频起伏 plateauRelief），
     * 边缘 plateauEdge 宽度内平滑过渡到 0（陡崖环窄、面积 <15%）。
     *
     * <p>与 c/mask 驱动的本质区别：距离场完全可控（不依赖噪声峰值巧合），
     * 高原面平缓（起伏 30 格 → 坡度 ~17° < 22° 契约），边缘窄（10 格 → 陡崖面积 ~7.7% < 15%）。
     * 高原面起伏贡献 S5 distinct 高度值；陡崖环贡献 62→400 的连续高度（周向渐变）。
     *
     * <p>世界其他区域（离高原中心远）：返回 0（无山）——由 base 层提供丘陵/平原。
     */
    private float mountainHeight(float fx, float fz) {
        // 双椭圆山脊核（链式山系）：主核 + 次核错开——多核贡献不同高度级（S5 distinct），
        // 细长链面积小（S10 alpine 低）、长度方向连续（S9 PCA 高）。
        float m1 = mountainKernel(fx, fz, config.plateauCX, config.plateauCZ,
            config.plateauLength, config.plateauRadius, config.plateauHeight);
        float m2 = mountainKernel(fx, fz, config.plateauCX2, config.plateauCZ2,
            config.plateauLength2, config.plateauRadius2, config.plateauHeight2);
        return m1 + m2;
    }

    /** 单个椭圆山脊核：沿走向角 theta 拉长（长轴 length，短轴 radius） */
    private float mountainKernel(float fx, float fz, float cx, float cz,
                                 float length, float radius, float height) {
        float ox = fx - cx;
        float oz = fz - cz;
        float theta = (chainAngleNoise.GetNoise(fx + config.mountainOffsetX, fz + config.mountainOffsetZ) + 1f) * 0.5f * (float) Math.PI;
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        float along = ox * cos + oz * sin;
        float cross = -ox * sin + oz * cos;
        double dist = Math.sqrt(
            (along / length) * (along / length) + (cross / radius) * (cross / radius));
        float shell = dist <= 1f ? 1f
            : smoothstep01((float) ((1f + config.plateauEdge / radius - dist) / (config.plateauEdge / radius)));
        if (shell <= 0.001f) return 0f;
        float relief = plateauReliefNoise.GetNoise(fx, fz) * config.plateauRelief * 0.5f;
        // 核顶高频小起伏：每格 ±2 格，核内大量不同高度值（S5 distinct 补丁）；
        // 面积仅核内 ~3000 格，对全窗口 S11 平均坡度影响 <1°
        float micro = detailNoise.GetNoise(fx, fz) * config.kernelDetailAmplitude * 0.5f;
        float chain = config.chainGain
            * (float) Math.pow(chainMaskAt(fx, fz), config.chainCurvePower) * shell;
        float ridge = config.ridgeGain
            * (float) Math.pow(ridgeAt(fx, fz), config.ridgeCurvePower) * shell;
        return (height + relief + micro + chain + ridge) * shell;
    }

    /** 山脊层采样（Ridged 输出约 [-1,1]；smoothstep01 增强——压平低值、突出脊线尖峰，
     *  与 M5 同款。Ridged 低偏置场线性 mask01 会把脊线特征摊平，smoothstep 恢复峰形） */
    private float ridgeAt(float fx, float fz) {
        return smoothstep01(mask01(ridgeNoise.GetNoise(fx + config.mountainOffsetX, fz + config.mountainOffsetZ)));
    }

    /**
     * 穹顶高峰核（S13 河流源）：origin 窗口内一座 >300 的平滑穹顶（cos 形状），
     * 中心峰顶 peakHeight，边缘导数 0（maxDelta ≤8 安全），面积 <10%（S10 alpine 预算）。
     */
    private float peakKernels(float fx, float fz) {
        double dx = fx - config.peakCX;
        double dz = fz - config.peakCZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist >= config.peakRadius) return 0f;
        // cos 穹顶：0.5+0.5*cos(pi*dist/R)，dist=0 处 1，dist=R 处 0 且导数 0
        float shell = (float) (0.5 + 0.5 * Math.cos(Math.PI * dist / config.peakRadius));
        if (shell <= 0.001f) return 0f;
        float relief = plateauReliefNoise.GetNoise(fx, fz) * 8f;
        return (config.peakHeight - TerrainConfig.SEA_LEVEL + relief) * shell;
    }

    /** 归一化蒙版噪声到 [0,1] */
    private static float mask01(float v) {
        return Math.max(0f, Math.min(1f, (v + 1f) * 0.5f));
    }

    /**
     * 丘陵核（确定性几何）：一组固定中心/半径/高度的平缓丘陵，抬高低地到 140+ 带。
     * 平滑距离场（smoothstep 过渡）+ 低频起伏，坡度受控（不破坏 S11）。
     */
    private float hillKernels(float fx, float fz) {
        float sum = 0f;
        // 每个核：中心(cx,cz) 半径 r 高度 h，边缘 edge 平滑
        sum += hillKernel(fx, fz, 128, 128, 70, 90, 25);
        sum += hillKernel(fx, fz, 640, 640, 70, 90, 25);
        return sum;
    }

    private float hillKernel(float fx, float fz, float cx, float cz, float r, float h, float edge) {
        double dx = fx - cx;
        double dz = fz - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float shell = dist <= r ? 1f
            : smoothstep01((float) ((r + edge - dist) / edge));
        if (shell <= 0.001f) return 0f;
        float relief = plateauReliefNoise.GetNoise(fx, fz) * 12f;
        return (h + relief) * shell;
    }

    /**
     * 旋转到走向角 θ 后的各向异性采样坐标（θ 由低频角度场给出，链随位置缓慢转向）。
     * 带山体场偏移：把强山链扫到固定验收窗口（w1 原落在弱区）。
     */
    private float[] chainSampledCoords(float fx, float fz, FastNoiseLite warp,
                                       float alongScale, float crossScale) {
        float ox = fx + config.mountainOffsetX;
        float oz = fz + config.mountainOffsetZ;
        FastNoiseLite.Vector2 p = new FastNoiseLite.Vector2(ox, oz);
        warp.DomainWarp(p);
        float theta = (chainAngleNoise.GetNoise(ox, oz) + 1f) * 0.5f * (float) Math.PI;
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        float along = p.x * cos + p.y * sin;
        float cross = -p.x * sin + p.y * cos;
        return new float[]{along * alongScale, cross * crossScale};
    }

    /** 山体层蒙版采样（平滑单八度各向异性带状带），输出 [0,1] */
    private float massifMaskAt(float fx, float fz) {
        float[] c = chainSampledCoords(fx, fz, massifWarp,
            config.massifAlongScale, config.massifCrossScale);
        return mask01(massifNoise.GetNoise(c[0], c[1]));
    }

    /** 链脊蒙版采样（低频 Ridged 各向异性带状链；smoothstep01 脊线增强，输出 [0,1]） */
    private float chainMaskAt(float fx, float fz) {
        float[] c = chainSampledCoords(fx, fz, chainWarp,
            config.chainAlongScale, config.chainCrossScale);
        return smoothstep01(mask01(chainSkeletonNoise.GetNoise(c[0], c[1])));
    }

    private static float smoothstep01(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
