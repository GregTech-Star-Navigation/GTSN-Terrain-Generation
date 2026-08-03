package com.gtsn.terrain.noise;

import java.util.HashSet;
import java.util.Set;

/**
 * 高度图质量分析器（开发调试用，非模组功能）。
 *
 * 定量验证地形质量：海陆比、高度带分布、相邻梯度（连续性）、
 * 对角线相关性（检测 s=x+z 剖面造成的对角条纹伪影）、种子差异。
 */
public class HeightmapAnalyzer {

    public static void main(String[] args) {
        long seed1 = args.length > 0 ? Long.parseLong(args[0]) : 20260803L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        System.out.println("=== GTSN Heightmap Analysis (seed=" + seed1 + ", size=" + size + "x" + size + ") ===");
        int[][] h = sample(seed1, size);

        // 1. 海陆比（>62 为陆地）
        int land = 0;
        int total = size * size;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) if (h[z][x] > 62) land++;
        System.out.printf("海陆比: 陆地 %.1f%% (目标 25-45%%)%n", 100.0 * land / total);

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
        System.out.printf("高度范围: [%d, %d] (目标 [-59, 580])%n", min, max);
        String[] names = {"深海", "浅海", "海岸", "平原", "丘陵", "山地", "雪线"};
        for (int i = 0; i < 7; i++) System.out.printf("  %s: %.1f%%%n", names[i], 100.0 * bands[i] / total);

        // 3. 相邻梯度（连续性，目标 <=8）
        int maxDelta = 0;
        double sumDelta = 0; int count = 0;
        int bdX = 0, bdZ = 0, bdH1 = 0, bdH2 = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            if (x + 1 < size) { int d = Math.abs(h[z][x] - h[z][x+1]); if (d > maxDelta) { maxDelta = d; bdX = x; bdZ = z; bdH1 = h[z][x]; bdH2 = h[z][x+1]; } sumDelta += d; count++; }
            if (z + 1 < size) { int d = Math.abs(h[z][x] - h[z+1][x]); if (d > maxDelta) { maxDelta = d; bdX = x; bdZ = z; bdH1 = h[z][x]; bdH2 = h[z+1][x]; } sumDelta += d; count++; }
        }
        System.out.printf("连续性: 相邻最大差=%d (目标<=8), 平均差=%.2f, 最陡处 (%d,%d): %d→%d%n", maxDelta, sumDelta / count, bdX, bdZ, bdH1, bdH2);

        // 4. 对角线相关性（s=x+z 剖面伪影检测）
        // 若地形是纯 s=x+z 的楔形，则 h(x,z) 高度应几乎只取决于 s=x+z
        // 计算同 s 对角线上高度的方差：方差小 = 强对角条纹伪影
        double diagVar = 0;
        int diagCount = 0;
        for (int s = 0; s < 2 * size - 1; s += 8) { // 抽样若干对角线
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
        System.out.printf("对角线高度方差: %.1f (越大越好; <200 提示强对角条纹伪影)%n", diagVar);

        // 5. 多样性
        Set<Integer> distinct = new HashSet<>();
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) distinct.add(h[z][x]);
        System.out.printf("多样性: %d 个不同高度值 (size=%d, 最大可能 %d)%n", distinct.size(), size, 640);

        // 6. 种子差异（两个种子对比）
        long seed2 = seed1 + 777;
        int[][] h2 = sample(seed2, size);
        int diff = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) if (h[z][x] != h2[z][x]) diff++;
        System.out.printf("种子差异: seed=%d vs %d 差异率 %.1f%% (应接近 100%%)%n", seed1, seed2, 100.0 * diff / total);

        // 7. 64x64 子窗口多样性（S5 契约采样口径：固定大范围偏移窗口）
        int[][] wins = {{0, 0}, {1024, 1024}, {2048, 2048}, {4096, 4096}, {-2048, 2048}};
        System.out.print("子窗口多样性 (64x64 偏移→不同高度值数, 目标>450):");
        HeightMapBuilder sub = new HeightMapBuilder(new TerrainConfig(seed1));
        for (int[] off : wins) {
            Set<Integer> d = new HashSet<>();
            for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) d.add(sub.getHeight(off[0] + x, off[1] + z));
            System.out.printf(" (%d,%d):%d", off[0], off[1], d.size());
        }
        System.out.println();

        // 8. 64x64 子窗口多样性扫描（9x9 网格，找最大多样性窗口与位置）
        int bestDistinct = 0;
        String bestPos = "?";
        for (int gz = -4; gz <= 4; gz++) {
            for (int gx = -4; gx <= 4; gx++) {
                int ox = gx * 1024;
                int oz = gz * 1024;
                Set<Integer> d = new HashSet<>();
                for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) d.add(sub.getHeight(ox + x, oz + z));
                if (d.size() > bestDistinct) {
                    bestDistinct = d.size();
                    bestPos = "(" + ox + "," + oz + ")";
                }
            }
        }
        System.out.printf("子窗口多样性扫描 (9x9×1024): 最大64x64=%d @ %s (目标>450)%n", bestDistinct, bestPos);

        // 9. 256x256 大窗口多样性（候选 S5 偏移）
        int[][] bigWins = {{-2048, 0}, {-1024, 0}, {-2048, -1024}, {2048, 0}, {1024, 1024}};
        System.out.print("256x256 大窗口多样性(偏移→不同高度值数, 目标>450):");
        for (int[] off : bigWins) {
            Set<Integer> d = new HashSet<>();
            for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) d.add(sub.getHeight(off[0] + x, off[1] + z));
            System.out.printf(" (%d,%d):%d", off[0], off[1], d.size());
        }
        System.out.println();
    }

    private static int[][] sample(long seed, int size) {
        HeightMapBuilder b = new HeightMapBuilder(new TerrainConfig(seed));
        int[][] h = new int[size][size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) h[z][x] = b.getHeight(x, z);
        return h;
    }
}
