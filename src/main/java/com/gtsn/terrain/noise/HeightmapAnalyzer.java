package com.gtsn.terrain.noise;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 高度图质量分析器（开发调试用，非模组功能）。
 *
 * <p>定量验证地形质量：海陆比、高度带分布、相邻梯度（连续性）、
 * 对角线相关性（检测 s=x+z 剖面造成的对角条纹伪影）、种子差异、
 * 山链走向性（山域点集 2D PCA 长轴/短轴比）、峰顶自然起伏（山域高度标准差）。
 *
 * <p>用法：<code>java com.gtsn.terrain.noise.HeightmapAnalyzer [seed] [size]</code>，
 * 固定分析 3 个 256×256 验证窗口：@(0,0)、@(-1024,0)、@(512,512)。
 */
public class HeightmapAnalyzer {

    /** 山域阈值：高于该值的采样点视为山脉区域（用于走向性 PCA 与峰顶起伏） */
    static final int MOUNTAIN_THRESHOLD = 250;

    /** 雪线高度（高度 &gt; 该值计为雪线覆盖） */
    static final int SNOW_LINE = 300;

    /** 坡度测量基线（块）：slope = atan(|h[x+base]-h[x-base]| / 2base)。
     *  用 8 块基线而非 1 块：整数高度下 1 块相邻差 1 就是 45°，无法区分现实坡度；
     *  8 块基线 ≈ 现实地形起伏的平缓尺度（avg<12° ⇔ 平均 8 块落差 <1.7 块）。 */
    static final int SLOPE_BASE = 8;

    /** 河流连通性：高山起点阈值（>300 视为内陆高地） */
    static final int RIVER_SOURCE = 300;

    /** 河流连通性：海平面判定（<=62 为海） */
    static final int RIVER_SEA = 62;

    /** 河流连通性：要求单调下降路径的最短长度（块），排除悬崖一步入海 */
    static final int RIVER_MIN_PATH = 64;

