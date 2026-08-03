package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 侵蚀内核 seam 契约测试。
 *
 * <p>被测 seam：{@link TerrainErosion} 两个纯函数（输入高度网格 float[] + 参数，输出侵蚀后网格）：
 * <ul>
 *   <li>T1-T3 热侵蚀：talus 松弛——超过休止角的坡度被抹平，总物质守恒，确定性；平坦输入不变</li>
 *   <li>T4-T5 水滴侵蚀：陡坡被刻出谷道（V 形谷/冲积），确定性，高度有界</li>
 * </ul>
 *
 * <p>纯函数 seam 的设计理由：侵蚀依赖邻域（非逐点），无法在 getHeight 里逐点求值，
 * 所以内核独立成纯函数，由 HeightCache 在区块粒度上调用——这正是 M6 性能设计的关键。
 * 输入为 size×size 行优先的 float[] 网格，输出等长新数组（不修改输入）。
 */
class TerrainErosionTest {

    // ---------------- T1-T3 热侵蚀 ----------------

    /** T1 休止角松弛：陡峭峰/崖在侵蚀后最大相邻坡度 <= talus + 容差，且总物质守恒 */
    @Test
    void t1_thermalErosionRelaxesSteepSlopesAndConservesMass() {
        int size = 32;
        float[] grid = new float[size * size];
        // 中央尖峰（100）+ 周围平底（10）：相对落差 90，远超 talus=4
        Arrays.fill(grid, 10f);
        grid[16 * size + 16] = 100f;
        grid[15 * size + 16] = 60f;
        grid[17 * size + 16] = 60f;
        grid[16 * size + 15] = 60f;
        grid[16 * size + 17] = 60f;

        double massBefore = sum(grid);
        float[] out = TerrainErosion.thermalErode(grid, size, 4f, 30);

        // 质量守恒：侵蚀只搬运不产生/消灭物质
        assertEquals(massBefore, sum(out), 1e-2, "热侵蚀后总物质不守恒");

        // 最大相邻坡度收敛到 <= talus（边界近似除外：只检查内部 24×24）
        int maxSlope = 0;
        for (int z = 4; z < size - 4; z++) {
            for (int x = 4; x < size - 4; x++) {
                int i = z * size + x;
                maxSlope = Math.max(maxSlope, (int) Math.abs(out[i] - out[i + 1]));
                maxSlope = Math.max(maxSlope, (int) Math.abs(out[i] - out[i - 1]));
                maxSlope = Math.max(maxSlope, (int) Math.abs(out[i] - out[i + size]));
                maxSlope = Math.max(maxSlope, (int) Math.abs(out[i] - out[i - size]));
            }
        }
        assertTrue(maxSlope <= 4 + 1, "热侵蚀后最大相邻坡度 " + maxSlope + " 未收敛到 talus=4");
    }

    /** T2 确定性：同输入两次侵蚀结果完全一致（纯函数，供 HeightCache 跨块一致依赖） */
    @Test
    void t2_thermalErosionIsDeterministic() {
        int size = 16;
        float[] grid = new float[size * size];
        for (int i = 0; i < grid.length; i++) grid[i] = (i % 7) * 3f - 9f; // 粗糙输入

        float[] a = TerrainErosion.thermalErode(grid, size, 4f, 10);
        float[] b = TerrainErosion.thermalErode(grid, size, 4f, 10);
        assertTrue(Arrays.equals(a, b), "热侵蚀两次结果不一致（纯函数确定性被破坏）");
        // 不修改输入
        for (int i = 0; i < grid.length; i++) {
            assertEquals((i % 7) * 3f - 9f, grid[i], 1e-6, "热侵蚀修改了输入数组");
        }
    }

    /** T3 平坦输入不变（无坡度可搬运，侵蚀是恒等变换） */
    @Test
    void t3_thermalErosionLeavesFlatGridUntouched() {
        int size = 16;
        float[] grid = new float[size * size];
        Arrays.fill(grid, 42f);
        float[] out = TerrainErosion.thermalErode(grid, size, 4f, 20);
        for (int i = 0; i < out.length; i++) {
            assertEquals(42f, out[i], 1e-6, "平坦网格被热侵蚀改动");
        }
    }

    // ---------------- T4-T5 水滴侵蚀 ----------------

    /** T4 谷道雕刻：倾斜地形经水滴侵蚀后出现下降的谷（沿坡向低洼，方差或深谷增多），且确定性 */
    @Test
    void t4_hydraulicErosionCarvesValleyIntoSlope() {
        int size = 48;
        float[] grid = new float[size * size];
        // 单方向斜坡：h = 100 - (x/48)*80，全窗口线性下降（无任何自然谷）
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                grid[z * size + x] = 100f - (x / (float) size) * 80f;
            }
        }

        TerrainErosion.HydraulicParams p = new TerrainErosion.HydraulicParams(
            400, 30, 0.05f, 4f, 0.01f, 0.3f, 0.1f, 3);
        float[] out = TerrainErosion.hydraulicErode(grid, size, 42L, p);

        // 谷道 = 出现显著低于原始斜坡的深谷（至少 3 个格点比原始高度低 > 6）
        int carved = 0;
        float totalDrop = 0;
        for (int i = 0; i < grid.length; i++) {
            float drop = grid[i] - out[i];
            if (drop > 6f) carved++;
            totalDrop += drop;
        }
        assertTrue(carved >= 3, "水滴侵蚀未刻出谷道（深于 6 的格点仅 " + carved + " 个）");
        assertTrue(totalDrop > 0, "水滴侵蚀没有向下搬运物质");

        // 确定性
        float[] again = TerrainErosion.hydraulicErode(grid, size, 42L, p);
        assertTrue(Arrays.equals(out, again), "水滴侵蚀两次结果不一致");
    }

    /** T5 有界：侵蚀不产生 NaN/Inf，且高度不会塌到负无穷或涨破上限 */
    @Test
    void t5_hydraulicErosionStaysBounded() {
        int size = 24;
        float[] grid = new float[size * size];
        for (int i = 0; i < grid.length; i++) grid[i] = 200f + (i % 11) * 5f;

        TerrainErosion.HydraulicParams p = new TerrainErosion.HydraulicParams(
            200, 40, 0.05f, 4f, 0.01f, 0.3f, 0.1f, 3);
        float[] out = TerrainErosion.hydraulicErode(grid, size, 7L, p);

        for (int i = 0; i < out.length; i++) {
            assertTrue(Float.isFinite(out[i]), "侵蚀产生非有限值: " + out[i]);
            assertTrue(out[i] >= -100f && out[i] <= 1000f, "侵蚀超出合理高度带: " + out[i]);
        }
    }

    private static double sum(float[] a) {
        double s = 0;
        for (float v : a) s += v;
        return s;
    }
}
