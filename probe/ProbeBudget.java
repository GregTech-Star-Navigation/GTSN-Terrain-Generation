import com.gtsn.terrain.noise.*;

/** T4 关键诊断：S11 坡度预算下，山体允许的最大面积占比。
 *  当前理论：avg 8-block 坡度 <12°（diff<3.4 blocks/16）、steep30 <5%。
 *  若窗口 60% 海洋(0°) + 山体占 a% 面积平均 s°：avg = 0.6*0 + (0.4-a)*5 + a*s < 12。
 *  测量各窗口当前：海洋占比 + 山体面积占比（>250 视为山） + 山体平均坡度。 */
public class ProbeBudget {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        cfg.plateOffsetX = -1200f; cfg.plateOffsetZ = -1500f;
        cfg.mountainOffsetX = -2500f; cfg.mountainOffsetZ = 1000f;
        cfg.baseOffsetX = 1500f; cfg.baseOffsetZ = 0f;
        int[][] WINS = {{0,0},{-1024,0},{512,512}};
        for (int[] w : WINS) {
            int wx = w[0], wz = w[1];
            int G = 256, B = 8;
            int sea = 0, land = 0, mount = 0, low = 0;
            double slopeSum = 0, mtnSlopeSum = 0; int cnt = 0, mtnCnt = 0, steep30 = 0;
            int[][] h = new int[G][G];
            for (int z = 0; z < G; z++) for (int x = 0; x < G; x++) {
                h[z][x] = Math.round(b.rawHeight(wx+x, wz+z));
                if (h[z][x] <= 62) sea++; else { land++; if (h[z][x] <= 140) low++; if (h[z][x] > 250) mount++; }
            }
            for (int z = B; z < G-B; z++) for (int x = B; x < G-B; x++) {
                int dx = Math.abs(h[z][x+B]-h[z][x-B]);
                int dz = Math.abs(h[z+B][x]-h[z-B][x]);
                double deg = Math.toDegrees(Math.atan(Math.max(dx,dz)/16.0));
                slopeSum += deg; cnt++;
                if (deg > 30) steep30++;
                if (h[z][x] > 250) { mtnSlopeSum += deg; mtnCnt++; }
            }
            System.out.printf("win=(%d,%d) sea=%.1f%% land=%.1f%% | lowland=%.1f%% mount(>250)=%.1f%% | avgSlope=%.1f° mtnAvg=%.1f° steep30=%.2f%%%n",
                wx, wz, 100.0*sea/(G*G), 100.0*land/(G*G), 100.0*low/Math.max(1,land), 100.0*mount/Math.max(1,land),
                slopeSum/cnt, mtnCnt>0?mtnSlopeSum/mtnCnt:0, 100.0*steep30/cnt);
        }
    }
}
