package com.gtsn.terrain.noise;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * M6 高度缓存（关键性能设计）：侵蚀依赖邻域，无法逐点纯函数计算，
 * 故按区块(16×16)预计算含边界(8)的 32×32 网格，侵蚀纯内存迭代后缓存内部 16×16。
 *
 * <p>一致性论证（相同输入必然相同输出）：
 * <ul>
 *   <li>raw 高度 {@code rawHeight} 是纯函数（同一种子同坐标恒定），侵蚀是纯函数
 *       （TerrainErosion 同输入同输出）→ 区块网格确定。</li>
 *   <li>缓存并发：ConcurrentHashMap 的 computeIfAbsent 原子填充；同区块并发填充时
 *       两个线程计算相同结果，任一被采用都一致。</li>
 *   <li>跨区块一致性：侵蚀只读本块 32×32 网格内部（含 8 格边界 raw 数据），
 *       边界格点值来自邻居区块的 raw 采样（纯函数），不依赖邻居的侵蚀结果 → 无跨块串扰。</li>
 * </ul>
 *
 * <p>LRU 上限：LinkedHashMap accessOrder 驱逐最久未用，内存有界。
 *
 * <p>RED 占位：M6-GREEN 实现前抛出 {@link UnsupportedOperationException}，
 * 契约测试 {@link HeightCacheTest} 先红后绿。
 */
public final class HeightCache {

    /** 区块边长（方块） */
    static final int CHUNK_SIZE = 16;

    /** 边界宽度（方块）：侵蚀影响半径（热侵蚀迭代数/水滴步数上限） */
    private final int border;

    /** LRU 上限（区块数） */
    private final int maxChunks;

    /** raw 高度纯函数（同种子同坐标恒定） */
    private final HeightMapBuilder.RawHeight rawHeight;

    /** 区块坐标 → 32×32 侵蚀后网格（行优先 float[]，含边界） */
    private final ConcurrentHashMap<Long, float[]> chunks = new ConcurrentHashMap<>();

    /** LRU 访问序：每次查询 touch，驱逐最久未用 */
    private final LinkedHashMap<Long, Boolean> lru = new LinkedHashMap<>(1024, 0.75f, true);

    /** 当前实际在缓存中的区块数 */
    private int chunkCount = 0;

    public HeightCache(HeightMapBuilder.RawHeight rawHeight, int border, int maxChunks) {
        this.rawHeight = rawHeight;
        this.border = border;
        this.maxChunks = maxChunks;
    }

    /**
     * 查询 (x,z) 侵蚀后高度（方块 Y）。
     * 命中缓存返回；未命中则计算所在区块的 32×32 网格并侵蚀，取内部 16×16 的对应格。
     */
    public int getHeight(int x, int z) {
        int cx = Math.floorDiv(x, CHUNK_SIZE);
        int cz = Math.floorDiv(z, CHUNK_SIZE);
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        float[] grid = chunks.get(key);
        if (grid == null) {
            grid = computeChunk(cx, cz);
            evictIfNeeded(key);
            chunks.put(key, grid);
        } else {
            touch(key);
        }
        int lx = x - cx * CHUNK_SIZE + border;
        int lz = z - cz * CHUNK_SIZE + border;
        return Math.round(grid[lz * (CHUNK_SIZE + 2 * border) + lx]);
    }

    /** 当前缓存区块数（测试/调优用） */
    public int chunkCount() {
        synchronized (lru) {
            return chunks.size();
        }
    }

    /**
     * 计算一个区块：raw 采样 32×32（16×16 内部 + 8 边界）→ 热侵蚀 + 水滴侵蚀 → 返回含边界网格。
     *
     * <p>一致性：raw 采样是纯函数（同种子同坐标恒定），侵蚀是纯函数且只读本块 32×32 网格内
     * （含 8 格边界 raw 值，边界值来自邻居区块的 raw 采样而非侵蚀结果）→ 无跨块串扰。
     * 影响半径 = 侵蚀迭代数/水滴步数 <= 边界 8，内部 16×16 侵蚀时邻域全部落在本块网格内。
     */
    private float[] computeChunk(int cx, int cz) {
        int gridSize = CHUNK_SIZE + 2 * border;
        float[] grid = new float[gridSize * gridSize];
        int worldX0 = cx * CHUNK_SIZE - border;
        int worldZ0 = cz * CHUNK_SIZE - border;
        for (int gz = 0; gz < gridSize; gz++) {
            for (int gx = 0; gx < gridSize; gx++) {
                grid[gz * gridSize + gx] = rawHeight.rawHeight(worldX0 + gx, worldZ0 + gz);
            }
        }
        // 侵蚀（纯内存迭代）。边界 ring 冻结（border 宽度不修改，只作邻居输入）——
        // 保证跨块一致：边界格值恒 = raw，邻居区块读到的对应格相同。
        TerrainErosion.HydraulicParams hp = fillParams();
        grid = TerrainErosion.thermalErode(grid, gridSize, this.talus, this.thermalIterations, this.border);
        grid = TerrainErosion.hydraulicErode(grid, gridSize, seedFor(cx, cz), hp, this.border);
        return grid;
    }

    /** 侵蚀参数（由 HeightMapBuilder 配置注入；默认值供纯缓存机制测试用） */
    private float talus = 4f;
    private int thermalIterations = 8;
    private int dropsPerChunk = 24;
    private int maxSteps = 8;
    private float inertia = 0.05f;
    private float sedimentCapacityFactor = 4f;
    private float minSedimentCapacity = 0.01f;
    private float erosionRate = 0.3f;
    private float depositionRate = 0.1f;
    private int erosionRadius = 3;

    /** 注入侵蚀参数（HeightMapBuilder 构造时调用） */
    void configureErosion(float talus, int thermalIterations, int dropsPerChunk, int maxSteps,
                          float inertia, float sedimentCapacityFactor, float minSedimentCapacity,
                          float erosionRate, float depositionRate, int erosionRadius) {
        this.talus = talus;
        this.thermalIterations = thermalIterations;
        this.dropsPerChunk = dropsPerChunk;
        this.maxSteps = maxSteps;
        this.inertia = inertia;
        this.sedimentCapacityFactor = sedimentCapacityFactor;
        this.minSedimentCapacity = minSedimentCapacity;
        this.erosionRate = erosionRate;
        this.depositionRate = depositionRate;
        this.erosionRadius = erosionRadius;
    }

    /** 水滴参数组装（minErosionSlope：缓坡不侵蚀，防平地被水流汇聚挖坑破坏连续性） */
    private TerrainErosion.HydraulicParams fillParams() {
        return new TerrainErosion.HydraulicParams(
            dropsPerChunk, maxSteps, inertia, sedimentCapacityFactor, minSedimentCapacity,
            erosionRate, depositionRate, erosionRadius, 0.5f);
    }

    /** 区块种子：确定性（同区块必然同 RNG 序列） */
    private static long seedFor(int cx, int cz) {
        return (cx * 73856093L) ^ (cz * 19349663L) ^ 0x5DEECE66DL;
    }

    /** LRU 访问记录 */
    private void touch(long key) {
        synchronized (lru) {
            lru.get(key);
        }
    }

    /** 超限驱逐最久未用区块 */
    private void evictIfNeeded(long key) {
        synchronized (lru) {
            lru.put(key, Boolean.TRUE);
            while (lru.size() > maxChunks) {
                long oldest = lru.keySet().iterator().next();
                lru.remove(oldest);
                chunks.remove(oldest);
            }
        }
    }
}
