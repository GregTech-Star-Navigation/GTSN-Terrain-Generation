package com.gtsn.terrain.noise;

/**
 * M6 侵蚀内核（纯函数：输入高度网格 → 输出侵蚀后网格，可独立 JUnit）。
 *
 * <p>两类侵蚀：
 * <ul>
 *   <li>热侵蚀 {@link #thermalErode}：坡度松弛——每点若与邻域坡度超过 talusAngle 阈值，
 *       把物质向低处搬运（坡度松弛）；纯内存迭代，快。</li>
 *   <li>水滴侵蚀 {@link #hydraulicErode}：经典 Hydraulic Erosion（每滴水沿下降方向走、
 *       携带沉积物、遇缓坡沉积）——产生 V 形谷与冲积扇。</li>
 * </ul>
 *
 * <p>输入为 size×size 行优先 float[] 网格，输出等长新数组（不修改输入）。
 * 确定性：同输入同参数必然同输出（内部 RNG 以传入 seed 驱动）。
 *
 * <p>RED 占位：M6-GREEN 实现前抛出 {@link UnsupportedOperationException}，
 * 契约测试 {@link TerrainErosionTest} 先红后绿。
 */
public final class TerrainErosion {

    /** 水滴侵蚀参数（与 TerraForged/Lague 水滴模拟一致的物理参数组） */
    public record HydraulicParams(
        int drops,          // 水滴数量
        int maxSteps,       // 每滴最大步数
        float inertia,      // 惯性（0-1，越大路径越直）
        float sedimentCapacityFactor,
        float minSedimentCapacity,
        float erosionRate,  // 侵蚀率（每步从地面带走沉积物上限）
        float depositionRate, // 沉积率
        int erosionRadius   // 侵蚀半径（格子）
    ) {}

    private TerrainErosion() {}

    /**
     * 热侵蚀（坡度松弛）：迭代迭代，每点若与最低邻域坡度差 > talusAngle，把超出部分的一半
     * 搬到低处。talusAngle 为「休止角」，单位=每格高度差（如 4 = 每格最多差 4 格高）。
     *
     * @param grid       size×size 行优先高度网格
     * @param size       网格边长
     * @param talusAngle 休止角（最大允许坡度，格/格）
     * @param iterations 松弛迭代次数
     * @return 侵蚀后新网格
     */
    public static float[] thermalErode(float[] grid, int size, float talusAngle, int iterations) {
        throw new UnsupportedOperationException("M6-GREEN: thermalErode 未实现");
    }

    /**
     * 水滴侵蚀：drops 个水滴从随机点出发，沿最陡下降方向走 maxSteps 步，
     * 携带沉积物（容量 ∝ 坡度×速度），超容沉积、欠容侵蚀（半径 erosionRadius）。
     *
     * @param grid   size×size 行优先高度网格
     * @param size   网格边长
     * @param seed   RNG 种子（确定性）
     * @param params 水滴参数
     * @return 侵蚀后新网格
     */
    public static float[] hydraulicErode(float[] grid, int size, long seed, HydraulicParams params) {
        throw new UnsupportedOperationException("M6-GREEN: hydraulicErode 未实现");
    }
}
