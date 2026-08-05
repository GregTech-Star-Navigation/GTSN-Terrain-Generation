package com.gtsn.terrain.noise;

/**
 * 单独验证热侵蚀内核：给定一个陡坡网格，侵蚀后 maxDelta 应收敛到 talus。
 */
public class ProbeThermal {
    public static void main(String[] args) {
        // 构造 32x32 陡坡：左低右高，每格 +10（模拟山体悬崖）
        int size = 32;
        float[] grid = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                grid[y * size + x] = 100 + x * 10f + (y % 3) * 0.5f;
            }
        }
        int before = maxAdj(grid, size);
        float[] eroded = TerrainErosion.thermalErode(grid, size, 8f, 32, 0);
        int after = maxAdj(eroded, size);
        System.out.printf("陡坡(+10/格) 侵蚀前 maxAdj=%d, talus=8 迭代32 后 maxAdj=%d%n", before, after);

        // 更陡：+25/格
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                grid[y * size + x] = 100 + x * 25f;
            }
        }
        float[] eroded2 = TerrainErosion.thermalErode(grid, size, 8f, 32, 0);
        System.out.printf("陡坡(+25/格) 侵蚀前 maxAdj=%d, 后 maxAdj=%d%n", maxAdj(grid, size), maxAdj(eroded2, size));

        // 尖峰：中间 500，四周 100（模拟孤峰）
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                grid[y * size + x] = 100f;
            }
        }
        grid[16 * size + 16] = 500f;
        float[] eroded3 = TerrainErosion.thermalErode(grid, size, 8f, 32, 0);
        System.out.printf("孤峰(500 vs 100) 侵蚀前 maxAdj=%d, 后 maxAdj=%d, 峰残留=%f%n",
            maxAdj(grid, size), maxAdj(eroded3, size), eroded3[16 * size + 16]);
    }

    static int maxAdj(float[] g, int size) {
        int m = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                if (x + 1 < size) m = Math.max(m, Math.round(Math.abs(g[i] - g[i + 1])));
                if (y + 1 < size) m = Math.max(m, Math.round(Math.abs(g[i] - g[i + size])));
            }
        }
        return m;
    }
}
