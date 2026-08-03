package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 HeightCache seam 契约测试。
 *
 * <p>被测 seam：{@link HeightCache}——按区块(16×16)预计算含边界(32×32)侵蚀后缓存的
 * 高度查询。缓存负责：确定性、跨区块一致性（相同输入必然相同输出）、LRU 上限内存。
 *
 * <p>fake chunk 坐标策略：raw 高度用纯函数 fakeHeight(x,z) 提供（与真实地形无关，
 * 只测缓存机制本身）；测试用 16 的整数倍坐标把世界切到不同区块，验证跨块一致性。
 */
class HeightCacheTest {

    /** 边界宽度 = 侵蚀影响半径（热侵蚀迭代数/水滴步数上限），须能覆盖侵蚀邻域 */
    private static final int BORDER = 8;

    /** fake 纯函数高度：确定性、低频起伏（保证跨块邻域相关） */
    private static float fakeHeight(int x, int z) {
        double n = Math.sin(x * 0.05) * Math.cos(z * 0.03) * 30
            + Math.sin((x + z) * 0.011) * 55;
        return (float) (80 + n);
    }

    private static HeightCache newCache(int maxEntries) {
        return new HeightCache(HeightCacheTest::fakeHeight, BORDER, maxEntries);
    }

    /** C1 确定性：同坐标重复查询结果一致（纯函数 + 缓存不破坏确定性） */
    @Test
    void c1_sameCoordinateDeterministic() {
        HeightCache cache = newCache(1024);
        for (int i = 0; i < 64; i++) {
            int x = ((i * 37) % 251) - 125;
            int z = ((i * 91) % 251) - 125;
            int a = cache.getHeight(x, z);
            int b = cache.getHeight(x, z);
            assertEquals(a, b, "(" + x + "," + z + ") 两次查询不一致: " + a + " vs " + b);
        }
    }

    /** C2 跨区块一致性：同一世界坐标无论经由哪个区块入口查询（含跨 16 边界）结果一致 */
    @Test
    void c2_crossChunkConsistency() {
        HeightCache cache = newCache(1024);
        // 跨 16 边界两侧的点：x=15 属块 0，x=16 属块 1；z 方向同理
        int[] xs = {0, 15, 16, 31, 32, 63, -1, -16, -17, 100};
        int[] zs = {0, 15, 16, 31, 32, -1, -33, 64, 200};
        // 先以正常顺序查询填缓存
        int[][] first = new int[xs.length][zs.length];
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < zs.length; j++) {
                first[i][j] = cache.getHeight(xs[i], zs[j]);
            }
        }
        // 换一种查询顺序（触发不同区块先填），结果必须完全一致
        HeightCache cache2 = newCache(1024);
        for (int j = zs.length - 1; j >= 0; j--) {
            for (int i = xs.length - 1; i >= 0; i--) {
                assertEquals(first[i][j], cache2.getHeight(xs[i], zs[j]),
                    "(" + xs[i] + "," + zs[j] + ") 不同区块填充顺序结果不一致");
            }
        }
    }

    /** C3 LRU 上限：超出后旧区块被逐出，但逐出后重查值不变（确定性重算），且内存有界 */
    @Test
    void c3_lruEvictionKeepsValuesAndBoundedMemory() {
        int maxEntries = 8;
        HeightCache cache = newCache(maxEntries);
        // 先填满 8 个区块（覆盖 8 个不同区块坐标），记录值
        int[] chunkXs = {0, 16, 32, 48, 64, 80, 96, 112};
        int[] qx = new int[8], qz = new int[8], expect = new int[8];
        for (int i = 0; i < 8; i++) {
            qx[i] = chunkXs[i];
            qz[i] = 0;
            expect[i] = cache.getHeight(qx[i], qz[i]);
        }
        // 再查 8 个新区块（触发逐出）
        for (int i = 0; i < 8; i++) {
            cache.getHeight(1000 + i * 16, 1000);
        }
        // 旧区块重查，值必须仍是原来的（逐出只是丢缓存，不是改结果）
        for (int i = 0; i < 8; i++) {
            assertEquals(expect[i], cache.getHeight(qx[i], qz[i]),
                "LRU 逐出后区块 " + qx[i] + " 重查值变化");
        }
        // 内存上限：内部区块数不得超过 maxEntries
        assertTrue(cache.chunkCount() <= maxEntries,
            "缓存区块数 " + cache.chunkCount() + " 超过上限 " + maxEntries);
    }

    /** C4 区块边界连续性：相邻区块交界处高度无悬崖（侵蚀邻域跨块一致的结果） */
    @Test
    void c4_noCliffAcrossChunkBorder() {
        HeightCache cache = newCache(1024);
        int maxCross = 0;
        // 沿 x 方向跨 16 边界（x=15→16, 31→32, ...）
        for (int z = 0; z < 64; z += 4) {
            for (int x = 15; x < 64; x += 16) {
                int h1 = cache.getHeight(x, z);
                int h2 = cache.getHeight(x + 1, z);
                maxCross = Math.max(maxCross, Math.abs(h1 - h2));
            }
            // 沿 z 方向跨边界
            for (int x = 0; x < 64; x += 4) {
                for (int zc = 15; zc < 64; zc += 16) {
                    int h1 = cache.getHeight(x, zc);
                    int h2 = cache.getHeight(x, zc + 1);
                    maxCross = Math.max(maxCross, Math.abs(h1 - h2));
                }
            }
        }
        assertTrue(maxCross <= 8, "跨区块边界最大高度差 " + maxCross + " > 8（侵蚀边界不一致/悬崖）");
    }
}
