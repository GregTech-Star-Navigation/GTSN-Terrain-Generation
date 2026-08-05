import com.gtsn.terrain.noise.*;

/** T4 精细扫描 v1：先扫 plate 偏移找 origin 海陆比 25-45%，再在候选内扫 mountain 偏移要 w1 高峰。 */
public class ProbeGeo4 {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        // 候选 plate 偏移（粗网格 300 步长）
        int[][] goodPlates = new int[128][];
        int gp = 0;
        for (int px = -2400; px <= 2400; px += 300) {
            for (int pz = -2400; pz <= 2400; pz += 300) {
                cfg.plateOffsetX = px; cfg.plateOffsetZ = pz;
                cfg.mountainOffsetX = 0; cfg.mountainOffsetZ = 0;
                int oLand = 0;
                for (int z = 0; z < 256; z += 8) for (int x = 0; x < 256; x += 8) {
                    if (b.rawHeight(x, z) > 62) oLand++;
                }
                double p = 100.0 * oLand / 1024;
                if (p >= 25 && p <= 45) { goodPlates[gp++] = new int[]{px, pz}; }
            }
        }
        System.out.printf("=== %d plate candidates with origin land 25-45%% ===%n", gp);
        for (int i = 0; i < gp; i++) {
            int[] po = goodPlates[i];
            // 在该 plate 下扫 mountain 偏移：w1 高峰 + origin 高地 + w2 低地
            int bestMox = 0, bestMoz = 0, bestScore = -1;
            String bestInfo = "";
            for (int mox = -3200; mox <= 3200; mox += 400) {
                for (int moz = -3200; moz <= 3200; moz += 400) {
                    cfg.plateOffsetX = po[0]; cfg.plateOffsetZ = po[1];
                    cfg.mountainOffsetX = mox; cfg.mountainOffsetZ = moz;
                    cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
                    int oHigh = 0, oSea = 0;
                    for (int z = 0; z < 256; z += 8) for (int x = 0; x < 256; x += 8) {
                        int h = Math.round(b.rawHeight(x, z));
                        if (h > 300) oHigh++; if (h <= 62) oSea++;
                    }
                    int w1over400 = 0;
                    for (int z = 0; z < 256; z += 8) for (int x = 0; x < 256; x += 8) {
                        if (b.rawHeight(-1024 + x, z) > 400) w1over400++;
                    }
                    int w2Land = 0, w2Low = 0;
                    for (int z = 512; z < 768; z += 8) for (int x = 512; x < 768; x += 8) {
                        int h = Math.round(b.rawHeight(x, z));
                        if (h > 62) { w2Land++; if (h <= 140) w2Low++; }
                    }
                    double w2LowP = 100.0 * w2Low / Math.max(1, w2Land);
                    int s = 0;
                    if (oHigh >= 50) s += 5;
                    if (oSea >= 50) s += 5;
                    if (w1over400 >= 50) s += 10; else if (w1over400 >= 25) s += 5;
                    if (w2LowP >= 35 && w2LowP <= 60) s += 5;
                    if (s > bestScore) { bestScore = s; bestMox = mox; bestMoz = moz;
                        bestInfo = String.format("oHigh=%d oSea=%d w1>400:%d w2Low=%.0f%%", oHigh, oSea, w1over400, w2LowP); }
                }
            }
            System.out.printf("plate=(%5d,%4d) best mountain=(%5d,%4d) score=%d | %s%n",
                po[0], po[1], bestMox, bestMoz, bestScore, bestInfo);
        }
    }
}
