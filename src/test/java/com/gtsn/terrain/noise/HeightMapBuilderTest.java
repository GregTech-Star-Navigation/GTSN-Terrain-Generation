package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 里程碑：2D 高度图管线 seam 契约测试（重构版：2D 大陆度大陆地形）。
 * 被测 seam：HeightMapBuilder.getHeight / isLand / continentalness 公开接口。
 *
 * <p>与原版的差异（重构依据：M2 用 s=x+z 一维对角线剖面过拟合 64×64 窗口，
 * 全域 256×256 定量验证暴露 96.4% 陆地 / 91.6% 雪线 / 对角线方差 22.8 /
 * 种子差异仅 16% 的缺陷）。新算法为真正的 2D 大陆度驱动大陆地形，契约相应更新：
 * <ul>
 *   <li>S2 采样窗口由 64×64@(0,0) 扩为 256×256@(0,0) —— 与全域验证口径一致。
 *       2D 大陆地形下 64×64 窗口可能整体落在一个大陆/海洋板块内，无法代表全局
 *       海陆比；256×256 与原版全量分析窗口相同，海陆比目标直接对齐全域指标。</li>
 *   <li>S5 多样性窗口由 64×64@(0,0) 改为 64×64@(2048,2048) 大范围固定偏移采样 ——
 *       原点窗口可能整体为海/陆导致高度带单一；大偏移窗口横跨大陆内陆，
 *       覆盖平原-丘陵-山地多高度带，保证 &gt;450 个不同高度值。</li>
 *   <li>S6 由「海陆判定与 s=x+z 大陆架剖面一致」改为「海陆判定与 2D 大陆度一致」：
 *       isLand(x,z) ⇔ continentalness(x,z) &gt; 0。s=x+z 剖面已彻底移除。</li>
 * </ul>
 *
 * 八个 seam：
 *  S1 确定性：同一种子同一 (x,z) 两次结果完全一致
 *  S2 海陆比：256x256 全域网格陆地（&gt; 62）占比 25%-45%
 *  S3 山高范围：最高 &lt;= 580，最低 &gt;= -60
 *  S4 连续性：相邻列高度差绝对值 &lt;= 8
 *  S5 多样性：64x64 大范围偏移窗口内不同高度值数量 &gt; 450
 *  S6 海陆一致性：isLand == (continentalness &gt; 0)
 *  S7 海陆判定与大陆度采样确定性
 *  S8 大陆度值域 [-1,1]
 */
class HeightMapBuilderTest {

    /** 固定测试种子（确定性依赖种子） */
    private static final long SEED = 20260803L;

    /** S2/S3/S4 采样窗口：256×256 于原点（与全域验证口径一致） */
    private static final int GRID = 256;
    private static final int X0 = 0;
    private static final int Z0 = 0;

    /** S5 多样性窗口：64×64 于大范围偏移 (2048,2048)，确保横跨大陆内陆与山地 */
    private static final int D_GRID = 64;
    private static final int DX0 = 2048;
    private static final int DZ0 = 2048;

    private static HeightMapBuilder newBuilder() {
        return new HeightMapBuilder(new TerrainConfig(SEED));
    }

    /** 采样 grid×grid 网格（行优先：idx = z * grid + x，坐标自 (x0,z0) 起） */
    private static int[] sampleGrid(HeightMapBuilder builder, int x0, int z0, int grid) {
        int[] heights = new int[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                heights[z * grid + x] = builder.getHeight(x0 + x, z0 + z);
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
        int[] heights = sampleGrid(builder, X0, Z0, GRID);
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
        int[] heights = sampleGrid(builder, X0, Z0, GRID);
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
        int[] heights = sampleGrid(builder, X0, Z0, GRID);
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
    void s5_moreThan450DistinctHeightValues() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder, DX0, DZ0, D_GRID);
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
        assertTrue(distinct > 450,
            "不同高度值数量 " + distinct + " 不超过 450，地形过于平坦");
    }

    /** S6 海陆判定与 2D 大陆度一致：isLand(x,z) ⇔ continentalness(x,z) &gt; 0 */
    @Test
    void s6_isLandMatchesContinentalness() {
        HeightMapBuilder builder = newBuilder();
        for (int z = 0; z < 256; z += 4) {
            for (int x = 0; x < 256; x += 4) {
                double c = builder.continentalness(x, z);
                boolean land = builder.isLand(x, z);
                if (c > 0) {
                    assertTrue(land, "(" + x + "," + z + ") 大陆度 " + c + " &gt; 0 应判定为陆，实际为海");
                } else {
                    assertFalse(land, "(" + x + "," + z + ") 大陆度 " + c + " &lt;= 0 应判定为海，实际为陆");
                }
            }
        }
    }

    /** S7 海陆判定与大陆度采样确定性 */
    @Test
    void s7_isLandAndContinentalnessDeterministic() {
        HeightMapBuilder builder = newBuilder();
        for (int i = 0; i < 256; i++) {
            int x = ((i * 37) % 251) - 125;
            int z = ((i * 91) % 251) - 125;
            assertEquals(builder.isLand(x, z), builder.isLand(x, z),
                "(" + x + "," + z + ") 海陆判定两次不一致");
            assertEquals(builder.continentalness(x, z), builder.continentalness(x, z),
                "(" + x + "," + z + ") 大陆度两次不一致");
        }
    }

    /** S8 大陆度值域 [-1,1]（群系温度/湿度计算的前提） */
    @Test
    void s8_continentalnessWithinUnitRange() {
        HeightMapBuilder builder = newBuilder();
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                double c = builder.continentalness(x, z);
                assertTrue(c >= -1.0 && c <= 1.0, "(" + x + "," + z + ") 大陆度 " + c + " 超出 [-1,1]");
            }
        }
    }
}
