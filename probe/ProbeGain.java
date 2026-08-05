import com.gtsn.terrain.noise.*;

/** T4 增益扫描：rawHeight 快速评估各窗口坡度（S11 预算）与峰值分布（S9/S5 需求）。
 *  目标：avg 8-block 坡度 <12°（raw 预算，侵蚀只会更平滑）、部分点 >400（S9）。
 *  输出满足坡度预算的增益组合及其峰值。 */
public class ProbeGain {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        cfg.plateOffsetX = -600f; cfg.plateOffsetZ = -1200f;
        cfg.mountainOffsetX = -2500f; cfg.mountainOffsetZ = 1000f;
        cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
        // 候选：baseElevationGain × massifGain × ridgeGain（chainGain=60 final）
        float[][] combos = {
            {150, 200, 30},
            {150, 250, 30},
            {180, 220, 25},
            {180, 260, 28},
            {120, 260, 35},
            {150, 280, 25},
            {200, 220, 22},
            {150, 240, 20},
            {180, 300, 30},
            {150, 320, 28},
            {220, 240, 20},
            {200, 280, 25},
        };
        for (float[] c : combos) {
            cfg.baseElevationGain = c[0];
            cfg.massifGain = c[1];
            cfg.ridgeGain = c[2];
            double[] avgSlope = new double[3];
            int[] over400 = new int[3];
            int[] over300 = new int[3];
            int[] hMax = new int[3];
            int[][] WINS = {{0,0},{-1024,0},{512,512}};
            for (int w = 0; w < 3; w++) {
                int wx = WINS[w][0], wz = WINS[w][1];
                int G = 256, B = 8;
                double sum = 0; int cnt = 0;
                for (int z = B; z < G - B; z += 2) for (int x = B; x < G - B; x += 2) {
                    int dx = Math.abs(Math.round(b.rawHeight(wx+x+8, wz+z)) - Math.round(b.rawHeight(wx+x-8, wz+z)));
                    int dz = Math.abs(Math.round(b.rawHeight(wx+x, wz+z+8)) - Math.round(b.rawHeight(wx+x, wz+z-8)));
                    double deg = Math.toDegrees(Math.atan(Math.max(dx, dz) / 16.0));
                    sum += deg; cnt++;
                }
                avgSlope[w] = sum / cnt;
                for (int z = 0; z < G; z += 2) for (int x = 0; x < G; x += 2) {
                    int h = Math.round(b.rawHeight(wx+x, wz+z));
                    if (h > 400) over400[w]++;
                    if (h > 300) over300[w]++;
                    hMax[w] = Math.max(hMax[w], h);
                }
            }
            System.out.printf("base=%3.0f massif=%3.0f ridge=%3.0f | slope[%.1f %.1f %.1f] | >400[%d %d %d] >300[%d %d %d] max[%d %d %d]%n",
                c[0], c[1], c[2],
                avgSlope[0], avgSlope[1], avgSlope[2],
                over400[0], over400[1], over400[2],
                over300[0], over300[1], over300[2],
                hMax[0], hMax[1], hMax[2]);
        }
    }
}
