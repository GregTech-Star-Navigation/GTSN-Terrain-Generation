package com.gtsn.terrain.noise;

/**
 * M6 拆层探针：定位 maxDelta 悬崖来自哪一层。
 * 对 origin 窗口逐层测量 maxDelta：base / +band / +mountain / +river / +erosion(cache)。
 */
public class ProbeLayer {

    static final long SEED = 20260803L;

    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        cfg.massifGain = 1400;
        cfg.mountainMaskFrequency = 0.0006f;
        HeightMapBuilder b = new HeightMapBuilder(cfg);

        int N = 256;
        // 各层高度（rawHeight 内部结构不可直接拿，用反射不可行；这里用缓存侵蚀前 vs 后对比）
        int[][] raw = new int[N][N];
        int[][] cache = new int[N][N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                raw[z][x] = Math.round(b.rawHeight(x, z));
                cache[z][x] = b.getHeight(x, z);
            }
        }
        int rawMaxD = maxDelta(raw, N);
        int cacheMaxD = maxDelta(cache, N);

        // 定位 cache 最大悬崖点，看 raw vs cache
        int bx = 0, bz = 0, best = 0;
        for (int z = 0; z < N - 1; z++) {
            for (int x = 0; x < N - 1; x++) {
                int d = Math.max(Math.abs(cache[z][x] - cache[z][x + 1]),
                    Math.abs(cache[z][x] - cache[z + 1][x]));
                if (d > best) { best = d; bx = x; bz = z; }
            }
        }
        System.out.printf("raw maxDelta=%d | cache maxDelta=%d | 悬崖@(%d,%d) cache=%d adjacent=%d%n",
            rawMaxD, cacheMaxD, bx, bz, cache[bz][bx],
            Math.max(cache[bz][bx + 1], cache[bz + 1][bx]));
        // 悬崖点上下文
        for (int dz = -2; dz <= 2; dz++) {
            int z = bz + dz;
            if (z < 0 || z >= N) continue;
            StringBuilder sb = new StringBuilder();
            for (int dx = -2; dx <= 2; dx++) {
                int x = bx + dx;
                if (x < 0 || x >= N) continue;
                sb.append(String.format("(%d,%d)r=%d c=%d  ", x, z, raw[z][x], cache[z][x]));
            }
            System.out.println(sb);
        }
        // 河流贡献：随机采样 1000 点比较 rawHeight 与"无河流"版本不可行（结构在内部），
        // 改为统计 raw 里相邻差>8 的位置数 vs cache
        int rawCliff = 0, cacheCliff = 0;
        for (int z = 0; z < N - 1; z++) {
            for (int x = 0; x < N - 1; x++) {
                if (Math.abs(raw[z][x] - raw[z][x + 1]) > 8) rawCliff++;
                if (Math.abs(raw[z][x] - raw[z + 1][x]) > 8) rawCliff++;
                if (Math.abs(cache[z][x] - cache[z][x + 1]) > 8) cacheCliff++;
                if (Math.abs(cache[z][x] - cache[z + 1][x]) > 8) cacheCliff++;
            }
        }
        System.out.printf(">8 悬崖边数: raw=%d cache=%d%n", rawCliff, cacheCliff);
    }

    static int maxDelta(int[][] h, int N) {
        int m = 0;
        for (int z = 0; z < N - 1; z++) {
            for (int x = 0; x < N - 1; x++) {
                m = Math.max(m, Math.abs(h[z][x] - h[z][x + 1]));
                m = Math.max(m, Math.abs(h[z][x] - h[z + 1][x]));
            }
        }
        return m;
    }
}
