package com.gtsn.terrain.noise;

/**
 * 地形参数配置（2D 大陆度驱动大陆地形核心）。
 *
 * <p>所有字段公开 final，不可变；构造时仅需世界种子。
 * 参数先按调参报告参考值写死，若 seam 契约不满足则在此调参。
 *
 * <p>重构说明：M2 版本使用 s=x+z 一维对角线大陆架剖面（shelfKink/shelfLandSlope/
 * shelfOceanRise/shelfWiggleAmplitude），全域 256×256 验证暴露 96.4% 陆地、
 * 91.6% 雪线、强对角条纹、种子差异仅 16% 等缺陷。本版彻底移除对角线剖面，
 * 改为真正的 2D 大陆度驱动：海陆由低频 2D 噪声 c 的正负决定（c>0 陆，c<=0 海），
 * 大陆度同时决定海床深度与陆地基础海拔。
 */
public class TerrainConfig {

    // ---------------- 世界常量 ----------------

    /** 海平面（陆地判定线：高度 > SEA_LEVEL 视为陆地） */
    public static final int SEA_LEVEL = 62;

    /** 世界最低 Y（1.20 深板岩层） */
    public static final int MIN_Y = -64;

    /** 地形最高峰 */
    public static final int MAX_HEIGHT = 580;

    /** 海床底部基岩层厚度 */
    public static final int BEDROCK_THICKNESS = 5;

    /** 地形可生成的最低方块 Y（基岩层顶，-64 + 5 = -59） */
    public static final int MIN_LAND_Y = MIN_Y + BEDROCK_THICKNESS;

    // ---------------- 种子 ----------------

    /** 世界种子 */
    public final long seed;

    // ---------------- 大陆度层（宏观大陆/海洋，2D 驱动） ----------------

    /** 大陆度噪声频率（低频：板块尺度 ~数百格，1/f ≈ 单个板块宽度） */
    public final float continentFrequency = 0.002f;

    /** 大陆度分形八度数（2 八度：低八度给板块，避免高八度制造陡峭海岸梯度） */
    public final int continentOctaves = 2;

    /** 大陆度分形增益（<0.5 削弱高八度贡献，控制海岸梯度 Δc，满足连续性 <=8） */
    public final float continentFractalGain = 0.2f;

    /** 大陆度域扭曲振幅（方块）：让海岸线自然弯曲 */
    public final float continentWarpAmplitude = 40f;

    /** 大陆度域扭曲频率 */
    public final float continentWarpFrequency = 0.0008f;

    /** 陆地基础海拔增益（方块）：c=1 时海拔 = SEA_LEVEL + 该值 */
    public final float continentElevationGain = 150f;

    /** 内陆调制坡宽：c ∈ (0, inlandRamp] 内内陆因子从 0 平滑升至 1（海岸无山）。
     *  坡宽越大内陆因子导数越小——海岸连续约束（相邻差 <=8）要求 d(内陆)/dc × 山高 × Δc <= 8 */
    public final float inlandRamp = 1.2f;

    /** 海床深度坡宽：|c| ∈ (0, oceanDepthScale] 内海床从海平面平滑加深至 -59。
     *  海床导数 = 121×1.5/scale 必须 <= 8/Δc_max，实测 Δc_max≈0.012 → scale >= 0.375 */
    public final float oceanDepthScale = 0.4f;

    // ---------------- 山脊层（Ridged 山脉，仅内陆） ----------------

    /** 山脊噪声频率 */
    public final float ridgeFrequency = 0.002f;

    /** 山脊分形八度数 */
    public final int ridgeOctaves = 4;

    /** 山脊度 → 山体增益（方块）：smoothstep(ridge01) * gain * 内陆因子 决定山高。
     *  用 smoothstep 而非 pow 锐化：smoothstep 在 ridge01=0/1 处导数为 0，
     *  杀死 Ridged 噪声 V 型山脊线的陡峭尖点，保证相邻差 <=8。 */
    public final float ridgeGain = 450f;

    // ---------------- 细节层（中小起伏，高频保证窗口内去相关） ----------------

    /** 细节噪声频率 */
    public final float detailFrequency = 0.035f;

    /** 细节分形八度数 */
    public final int detailOctaves = 3;

    /** 细节振幅（方块） */
    public final float detailAmplitude = 6f;

    // ---------------- 河网侵蚀层 ----------------

    /** 河网噪声频率（低频，阈值切出河道） */
    public final float riverFrequency = 0.003f;

    /** 河网分形八度数 */
    public final int riverOctaves = 2;

    /** 河流判定阈值：河网噪声低于该值视为河道 */
    public final float riverThreshold = 0.0f;

    /** 河道过渡带宽（阈值两侧平滑过渡宽度） */
    public final float riverWidth = 0.35f;

    /** 河道最大下挖深度（方块） */
    public final float riverCutDepth = 4f;

    public TerrainConfig(long seed) {
        this.seed = seed;
    }
}
