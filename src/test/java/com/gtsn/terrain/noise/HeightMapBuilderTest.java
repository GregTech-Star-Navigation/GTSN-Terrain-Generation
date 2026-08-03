package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 里程碑：2D 高度图管线 seam 契约测试。
 * 被测 seam：HeightMapBuilder.getHeight(int x, int z) 公开接口。
 *
 * 五个 seam：
 *  S1 确定性：同一种子同一 (x,z) 两次结果完全一致
 *  S2 海陆比：64x64 网格陆地（> 62）占比 25%-45%
 *  S3 山高范围：最高 <= 580，最低 >= -60
 *  S4 连续性：相邻列高度差绝对值 <= 8
 *  S5 多样性：网格内不同高度值数量 > 500
 */
class HeightMapBuilderTest {

    /** 固定测试种子（确定性依赖种子） */
    private static final long SEED = 20260803L;

    private static final int GRID = 64;

    private static HeightMapBuilder newBuilder() {
        return new HeightMapBuilder(new TerrainConfig(SEED));
    }

    /** 采样 64x64 网格，返回高度数组（行优先：idx = z * GRID + x） */
    private static int[] sampleGrid(HeightMapBuilder builder) {
        int[] heights = new int[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                heights[z * GRID + x] = builder.getHeight(x, z);
            }
        }
        return heights;
    }

    @Test
    void s1_sameSeedSamePositionDeterministic() {
        HeightMapBuilder builder = newBuilder();
        for (int i = 0; i < 256; i++) {
            int x = ((i * 37) % 251) - 125;
            int z = ((i * 91) % 251) - 125;
            int first = builder.getHeight(x, z);
            int second = builder.getHeight(x, z);
            assertEquals(first, second,
                "同一种子同一坐标 (" + x + "," + z + ") 两次结果不一致: " + first + " vs " + second);
        }
    }

    @Test
    void s2_landRatioBetween25And45Percent() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder);
        int land = 0;
        for (int h : heights) {
            if (h > TerrainConfig.SEA_LEVEL) {
                land++;
            }
        }
        double ratio = (double) land / (GRID * GRID);
        assertTrue(ratio >= 0.25 && ratio <= 0.45,
            "海陆比 " + String.format("%.1f%%", ratio * 100) + " 不在 [25%, 45%] 区间 (陆地 " + land + "/" + (GRID * GRID) + ")");
    }

    @Test
    void s3_heightWithinWorldBounds() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder);
        int maxH = Integer.MIN_VALUE;
        int minH = Integer.MAX_VALUE;
        for (int h : heights) {
            maxH = Math.max(maxH, h);
            minH = Math.min(minH, h);
        }
        assertTrue(maxH <= TerrainConfig.MAX_HEIGHT,
            "最高高度 " + maxH + " 超过上限 " + TerrainConfig.MAX_HEIGHT);
        assertTrue(minH >= -60,
            "最低高度 " + minH + " 低于海床下限 -60");
    }

    @Test
    void s4_noAdjacentCliffBeyond8Blocks() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder);
        int maxDelta = 0;
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int h = heights[z * GRID + x];
                if (x + 1 < GRID) {
                    maxDelta = Math.max(maxDelta, Math.abs(h - heights[z * GRID + x + 1]));
                }
                if (z + 1 < GRID) {
                    maxDelta = Math.max(maxDelta, Math.abs(h - heights[(z + 1) * GRID + x]));
                }
            }
        }
        assertTrue(maxDelta <= 8,
            "相邻列最大高度差 " + maxDelta + " 超过 8，存在悬崖断裂");
    }

    @Test
    void s5_moreThan500DistinctHeightValues() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder);
        // 高度范围 [-64, 580]，偏移 +64 映射到非负下标
        boolean[] seen = new boolean[TerrainConfig.MAX_HEIGHT + 65];
        int distinct = 0;
        for (int h : heights) {
            int idx = h + 64;
            if (idx >= 0 && idx < seen.length && !seen[idx]) {
                seen[idx] = true;
                distinct++;
            }
        }
        assertTrue(distinct > 500,
            "不同高度值数量 " + distinct + " 不超过 500，地形过于平坦");
    }
}
