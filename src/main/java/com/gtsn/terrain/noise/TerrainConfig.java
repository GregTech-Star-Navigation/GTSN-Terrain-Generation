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

    /** 陆地基础海拔增益（方块）：c=1 时海拔 = SEA_LEVEL + 该值。
     *  M5 从 150 提到 205：给出生点窗口（c_max≈0.59）的海拔基底 +32，为 480+ 峰顶提供廉价高度
     *  （基础层坡度 = Δc×增益 ≈ 0.0083×205 = 1.7/格，仍在连续预算内）。 */
    public final float continentElevationGain = 228f;

    /** 内陆门控坡宽：c ∈ (0, inlandRamp] 内山体门控线性升至 1（海岸无山）。
     *  线性斜坡（非 smoothstep）：门控坡度恒为 1/ramp，连续预算充足。
     *  0.65：origin c_max≈0.59 时门控 0.91（山体近全强），c<0.3 海岸被压到 <0.46。 */
    public final float inlandRamp = 0.65f;

    /** 海床深度坡宽：|c| ∈ (0, oceanDepthScale] 内海床从海平面平滑加深至 -59。
     *  海床导数 = 121×1.5/scale 必须 <= 8/Δc_max，实测 Δc_max≈0.012 → scale >= 0.375 */
    public final float oceanDepthScale = 0.4f;

    // ---------------- 山链系统（M5：三尺度分离，各层独立坡度预算，保证相邻差<=8） ----------------

    /** 山链走向角度场频率：低频（~1250 格波长），给出每处山脉走向角 θ∈[0,π)，链缓慢转向。
     *  山体层与链脊层共用该角度场，保证链脊骑在山体带上。 */
    public final float chainAngleFrequency = 0.0008f;

    // ---- 山体层（平滑低频各向异性，大增益提供大尺度高度，坡度预算效率最高） ----

    /** 山体噪声频率（低频：波长 ~1100 格；单八度 → 每格蒙版 Δ ~0.007，坡度预算充足） */
    public final float massifFrequency = 0.0009f;
    public final int massifOctaves = 1;

    /** 山体各向异性：沿走向压缩频率（链更长）、垂直走向放大频率（链更窄）。
     *  along 0.35 / cross 1.9 → 链长宽比 ~5.4×，梯度方向性（S9 契约）强 */
    public final float massifAlongScale = 0.35f;
    public final float massifCrossScale = 1.9f;

    /** 山体域扭曲（链自然弯曲；振幅小 → 链更直，走向性更强） */
    public final float massifWarpAmplitude = 100f;
    public final float massifWarpFrequency = 0.00035f;

    /** 山体增益（方块）：大尺度山体质量（单八度低频 → 高度/坡度比最高） */
    public final float massifGain = 325f;

    /** 山体曲线指数（>1：峰顶尖、山脚缓） */
    public final float massifCurvePower = 1.28f;

    // ---- 链脊层（低频 Ridged 各向异性采样，V 型尖脊，中等增益） ----

    /** 链脊噪声频率 */
    public final float chainFrequency = 0.0013f;
    public final int chainOctaves = 3;

    /** 链脊各向异性（crossScale 1.4：V 尖点横向坡度放大 1.4 倍；过大会让点状峰梯度变径向各向同性） */
    public final float chainAlongScale = 0.45f;
    public final float chainCrossScale = 1.4f;

    /** 链脊域扭曲（振幅小 → 链更直） */
    public final float chainWarpAmplitude = 60f;
    public final float chainWarpFrequency = 0.0004f;

    /** 链脊增益（方块）：小增益（V 尖点 Δ~0.045/格，55×0.045≈2.5/格预算） */
    public final float chainGain = 65f;

    /** 链脊曲线指数（>1：V 尖点在 mask=1 处导数非零，保留尖峰形态） */
    public final float chainCurvePower = 1.15f;

    // ---- 尖峰细节层（中频 Ridged，小增益控制坡度预算） ----

    /** 尖峰噪声频率（比链脊高一级，叠加出山峰/垭口） */
    public final float ridgeFrequency = 0.0022f;

    /** 尖峰分形八度数 */
    public final int ridgeOctaves = 4;

    /** 尖峰增益（方块）：小增益（Δ~0.0125/格 → 30×0.0125≈0.4/格预算；尖峰径向梯度会稀释走向性，须小） */
    public final float ridgeGain = 35f;

    /** 尖峰曲线指数 */
    public final float ridgeCurvePower = 1.1f;

    // ---------------- 细节层（中小起伏，高频保证窗口内去相关） ----------------

    /** 细节噪声频率 */
    public final float detailFrequency = 0.035f;

    /** 细节分形八度数 */
    public final int detailOctaves = 3;

    /** 细节振幅（方块） */
    public final float detailAmplitude = 3f;

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
