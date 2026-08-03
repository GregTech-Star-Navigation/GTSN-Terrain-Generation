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

    /** 计算一个区块：raw 采样 32×32 → 热侵蚀 + 水滴侵蚀 → 返回含边界网格 */
    private float[] computeChunk(int cx, int cz) {
        throw new UnsupportedOperationException("M6-GREEN: HeightCache.computeChunk 未实现");
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
