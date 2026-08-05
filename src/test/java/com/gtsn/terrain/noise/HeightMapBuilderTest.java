package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
 *   <li>S5 多样性窗口由 64×64@(0,0) 改为 256×256@(-1024,0) 大范围固定偏移采样。
 *       实测：2D 板块尺度大陆地形（大陆频率 0.002，板块 ~500 格）下，任何 64×64
 *       窗口最大跨度仅 ~413 个不同高度值（全 9×9×1024 偏移扫描），无法达到 >450
 *       ——M2 的「64×64 覆盖完整高度梯度」语义只有一维对角线剖面这种人工构造才能
 *       满足，新算法下被连续性(<=8)与板块尺度共同排除。256×256 窗口在
 *       (-1024,0) 处实测 638 个不同高度值（余量 188），跨度覆盖深海到高山，
 *       是保持 >450 强断言的可靠采样口径（任务允许「固定大范围偏移采样」）。</li>
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

    /** S5 多样性窗口：256×256 于大范围偏移 (-1024,0)，跨深海到高山（实测 638 个不同高度值） */
    private static final int D_GRID = 256;
    private static final int DX0 = -1024;
    private static final int DZ0 = 0;

    /** S9 山链走向性窗口：256×256 于 (-1024,0)（高海拔山域覆盖最充分的验证窗口） */
    private static final int CHAIN_GRID = 256;
    private static final int CHAIN_X0 = -1024;
    private static final int CHAIN_Z0 = 0;

    /** S9 山域阈值：400（雪线之上）——隔离山链核心区，切掉大陆度基底抬升的高原（见 S9 论证） */
    private static final int CHAIN_THRESHOLD = 400;

    /** M6 现实感指标窗口：三个验收窗口（与 HeightmapAnalyzer 口径一致） */
    private static final int M6_GRID = 256;
    private static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

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

    /**
     * S9 山链走向性：山脉核心区（高度 &gt; 400）呈带状/链状延伸而非各向同性斑点。
     *
     * <p>指标：对高于阈值的采样点集合做 2D 主成分分析（协方差矩阵特征分解），
     * 取长轴/短轴比（主轴标准差之比）。链状山脉的核心区狭长（比 &gt; 1.5），
     * 各向同性斑点（随机土丘）近圆形（比接近 1）。
     *
     * <p>窗口与阈值论证（基线实测，M4 斑点评测）：
     * <ul>
     *   <li>窗口取 (-1024,0)（高海拔山域覆盖最充分的验证窗口，山域 15k+ 采样点）。</li>
     *   <li>阈值取 400 而非 250：该窗口大陆度极高，基底海拔本身就把 60%+ 格点抬过 250
     *       （高原稀释链信号，M4/M5 在 250 处均 <1.5 无法判别）；阈值 400 切掉基底高原，
     *       只剩山链核心区——M4 斑点实测长轴/短轴比 1.27（RED），M5 山链 1.89（GREEN）。</li>
     * </ul>
     *
     * <p>梯度结构张量（自相关方向性）在该窗口失效：V 型尖峰梯度向心（径向各向同性），
     * 稀释了链带侧翼的方向性信号（M4 1.22 / M5 1.27~1.42），故契约采用点位 PCA。
     */
    @Test
    void s9_mountainChainsAreDirectional() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder, CHAIN_X0, CHAIN_Z0, CHAIN_GRID);

        // 收集山域采样点 (x, z)
        List<int[]> pts = new ArrayList<>();
        for (int z = 0; z < CHAIN_GRID; z++) {
            for (int x = 0; x < CHAIN_GRID; x++) {
                if (heights[z * CHAIN_GRID + x] > CHAIN_THRESHOLD) {
                    pts.add(new int[]{x, z});
                }
            }
        }
        assertTrue(pts.size() >= 200, "山域采样点不足 " + pts.size() + "（窗口内山脉核心区过少，指标无统计意义）");

        // 2D 协方差矩阵 [[cxx, cxz], [cxz, czz]] 特征分解
        int n = pts.size();
        double mx = 0, mz = 0;
        for (int[] p : pts) { mx += p[0]; mz += p[1]; }
        mx /= n; mz /= n;
        double cxx = 0, czz = 0, cxz = 0;
        for (int[] p : pts) {
            double dx = p[0] - mx, dz = p[1] - mz;
            cxx += dx * dx; czz += dz * dz; cxz += dx * dz;
        }
        cxx /= n; czz /= n; cxz /= n;
        double trace = cxx + czz;
        double det = cxx * czz - cxz * cxz;
        double disc = Math.sqrt(Math.max(0.0, trace * trace / 4.0 - det));
        double lambda1 = Math.max(1e-9, trace / 2.0 + disc);
        double lambda2 = Math.max(1e-9, trace / 2.0 - disc);
        double ratio = Math.sqrt(lambda1 / lambda2);

        assertTrue(ratio > 1.5,
            "山链走向性不足: 山域核心区长轴/短轴比 " + String.format("%.2f", ratio)
                + " <= 1.5，山脉呈各向同性斑点而非带状/链状延伸");
    }

    /**
     * S10 高程金字塔分布（M6 现实感指标 a）：低地(62-140) 占陆地 35-60%，高山(>400) 占陆地 <10%。
     *
     * <p>分母为陆地格（>62）而非全窗口——自然地形金字塔型：低地为主体、雪线稀薄。
     * 三验收窗口都验证（任意窗口都可能落在大陆或海洋上，海洋窗口陆地格少，百分比口径自动适应）。
     */
    @Test
    void s10_elevationPyramidDistribution() {
        HeightMapBuilder builder = newBuilder();
        for (int[] win : M6_WINDOWS) {
            int[] heights = sampleGrid(builder, win[0], win[1], M6_GRID);
            int land = 0, lowland = 0, alpine = 0;
            for (int h : heights) {
                if (h > TerrainConfig.SEA_LEVEL) {
                    land++;
                    if (h <= 140) lowland++;
                    if (h > 400) alpine++;
                }
            }
            if (land < 500) continue; // 纯海洋窗口跳过（陆地格不足无统计意义）
            double lowlandRatio = 100.0 * lowland / land;
            double alpineRatio = 100.0 * alpine / land;
            assertTrue(lowlandRatio >= 35 && lowlandRatio <= 60,
                "窗口 (" + win[0] + "," + win[1] + ") 低地占比 " + String.format("%.1f%%", lowlandRatio)
                    + " 不在 [35%, 60%]（陆地 " + land + "）");
            assertTrue(alpineRatio < 10,
                "窗口 (" + win[0] + "," + win[1] + ") 高山(>400)占比 " + String.format("%.1f%%", alpineRatio)
                    + " 超过 10%（陆地 " + land + "）");
        }
    }

    /**
     * S11 坡度分布（M6 现实感指标 b）：8 块基线平均坡度 <22°，>30° 陡坡占比 <15%。
     *
     * <p>阈值按真实地形数据放宽（用户决策 2026-08-05）：真实山地窗口平均坡度 20-30°、
     * 陡坡面积 20%+ 是常态；原 <12°/<5% 是荷兰平原标准，与「现实地形」目标自相矛盾。
     * 保留 maxDelta≤8（防悬崖）作为硬性连续性约束，坡度契约只防「全窗口陡峭」。
     *
     * <p>坡度基线取 8 块（同 HeightmapAnalyzer.SLOPE_BASE）：1 块基线在整数高度下
     * 相邻差 1 就是 45°，无法区分现实坡度；8 块基线对应「地形起伏的平缓尺度」。
     * 全窗口平均（海洋坡度 0 计入，拉低均值——合理，因为海洋占窗口大半时现实地形平均坡度本来就低）。
     */
    @Test
    void s11_slopeDistribution() {
        HeightMapBuilder builder = newBuilder();
        int base = 8;
        for (int[] win : M6_WINDOWS) {
            int[] heights = sampleGrid(builder, win[0], win[1], M6_GRID);
            double slopeSum = 0;
            int slopeCount = 0, steep30 = 0;
            for (int z = base; z < M6_GRID - base; z++) {
                for (int x = base; x < M6_GRID - base; x++) {
                    int i = z * M6_GRID + x;
                    int dx = Math.abs(heights[i + base] - heights[i - base]);
                    int dz = Math.abs(heights[(z + base) * M6_GRID + x] - heights[(z - base) * M6_GRID + x]);
                    double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / (2.0 * base)));
                    slopeSum += deg;
                    slopeCount++;
                    if (deg > 30.0) steep30++;
                }
            }
            double avg = slopeSum / slopeCount;
            double steepRatio = 100.0 * steep30 / slopeCount;
            assertTrue(avg < 22.0,
                "窗口 (" + win[0] + "," + win[1] + ") 平均坡度 " + String.format("%.2f°", avg) + " 超过 22°");
            assertTrue(steepRatio < 15.0,
                "窗口 (" + win[0] + "," + win[1] + ") >30° 陡坡占比 " + String.format("%.2f%%", steepRatio) + " 超过 15%");
        }
    }

    /**
     * S12 海岸线复杂度（M6 现实感指标 c）：海岸线长度/窗口边长 > 1.2。
     *
     * <p>粗指标：陆地格（>62）中与海格（<=62）相邻的格数 ≈ 海岸线长度，除以窗口边长 256。
     * 直线海岸（一条边穿过窗口）比值约 1.0；有海湾/半岛的分形海岸比值明显 >1.2。
     * 三窗口至少两个达标（要求全部达标会让纯海洋/纯大陆窗口无法成立）。
     */
    @Test
    void s12_coastlineComplexity() {
        HeightMapBuilder builder = newBuilder();
        int pass = 0;
        for (int[] win : M6_WINDOWS) {
            int[] heights = sampleGrid(builder, win[0], win[1], M6_GRID);
            int coast = 0;
            for (int z = 0; z < M6_GRID; z++) {
                for (int x = 0; x < M6_GRID; x++) {
                    int i = z * M6_GRID + x;
                    if (heights[i] <= TerrainConfig.SEA_LEVEL) continue;
                    boolean hasSea = (x > 0 && heights[i - 1] <= TerrainConfig.SEA_LEVEL)
                        || (x + 1 < M6_GRID && heights[i + 1] <= TerrainConfig.SEA_LEVEL)
                        || (z > 0 && heights[i - M6_GRID] <= TerrainConfig.SEA_LEVEL)
                        || (z + 1 < M6_GRID && heights[i + M6_GRID] <= TerrainConfig.SEA_LEVEL);
                    if (hasSea) coast++;
                }
            }
            double ratio = coast / (double) M6_GRID;
            if (ratio > 1.2) pass++;
        }
        assertTrue(pass >= 2,
            "海岸线复杂度过低：仅 " + pass + "/3 窗口海岸线长度/边长 > 1.2（需海湾半岛而非直线海岸）");
    }

    /**
     * S13 河流连通性（M6 现实感指标 d）：存在从内陆(>300)到海(<=62)连续下降的谷道路径。
     *
     * <p>量化：记忆化 DFS 从每个 h>300 的高地格出发，只沿严格下降（或等高）的 4 邻域走；
     * 若存在路径到达 <=62 的海格且长度 >= 64，则河流连通成立。这验证「低洼连通域从
     * 高山带延伸到海」——河流系统的粗代理（有连续下坡的谷道，水才能从山流向海）。
     * 窗口取 origin（既含高地又含海，最具代表性）。
     */
    @Test
    void s13_riverConnectivityHighToSea() {
        HeightMapBuilder builder = newBuilder();
        int[] heights = sampleGrid(builder, 0, 0, M6_GRID);
        assertTrue(hasDescendingPathToSea(heights, M6_GRID),
            "origin 窗口不存在从内陆(>300)到海(<=62)的连续下降路径——河流系统缺失");
    }

    /** S13 辅助：记忆化 DFS 是否存在从 h>300 到海(<=62)的下降路径（长度>=64） */
    private static boolean hasDescendingPathToSea(int[] h, int size) {
        int n = size * size;
        Boolean[] memo = new Boolean[n];
        boolean[] vis = new boolean[n];
        java.util.List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (h[i] > 300) starts.add(i);
        }
        if (starts.isEmpty()) return false;
        for (int s : starts) {
            if (dfsSea(s, h, size, memo, vis, 0)) return true;
        }
        return false;
    }

    private static boolean dfsSea(int idx, int[] h, int size, Boolean[] memo, boolean[] vis, int depth) {
        if (depth > 4096) return false;
        int x = idx % size, z = idx / size;
        if (h[idx] <= 62 && depth >= 64) return true;
        if (memo[idx] != null) return memo[idx];
        vis[idx] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nx >= size || nz < 0 || nz >= size) continue;
            int ni = nz * size + nx;
            if (vis[ni] || h[ni] > h[idx] + 0) continue; // 只走下降或等高
            if (dfsSea(ni, h, size, memo, vis, depth + 1)) { memo[idx] = true; return true; }
        }
        memo[idx] = false;
        return false;
    }
}
