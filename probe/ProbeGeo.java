import com.gtsn.terrain.noise.*;

/** T4 地理定位扫描：扫 plate/base/mountain 偏移，找 w1(-1024,0) 深海+高峰 / origin 有高地 / w2 低地 35-60% 的组合。
 *  用 rawHeight（纯函数，快），只测地理分布，坡度/连续性由另一轮验证。 */
public class ProbeGeo {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        int[][] MOUNTAIN = {{1000,0},{-500,0},{1500,500},{500,-800},{-1200,600},{0,0},{2000,0},{-2000,0}};
        int[][] PLATE = {{-900,-630},{90,900},{0,0},{-400,200},{600,-400},{-1200,300},{300,800},{-600,-1200}};
        for (int[] mo : MOUNTAIN) {
            for (int[] po : PLATE) {
                cfg.mountainOffsetX = mo[0]; cfg.mountainOffsetZ = mo[1];
                cfg.plateOffsetX = po[0]; cfg.plateOffsetZ = po[1];
                // w1 = (-1024,0)
                int w1max = Integer.MIN_VALUE, w1min = Integer.MAX_VALUE, w1over400 = 0, w1deep = 0;
                for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                    int h = Math.round(b.rawHeight(-1024 + x, z));
                    if (h > w1max) w1max = h; if (h < w1min) w1min = h;
                    if (h > 400) w1over400++; if (h < -30) w1deep++;
                }
                // origin
                int oMax = Integer.MIN_VALUE, oLand = 0, oLow = 0;
                for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                    int h = Math.round(b.rawHeight(x, z));
                    if (h > oMax) oMax = h;
                    if (h > 62) { oLand++; if (h <= 140) oLow++; }
                }
                double oLowP = 100.0 * oLow / Math.max(1, oLand);
                // w2
                int w2Land = 0, w2Low = 0;
                for (int z = 512; z < 768; z += 4) for (int x = 512; x < 768; x += 4) {
                    int h = Math.round(b.rawHeight(x, z));
                    if (h > 62) { w2Land++; if (h <= 140) w2Low++; }
                }
                double w2LowP = 100.0 * w2Low / Math.max(1, w2Land);
                System.out.printf("m=(%5d,%4d) p=(%5d,%4d) | w1[%4d,%4d] over400=%4d deep=%3d | oMax=%3d oLow=%.0f%% | w2Low=%.0f%%%n",
                    mo[0], mo[1], po[0], po[1], w1min, w1max, w1over400, w1deep, oMax, oLowP, w2LowP);
            }
        }
    }
}
