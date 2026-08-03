package com.gtsn.terrain.noise;

/**
 * 3D 洞穴噪声（M3-C 核心，纯 Java，零 Minecraft 依赖）。
 *
 * <p>双层噪声（cheese-cave 风格：大洞 + 小洞），独立种子偏移
 * （seed+201 ~ seed+202），与 HeightMapBuilder 的 seed+1 ~ seed+6、
 * BiomeLayoutNoise 的 seed+101 ~ seed+103 完全隔离：
 * <ul>
 *   <li>高频层：OpenSimplex2 3D FBm（默认频率 0.008），输出高于
 *       {@link #CAVE_THRESHOLD} 判定为小洞穴</li>
 *   <li>低频层：OpenSimplex2 3D FBm（频率 = 主频率 × 0.25），输出高于
 *       {@link #CORRIDOR_THRESHOLD} 判定为大型洞穴走廊</li>
 * </ul>
 *
 * <p>{@link #isCave(int, int, int, int)} 防穿帮三重闸门（按序短路）：
 * <ol>
 *   <li><b>海平面掩码</b>：y &gt;= {@link #SEA_LEVEL}（63）恒 false，
 *       保证洞穴绝不挖穿海面/湖面以上的水体</li>
 *   <li><b>地表安全深度</b>：洞穴仅在 y &lt; surfaceHeight - 3 以下激活，
 *       地表下 1-3 格永远完整</li>
 *   <li><b>近地表平滑衰减</b>：深度在 (surfaceHeight-6, surfaceHeight-3]
 *       区间内阈值随平滑步进函数抬高，越接近地表成洞概率越低，
 *       与安全深度闸门共同保证地表 4-6 格内无洞穴口</li>
 * </ol>
 *
 * <p>全部方法为纯函数：同一种子同坐标结果恒定，线程安全
 * （fillFromNoise 异步列填充可安全共享实例）。
 */
public class CaveNoise {

    /** 海平面（与 ChunkGenerator 水面一致）：y &gt;= 该值禁止洞穴 */
    public static final int SEA_LEVEL = 63;

    /** 默认洞穴噪声频率 */
    public static final float DEFAULT_FREQUENCY = 0.008f;

    /** 小洞层判定阈值：OpenSimplex2 输出高于该值成洞 */
    private static final float CAVE_THRESHOLD = 0.15f;

    /** 大走廊层判定阈值（低频层输出更集中于 0，阈值相应抬高） */
    private static final float CORRIDOR_THRESHOLD = 0.28f;

    /** 地表安全深度：洞穴仅在 y &lt; surfaceHeight - 该值 以下激活 */
    private static final int SURFACE_SAFE_DEPTH = 3;

    /** 近地表衰减带宽度（方块）：安全深度之上该宽度内阈值平滑抬高 */
    private static final float SURFACE_FADE_BAND = 3f;

    /** 近地表阈值抬高量：衰减带内阈值最大 += 该值（成洞率骤降） */
    private static final float SURFACE_BOOST = 0.5f;

    /** 高频小洞层（3D） */
    private final FastNoiseLite caveNoise;

    /** 低频大走廊层（3D） */
    private final FastNoiseLite corridorNoise;

    public CaveNoise(long seed) {
        this(seed, DEFAULT_FREQUENCY);
    }

    public CaveNoise(long seed, float frequency) {
        this.caveNoise = noise3d(seed + 201, frequency, 3);
        this.corridorNoise = noise3d(seed + 202, frequency * 0.25f, 2);
    }

    private static FastNoiseLite noise3d(long seed, float frequency, int octaves) {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFrequency(frequency);
        n.SetFractalOctaves(octaves);
        return n;
    }

    /**
     * 判定 (x, y, z) 处是否为洞穴。
     *
     * @param x             世界 X 坐标（方块）
     * @param y             世界 Y 坐标（方块）
     * @param z             世界 Z 坐标（方块）
     * @param surfaceHeight 该列地表高度（来自 HeightMapBuilder.getHeight）
     * @return true 为洞穴（该处填空气）
     */
    public boolean isCave(int x, int y, int z, int surfaceHeight) {
        // 闸门 1：海平面掩码——y >= 海平面恒不生成洞穴（防穿出海面/湖面）
        if (y >= SEA_LEVEL) {
            return false;
        }
        // 闸门 2：地表安全深度——洞穴仅在 y < surfaceHeight - 3 以下激活
        int depthBelow = surfaceHeight - y;
        if (depthBelow <= SURFACE_SAFE_DEPTH) {
            return false;
        }

        // 阈值判定：小洞层 OR 大走廊层
        float n1 = caveNoise.GetNoise(x, y, z);
        float n2 = corridorNoise.GetNoise(x, y, z);
        if (n1 <= CAVE_THRESHOLD && n2 <= CORRIDOR_THRESHOLD) {
            return false;
        }

        // 闸门 3：近地表平滑衰减——深度越接近安全深度阈值越高
        float fade = (depthBelow - SURFACE_SAFE_DEPTH) / SURFACE_FADE_BAND;
        if (fade >= 1f) {
            return true;
        }
        float smooth = fade * fade * (3f - 2f * fade); // smoothstep
        float boost = (1f - smooth) * SURFACE_BOOST;
        return n1 > CAVE_THRESHOLD + boost || n2 > CORRIDOR_THRESHOLD + boost;
    }
}