    /** 三个验收窗口 */
    static final int[][] WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260803L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        for (int[] win : WINDOWS) {
            analyze(seed, size, win[0], win[1]);
        }
    }

    /** 分析单个窗口并打印完整指标 + 一行紧凑摘要 */
    private static void analyze(long seed, int size, int winX, int winZ) {
        System.out.println("=== GTSN Heightmap Analysis (seed=" + seed
            + ", window " + size + "x" + size + " @(" + winX + "," + winZ + ")) ===");
        int[][] h = sample(seed, size, winX, winZ);
        int total = size * size;

        // 1. 海陆比（>62 为陆地）
        int land = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) if (h[z][x] > 62) land++;
        double landRatio = 100.0 * land / total;

        // 2. 高度带分布
        int[] bands = new int[7]; // 深海/浅海/海岸/平原/丘陵/山地/雪线
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int v = h[z][x];
            max = Math.max(max, v); min = Math.min(min, v);
            if (v < 0) bands[0]++;
            else if (v < 30) bands[1]++;
            else if (v < 62) bands[2]++;
            else if (v < 110) bands[3]++;
            else if (v < 180) bands[4]++;
            else if (v < 300) bands[5]++;
            else bands[6]++;
        }
        String[] names = {"深海", "浅海", "海岸", "平原", "丘陵", "山地", "雪线"};

        // 3. 相邻梯度（连续性，目标 <=8）
        int maxDelta = 0;
        double sumDelta = 0; int count = 0;
        int bdX = 0, bdZ = 0, bdH1 = 0, bdH2 = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            if (x + 1 < size) { int d = Math.abs(h[z][x] - h[z][x + 1]); if (d > maxDelta) { maxDelta = d; bdX = x; bdZ = z; bdH1 = h[z][x]; bdH2 = h[z][x + 1]; } sumDelta += d; count++; }
            if (z + 1 < size) { int d = Math.abs(h[z][x] - h[z + 1][x]); if (d > maxDelta) { maxDelta = d; bdX = x; bdZ = z; bdH1 = h[z][x]; bdH2 = h[z + 1][x]; } sumDelta += d; count++; }
        }

        // 4. 对角线相关性（s=x+z 剖面伪影检测）
        double diagVar = 0;
        int diagCount = 0;
        for (int s = 0; s < 2 * size - 1; s += 8) {
            Set<Integer> seen = new HashSet<>();
            for (int x = 0; x < size; x++) {
                int z = s - x;
                if (z >= 0 && z < size) seen.add(h[z][x]);
            }
            if (seen.size() >= 5) {
                double mean = seen.stream().mapToInt(Integer::intValue).average().orElse(0);
                double v = seen.stream().mapToDouble(vv -> (vv - mean) * (vv - mean)).average().orElse(0);
                diagVar += v; diagCount++;
            }
        }
        diagVar /= Math.max(1, diagCount);

        // 5. 多样性
        Set<Integer> distinct = new HashSet<>();
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) distinct.add(h[z][x]);

        // 6. 种子差异（两个种子对比）
        long seed2 = seed + 777;
        int[][] h2 = sample(seed2, size, winX, winZ);
        int diff = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) if (h[z][x] != h2[z][x]) diff++;
        double seedDiff = 100.0 * diff / total;

        // 7. 雪线占比（>300）
        double snowRatio = 100.0 * bands[6] / total;

        // 8. 山链走向性
        List<int[]> mtPts = new ArrayList<>();
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) if (h[z][x] > MOUNTAIN_THRESHOLD) mtPts.add(new int[]{x, z});
        double chainRatio = pcaAspectRatio(mtPts);          // 点位 PCA 长轴/短轴比
        double chainCoherence = gradientCoherence(h, size); // 梯度结构张量方向性

        // 9. 峰顶自然起伏：山域高度标准差（>5 非平台）
        double peakStd = 0;
        if (!mtPts.isEmpty()) {
            double mean = 0;
            for (int[] p : mtPts) mean += h[p[1]][p[0]];
            mean /= mtPts.size();
            double v = 0;
            for (int[] p : mtPts) { double d = h[p[1]][p[0]] - mean; v += d * d; }
            peakStd = Math.sqrt(v / mtPts.size());
        }

        // 10. 高程金字塔分布（M6 现实感指标 a）：低地(62-140) 占比 35-60%，高山(>400) 占比 <10%
        int lowland = 0, alpine = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int v = h[z][x];
            if (v > 62 && v <= 140) lowland++;
            if (v > 400) alpine++;
        }
        double lowlandRatio = 100.0 * lowland / total;
        double alpineRatio = 100.0 * alpine / total;

        // 11. 坡度分布（M6 现实感指标 b）：8 块基线平均坡度 <12°，>30° 陡坡 <5%
        double slopeSum = 0; int slopeCount = 0;
        int steep30 = 0;
        for (int z = SLOPE_BASE; z < size - SLOPE_BASE; z++) for (int x = SLOPE_BASE; x < size - SLOPE_BASE; x++) {
            int dx = Math.abs(h[z][x + SLOPE_BASE] - h[z][x - SLOPE_BASE]);
            int dz = Math.abs(h[z + SLOPE_BASE][x] - h[z - SLOPE_BASE][x]);
            double grad = Math.max(dx, dz) / (2.0 * SLOPE_BASE);
            double deg = Math.toDegrees(Math.atan(grad));
            slopeSum += deg; slopeCount++;
            if (deg > 30.0) steep30++;
        }
        double avgSlope = slopeSum / Math.max(1, slopeCount);
        double steepRatio = 100.0 * steep30 / Math.max(1, slopeCount);

        // 12. 海岸线复杂度（M6 现实感指标 c）：海岸格数/窗口边长
        int coast = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            if (h[z][x] <= 62) continue;
            boolean hasSea = (x > 0 && h[z][x - 1] <= 62) || (x + 1 < size && h[z][x + 1] <= 62)
                || (z > 0 && h[z - 1][x] <= 62) || (z + 1 < size && h[z + 1][x] <= 62);
            if (hasSea) coast++;
        }
        double coastRatio = coast / (double) size;

        // 13. 河流连通性（M6 现实感指标 d）：存在从内陆(>300)到海(<=62)连续下降的谷道路径
        boolean riverOk = hasDescendingPathToSea(h, size);

        System.out.printf("海陆比: 陆地 %.1f%% (目标 25-45%%)%n", landRatio);
        System.out.printf("高度范围: [%d, %d] (目标 [-59, 580], 出生点窗口最高>=480)%n", min, max);
        for (int i = 0; i < 7; i++) System.out.printf("  %s: %.1f%%%n", names[i], 100.0 * bands[i] / total);
        System.out.printf("连续性: 相邻最大差=%d (目标<=8), 平均差=%.2f, 最陡处 (%d,%d): %d→%d%n",
            maxDelta, sumDelta / count, winX + bdX, winZ + bdZ, bdH1, bdH2);
        System.out.printf("对角线高度方差: %.1f (越大越好; <200 提示强对角条纹伪影)%n", diagVar);
        System.out.printf("多样性: %d 个不同高度值%n", distinct.size());
        System.out.printf("种子差异: %.1f%% (应>70%%)%n", seedDiff);
        System.out.printf("雪线(>%d)占比: %.1f%% (目标<10%%)%n", SNOW_LINE, snowRatio);
        System.out.printf("山链走向性: 山域点=%d, PCA长轴/短轴比=%.2f, 梯度方向性=%.2f (目标>1.5)%n", mtPts.size(), chainRatio, chainCoherence);
        System.out.printf("峰顶起伏: 山域高度标准差=%.2f (目标>5, 非平台)%n", peakStd);
        System.out.printf("高程金字塔(M6a): 低地(62-140)=%.1f%% (目标 35-60%%), 高山(>400)=%.1f%% (目标<10%%)%n",
            lowlandRatio, alpineRatio);
        System.out.printf("坡度分布(M6b): 平均坡度=%.2f° (目标<12°), >30°陡坡=%.2f%% (目标<5%%)%n",
            avgSlope, steepRatio);
        System.out.printf("海岸线复杂度(M6c): 海岸格数=%d, 长度/边长=%.2f%n", coast, coastRatio);
        System.out.printf("河流连通性(M6d): 高山到海连续下降路径=%s%n", riverOk ? "存在" : "不存在");

        System.out.printf("SUMMARY win=(%d,%d) max=%d land=%.1f%% maxDelta=%d diagVar=%.1f seedDiff=%.1f%% snow=%.1f%% pcaRatio=%.2f gradDir=%.2f peakStd=%.2f lowland=%.1f%% alpine=%.1f%% avgSlope=%.2f° steep30=%.2f%% coast=%.2f river=%s cliff@(%d,%d)%n",
            winX, winZ, max, landRatio, maxDelta, diagVar, seedDiff, snowRatio, chainRatio, chainCoherence, peakStd,
            lowlandRatio, alpineRatio, avgSlope, steepRatio, coastRatio, riverOk,
            winX + bdX, winZ + bdZ);
        System.out.println();
    }

    /**
     * M6 现实感指标 d：是否存在从内陆(>300)到海(<=62)的连续下降（谷道）路径。
     *
     * <p>实现：从每个 h>300 的高地格出发做记忆化 DFS，只沿高度严格下降（或相等）的 4 邻域走；
     * 若任一高地格能到达 <=62 的海格且路径长度 >= {@link #RIVER_MIN_PATH}，则连通。
     * 这量化「低洼连通域从高山带延伸到海」——河流存在性的粗代理指标。
     */
    static boolean hasDescendingPathToSea(int[][] h, int size) {
        Boolean[] memo = new Boolean[size * size];
        boolean[] high = new boolean[size * size];
        java.util.List<Integer> starts = new ArrayList<>();
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int v = h[z][x];
            if (v > RIVER_SOURCE) { high[z * size + x] = true; starts.add(z * size + x); }
        }
        if (starts.isEmpty()) return false;
        for (int s : starts) {
            if (dfsRiver(s, h, size, memo, new boolean[size * size], 0)) return true;
        }
        return false;
    }

    /** 记忆化 DFS：从 idx 出发沿下降邻域能否到海，且路径足够长 */
    private static boolean dfsRiver(int idx, int[][] h, int size, Boolean[] memo, boolean[] vis, int depth) {
        if (depth > 4096) return false; // 防御：路径爆炸保护
        int x = idx % size, z = idx / size;
        int v = h[z][x];
        if (v <= RIVER_SEA && depth >= RIVER_MIN_PATH) return true;
        if (memo[idx] != null) return memo[idx];
        vis[idx] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nx >= size || nz < 0 || nz >= size) continue;
            int ni = nz * size + nx;
            if (vis[ni]) continue;
            if (h[nz][nx] > v + 0) continue; // 只走下降或等高
            if (dfsRiver(ni, h, size, memo, vis, depth + 1)) { memo[idx] = true; return true; }
        }
        memo[idx] = false;
        return false;
    }

    /**
     * 2D 主成分分析：山域点集长轴/短轴比（主轴标准差之比）。
     * 各向同性斑点 → 比接近 1；带状/链状延伸 → 比 > 1.5。
     */
    static double pcaAspectRatio(List<int[]> pts) {
        if (pts.size() < 10) return 1.0;
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
        double l1 = trace / 2.0 + disc;
        double l2 = Math.max(1e-9, trace / 2.0 - disc);
        return Math.sqrt(l1 / l2);
    }

    /**
     * 梯度结构张量方向性（自相关方向性）：山域高度场梯度的协方差张量主轴比。
     * 链状山脉的梯度几乎全垂直于走向（张量高度各向异性），各向同性斑点则近似各向同性。
     * 比值 = 主轴标准差之比（>1.5 有明确走向）。
     */
    static double gradientCoherence(int[][] h, int size) {
        return gradientCoherence(h, size, MOUNTAIN_THRESHOLD);
    }

    static double gradientCoherence(int[][] h, int size, int threshold) {
        double a = 0, b = 0, c = 0;
        int n = 0;
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                if (h[z][x] <= threshold) continue; // 仅统计山域梯度
                double gx = h[z][x + 1] - h[z][x - 1];
                double gz = h[z + 1][x] - h[z - 1][x];
                if (gx == 0 && gz == 0) continue;
                a += gx * gx; b += gx * gz; c += gz * gz;
                n++;
            }
        }
        if (n < 64) return 1.0;
        double trace = a + c;
        double disc = Math.sqrt(Math.max(0.0, (a - c) * (a - c) / 4.0 + b * b));
        double l1 = Math.max(1e-9, (trace / 2.0 + disc));
        double l2 = Math.max(1e-9, (trace / 2.0 - disc));
        return Math.sqrt(l1 / l2);
    }

    private static int[][] sample(long seed, int size, int winX, int winZ) {
        HeightMapBuilder b = new HeightMapBuilder(new TerrainConfig(seed));
        int[][] h = new int[size][size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) h[z][x] = b.getHeight(winX + x, winZ + z);
        return h;
    }
}
