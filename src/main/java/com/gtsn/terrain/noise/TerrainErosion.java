package com.gtsn.terrain.noise;

import java.util.Random;

/**
 * M6 侵蚀内核（纯函数：输入高度网格 → 输出侵蚀后网格，可独立 JUnit）。
 *
 * <p>两类侵蚀：
 * <ul>
 *   <li>热侵蚀 {@link #thermalErode}：坡度松弛——每点若与最低邻域坡度差超过 talusAngle，
 *       把超出部分的一半搬到低处（talus 松弛）。纯内存迭代，快；产生山脚缓坡/冲积扇。</li>
 *   <li>水滴侵蚀 {@link #hydraulicErode}：经典 Hydraulic Erosion（Sebastian Lague）——
 *       每滴沿最陡下降方向走、携带沉积物、遇缓坡沉积——产生 V 形谷与冲积扇。</li>
 * </ul>
 *
 * <p>输入为 size×size 行优先 float[] 网格，输出等长新数组（不修改输入）。
 * 确定性：同输入同参数必然同输出（内部 RNG 以传入 seed 驱动），供 HeightCache 跨块一致依赖。
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
        int erosionRadius,  // 侵蚀半径（格子）
        float minErosionSlope // 最小侵蚀坡度（格/步）：低于该坡度的缓坡不侵蚀（防平地被水流汇聚挖坑）
    ) {}

    private TerrainErosion() {}

    /**
     * 热侵蚀（talus 松弛）：迭代，每点若与最低 4-邻域坡度差 > talusAngle，
     * 把超出部分的一半搬到低处。质量守恒（只搬运不增减）。
     *
     * @param grid       size×size 行优先高度网格
     * @param size       网格边长
     * @param talusAngle 休止角（最大允许坡度，格/格）
     * @param iterations 松弛迭代次数
     * @return 侵蚀后新网格
     */
    public static float[] thermalErode(float[] grid, int size, float talusAngle, int iterations) {
        return thermalErode(grid, size, talusAngle, iterations, 0);
    }

    /**
     * 热侵蚀（talus 松弛）：迭代，每点若与最低 4-邻域坡度差 > talusAngle，
     * 把超出部分的一半搬到低处。质量守恒（只搬运不增减）。
     *
     * @param grid       size×size 行优先高度网格
     * @param size       网格边长
     * @param talusAngle 休止角（最大允许坡度，格/格）
     * @param iterations 松弛迭代次数
     * @param border     冻结边界宽度：border 内的格永不修改（只作邻居输入），
     *                   保证跨区块一致（边界格值 = raw，邻居区块同样只读不写）
     * @return 侵蚀后新网格
     */
    public static float[] thermalErode(float[] grid, int size, float talusAngle, int iterations, int border) {
        float[] src = grid.clone();
        float[] dst = new float[grid.length];
        for (int it = 0; it < iterations; it++) {
            // dst 先整体拷贝 src，再对每个点做「自身减、低邻加」的搬运（累加语义，避免覆盖）
            System.arraycopy(src, 0, dst, 0, grid.length);
            for (int y = border; y < size - border; y++) {
                for (int x = border; x < size - border; x++) {
                    int idx = y * size + x;
                    float h = src[idx];
                    // 找最低 4-邻域
                    int best = -1;
                    float bestH = h;
                    if (x > 0) {
                        float v = src[idx - 1];
                        if (v < bestH) { bestH = v; best = idx - 1; }
                    }
                    if (x + 1 < size) {
                        float v = src[idx + 1];
                        if (v < bestH) { bestH = v; best = idx + 1; }
                    }
                    if (y > 0) {
                        float v = src[idx - size];
                        if (v < bestH) { bestH = v; best = idx - size; }
                    }
                    if (y + 1 < size) {
                        float v = src[idx + size];
                        if (v < bestH) { bestH = v; best = idx + size; }
                    }
                    if (best < 0) continue;
                    float diff = h - bestH;
                    if (diff > talusAngle) {
                        float move = (diff - talusAngle) * 0.5f;
                        dst[idx] -= move;
                        dst[best] += move;
                    }
                }
            }
            float[] tmp = src; src = dst; dst = tmp;
        }
        return src;
    }

    /**
     * 水滴侵蚀：drops 个水滴从随机点出发，沿最陡下降方向走 maxSteps 步，
     * 携带沉积物（容量 ∝ 沿坡下降量×速度），超容沉积、欠容侵蚀。
     *
     * <p>实现要点（Sebastian Lague Hydraulic-Erosion 参考）：
     * <ul>
     *   <li>方向 = 最陡下降方向，与历史方向按 inertia 混合后归一化，每步前进 1 格；</li>
     *   <li>沉积容量 capacity = max(minCap, -Δh × speed × capFactor)，Δh 为本步沿坡下降量，
     *       速度随下坡累加（水滴加速）→ 陡坡上容量远大于携带量 → 持续侵蚀刻谷；</li>
     *   <li>侵蚀量钳制 ≤ -Δh（不在身后挖坑），集中在水滴当前格（窄 V 谷切割）；</li>
     *   <li>沉积按双线性权重洒在当前格 4 节点（填小坑），上坡时填到当前高度；</li>
     *   <li>侵蚀下限钳制 {@link TerrainConfig#MIN_LAND_Y}，防塌陷到负无穷。</li>
     * </ul>
     *
     * @param grid   size×size 行优先高度网格
     * @param size   网格边长
     * @param seed   RNG 种子（确定性）
     * @param params 水滴参数
     * @return 侵蚀后新网格
     */
    public static float[] hydraulicErode(float[] grid, int size, long seed, HydraulicParams params) {
        return hydraulicErode(grid, size, seed, params, 0);
    }

    /**
     * 水滴侵蚀：drops 个水滴从随机点出发，沿最陡下降方向走 maxSteps 步，
     * 携带沉积物（容量 ∝ 沿坡下降量×速度），超容沉积、欠容侵蚀。
     *
     * @param border 冻结边界宽度：border 内的格永不修改（水滴在该区域只读不写），
     *               保证跨区块一致（边界格值 = raw）。
     * @see #hydraulicErode(float[], int, long, HydraulicParams)
     */
    public static float[] hydraulicErode(float[] grid, int size, long seed, HydraulicParams params, int border) {
        float[] h = grid.clone();
        Random rng = new Random(seed);
        final float gravity = 4f; // 速度累加系数（非参数，Lague 默认 4）

        for (int d = 0; d < params.drops(); d++) {
            float px = rng.nextFloat() * (size - 1);
            float pz = rng.nextFloat() * (size - 1);
            float vx = 0, vz = 0;
            float speed = 1f;
            float sediment = 0f;
            for (int s = 0; s < params.maxSteps(); s++) {
                int xi = (int) px, zi = (int) pz;
                // 冻结边界（border 内格不修改）+ 梯度邻域保护（xi-1/zi-1 需 ≥0 → xi,zi ≥ max(1,border)）
                int lo = Math.max(1, border);
                if (xi < lo || xi >= size - lo || zi < lo || zi >= size - lo) break;
                // 有限差分梯度（指向上坡方向）
                float gx = h[zi * size + xi + 1] - h[zi * size + xi - 1];
                float gz = h[(zi + 1) * size + xi] - h[(zi - 1) * size + xi];
                float gmag = (float) Math.sqrt(gx * gx + gz * gz);
                if (gmag < 1e-6f) break; // 平底：停
                // 下坡方向（惯性混合 + 归一化）
                float dx = -gx / gmag, dz = -gz / gmag;
                vx = vx * params.inertia() + dx * (1f - params.inertia());
                vz = vz * params.inertia() + dz * (1f - params.inertia());
                float vlen = (float) Math.sqrt(vx * vx + vz * vz);
                if (vlen < 1e-6f) break;
                vx /= vlen; vz /= vlen;
                float nx = px + vx, nz = pz + vz;
                if (nx < 1 || nx >= size - 1 || nz < 1 || nz >= size - 1) break;
                // 新旧位置高度差（双线性采样）：Δh = 新 - 旧（下坡为负）
                float oldH = sampleBilinear(h, size, px, pz);
                float newH = sampleBilinear(h, size, nx, nz);
                float deltaH = newH - oldH;
                // 沉积容量 ∝ 沿坡下降量 × 速度（下坡 -Δh>0 → 容量大 → 侵蚀）
                float capacity = Math.max(params.minSedimentCapacity(),
                    -deltaH * speed * params.sedimentCapacityFactor());
                if (sediment > capacity || deltaH > 0) {
                    // 沉积：上坡填到当前高度；超容按沉积率洒出
                    float deposit = (deltaH > 0) ? Math.min(deltaH, sediment)
                        : (sediment - capacity) * params.depositionRate();
                    deposit = Math.max(0f, Math.min(deposit, sediment));
                    sediment -= deposit;
                    addDeposit(h, size, px, pz, deposit);
                } else {
                    // 侵蚀：不低于最小坡度阈值且非局部盆地（防止汇聚点被挖穿成悬崖），
                    // 且不超过容量缺口×速率，且不超过 -Δh（不在身后挖坑）
                    if (-deltaH >= params.minErosionSlope() && !isBasin(h, size, xi, zi)) {
                        float erode = Math.min((capacity - sediment) * params.erosionRate(), -deltaH);
                        erode = Math.max(0f, erode);
                        sediment += erode;
                        erodeAt(h, size, xi, zi, erode);
                    }
                }
                px = nx; pz = nz;
                // 速度：下坡加速（-Δh>0），上坡减速；钳制非负防 NaN
                speed = (float) Math.sqrt(Math.max(0f, speed * speed - deltaH * gravity));
            }
        }
        return h;
    }

    /** 双线性采样网格高度（px,pz 可为小数；x,z 钳制到 [1, size-2] 防越界） */
    private static float sampleBilinear(float[] grid, int size, float px, float pz) {
        int x = Math.max(1, Math.min(size - 2, (int) px));
        int z = Math.max(1, Math.min(size - 2, (int) pz));
        float fx = px - x, fz = pz - z;
        int idx = z * size + x;
        float h00 = grid[idx];
        float h10 = grid[idx + 1];
        float h01 = grid[idx + size];
        float h11 = grid[idx + size + 1];
        return h00 * (1f - fx) * (1f - fz) + h10 * fx * (1f - fz)
             + h01 * (1f - fx) * fz + h11 * fx * fz;
    }

    /** 在水滴当前格 4 节点按双线性权重沉积（填小坑；x,z 钳制到 [1, size-2] 防越界） */
    private static void addDeposit(float[] grid, int size, float px, float pz, float amount) {
        int x = Math.max(1, Math.min(size - 2, (int) px));
        int z = Math.max(1, Math.min(size - 2, (int) pz));
        float fx = px - x, fz = pz - z;
        int idx = z * size + x;
        grid[idx] += amount * (1f - fx) * (1f - fz);
        grid[idx + 1] += amount * fx * (1f - fz);
        grid[idx + size] += amount * (1f - fx) * fz;
        grid[idx + size + 1] += amount * fx * fz;
    }

    /** 单格侵蚀（集中在水滴当前位置，窄 V 谷切割），不低于世界海床下限 */
    private static void erodeAt(float[] grid, int size, int x, int z, float amount) {
        int idx = z * size + x;
        grid[idx] = Math.max(grid[idx] - amount, TerrainConfig.MIN_LAND_Y);
    }

    /** 局部盆地检测：当前格高度 ≤ 所有 4 邻域（含微小容差），水流汇聚点不继续挖穿 */
    private static boolean isBasin(float[] grid, int size, int x, int z) {
        int idx = z * size + x;
        float h = grid[idx];
        if (x > 0 && grid[idx - 1] < h) return false;
        if (x + 1 < size && grid[idx + 1] < h) return false;
        if (z > 0 && grid[idx - size] < h) return false;
        if (z + 1 < size && grid[idx + size] < h) return false;
        return true;
    }
}
