import com.gtsn.terrain.noise.*;

/** T4 综合扫描：扫 (plateOffset, baseOffset, mountainOffset) 三元组，用 rawHeight 快速评估各窗口地理
 *  需求：w1 需深海(min<-30)+高峰(>400 点≥200)；origin 需高地(>300)+海(≤62 可走河)；w2 需低地 35-60%。
 *  输出满足条件的组合。 */
public class ProbeGeo2 {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        int[][] PLATES = {{-900,-630},{90,900},{0,0},{-400,200},{600,-400},{-1200,300},{300,800},{-600,-1200},
                          {1500,400},{-800,1500},{200,1500},{1500,-900},{1500,0},{-1500,-1500},{2500,500},{500,2500}};
        int[][] BASES = {{1500,0},{0,0},{-1500,0},{0,1500},{800,-800},{-800,800},{2000,1000},{1000,-1500}};
        int[][] MOUNTAINS = {{1000,0},{-500,0},{1500,500},{500,-800},{-1200,600},{0,0},{2000,0},{-2000,0},
                             {1500,-1500},{-1500,1500},{2500,0},{0,2500},{-2500,1000},{1000,2500}};
        int best = 0;
        for (int[] po : PLATES) for (int[] bo : BASES) for (int[] mo : MOUNTAINS) {
            cfg.plateOffsetX = po[0]; cfg.plateOffsetZ = po[1];
            cfg.baseOffsetX = bo[0]; cfg.baseOffsetZ = bo[1];
            cfg.mountainOffsetX = mo[0]; cfg.mountainOffsetZ = mo[1];
            // w1: 深海 + 高峰
            int w1min = Integer.MAX_VALUE, w1max = Integer.MIN_VALUE, w1over400 = 0, w1over300 = 0;
            for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                int h = Math.round(b.rawHeight(-1024 + x, z));
                if (h < w1min) w1min = h; if (h > w1max) w1max = h;
                if (h > 400) w1over400++; if (h > 300) w1over300++;
            }
            // origin: 高地>300 + 海<=62
            int oMax = Integer.MIN_VALUE, oHigh = 0, oSea = 0, oLand = 0, oLow = 0;
            for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                int h = Math.round(b.rawHeight(x, z));
                if (h > oMax) oMax = h;
                if (h > 300) oHigh++; if (h <= 62) oSea++;
                if (h > 62) { oLand++; if (h <= 140) oLow++; }
            }
            double oLowP = 100.0 * oLow / Math.max(1, oLand);
            // w2: 低地 35-60%
            int w2Land = 0, w2Low = 0;
            for (int z = 512; z < 768; z += 4) for (int x = 512; x < 768; x += 4) {
                int h = Math.round(b.rawHeight(x, z));
                if (h > 62) { w2Land++; if (h <= 140) w2Low++; }
            }
            double w2LowP = 100.0 * w2Low / Math.max(1, w2Land);
            int score = 0;
            if (w1min < -30 && w1over400 >= 200) score += 10;
            if (w1min < -30) score += 1;
            if (w1over400 >= 200) score += 3;
            if (w1over300 >= 400) score += 1;
            if (oHigh >= 200 && oSea >= 200) score += 10;
            if (oHigh >= 100) score += 2;
            if (oLowP >= 35 && oLowP <= 60) score += 2;
            if (w2LowP >= 35 && w2LowP <= 60) score += 2;
            if (score >= best && (w1min < -30 || w1over400 >= 200 || oHigh >= 100)) {
                best = score;
                System.out.printf("score=%2d p=(%5d,%4d) b=(%5d,%4d) m=(%5d,%4d) | w1[%4d,%4d] >400:%4d >300:%4d | oMax=%3d oHigh=%3d oSea=%3d oLow=%.0f%% | w2Low=%.0f%%%n",
                    score, po[0], po[1], bo[0], bo[1], mo[0], mo[1],
                    w1min, w1max, w1over400, w1over300, oMax, oHigh, oSea, oLowP, w2LowP);
            }
        }
    }
}
