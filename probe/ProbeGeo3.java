import com.gtsn.terrain.noise.*;

/** T4 地理定位扫描 v2：要求 origin 海陆比 25-45%（S2）+ 高地>300 + 海 + w1 高峰 + w2 低地 35-60%。 */
public class ProbeGeo3 {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        int[][] PLATES = {{-600,-1200},{90,900},{0,0},{-400,200},{600,-400},{-1200,300},{300,800},{-600,-1200},
                          {-900,-630},{-1400,-400},{200,-1500},{1500,1500},{900,-900},{-2000,-2000},{-300,1200},{300,-300}};
        int[][] MOUNTAINS = {{-2500,1000},{1000,0},{-500,0},{1500,500},{500,-800},{-1200,600},{0,0},{2000,0},
                             {1500,-1500},{-1500,1500},{2500,0},{0,2500},{-2500,-1000},{1000,-2500},{-3000,500},{-500,-2500}};
        for (int[] po : PLATES) for (int[] mo : MOUNTAINS) {
            cfg.plateOffsetX = po[0]; cfg.plateOffsetZ = po[1];
            cfg.mountainOffsetX = mo[0]; cfg.mountainOffsetZ = mo[1];
            cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
            // origin: 海陆比 25-45% + 高地>300 + 海<=62
            int oLand = 0, oSea = 0, oHigh = 0, oLow = 0, oMax = Integer.MIN_VALUE;
            for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                int h = Math.round(b.rawHeight(x, z));
                if (h > 62) { oLand++; if (h <= 140) oLow++; } else oSea++;
                if (h > 300) oHigh++; if (h > oMax) oMax = h;
            }
            double oLandP = 100.0 * oLand / 4096;
            double oLowP = 100.0 * oLow / Math.max(1, oLand);
            // w1: 高峰>400
            int w1over400 = 0, w1over300 = 0, w1max = Integer.MIN_VALUE, w1min = Integer.MAX_VALUE;
            for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                int h = Math.round(b.rawHeight(-1024 + x, z));
                if (h > 400) w1over400++; if (h > 300) w1over300++;
                if (h > w1max) w1max = h; if (h < w1min) w1min = h;
            }
            // w2: 低地 35-60%
            int w2Land = 0, w2Low = 0;
            for (int z = 512; z < 768; z += 4) for (int x = 512; x < 768; x += 4) {
                int h = Math.round(b.rawHeight(x, z));
                if (h > 62) { w2Land++; if (h <= 140) w2Low++; }
            }
            double w2LowP = 100.0 * w2Low / Math.max(1, w2Land);
            // 打分
            int score = 0;
            if (oLandP >= 25 && oLandP <= 45) score += 20;
            else if (oLandP >= 20 && oLandP <= 50) score += 5;
            if (oHigh >= 100) score += 10;
            if (oHigh >= 50) score += 3;
            if (oLowP >= 35 && oLowP <= 60) score += 5;
            if (w1over400 >= 200) score += 10;
            else if (w1over400 >= 100) score += 5;
            if (w1over300 >= 400) score += 5;
            if (w2LowP >= 35 && w2LowP <= 60) score += 5;
            if (score >= 25) {
                System.out.printf("score=%2d p=(%5d,%4d) m=(%5d,%4d) | oLand=%.0f%% oHigh=%3d oLow=%.0f%% oMax=%3d | w1[%3d,%3d] >400:%4d >300:%4d | w2Low=%.0f%%%n",
                    score, po[0], po[1], mo[0], mo[1], oLandP, oHigh, oLowP, oMax, w1min, w1max, w1over400, w1over300, w2LowP);
            }
        }
    }
}
