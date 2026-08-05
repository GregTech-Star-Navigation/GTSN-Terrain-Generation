import com.gtsn.terrain.noise.*;

/** T4 蒙版分布诊断：各窗口 mask01 分布（决定山体面积占比 → S11 steep30 预算）*/
public class ProbeMask3 {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        cfg.mountainOffsetX = -2500f; cfg.mountainOffsetZ = 1000f;
        int[][] WINS = {{0,0},{-1024,0},{512,512}};
        for (int[] w : WINS) {
            int wx = w[0], wz = w[1];
            // 反射调 mask01 太麻烦，直接用 rawHeight 反推：山体贡献 = raw - base - band
            // 简化：统计 raw 高度在 (base 之上) 的分布
            int[] h = new int[256*256];
            for (int z = 0; z < 256; z += 2) for (int x = 0; x < 256; x += 2) {
                h[z*256+x] = Math.round(b.rawHeight(wx+x, wz+z));
            }
            // 高度直方图分档
            int[] bins = new int[7]; // <62, 62-140, 140-250, 250-350, 350-400, 400-450, >450
            int land = 0, low = 0, alp = 0;
            for (int z = 0; z < 256; z += 2) for (int x = 0; x < 256; x += 2) {
                int v = h[z*256+x];
                if (v < 62) bins[0]++;
                else if (v <= 140) { bins[1]++; land++; low++; }
                else if (v <= 250) { bins[2]++; land++; }
                else if (v <= 350) { bins[3]++; land++; }
                else if (v <= 400) { bins[4]++; land++; }
                else if (v <= 450) { bins[5]++; land++; alp++; }
                else { bins[6]++; land++; alp++; }
            }
            System.out.printf("win=(%d,%d) land=%.1f%% | h<62:%.1f%% 62-140:%.1f%% 140-250:%.1f%% 250-350:%.1f%% 350-400:%.1f%% 400-450:%.1f%% >450:%.1f%% | lowland=%.1f%% alpine=%.1f%%%n",
                wx, wz, 100.0*land/(16384),
                100.0*bins[0]/16384, 100.0*bins[1]/16384, 100.0*bins[2]/16384, 100.0*bins[3]/16384,
                100.0*bins[4]/16384, 100.0*bins[5]/16384, 100.0*bins[6]/16384,
                100.0*low/Math.max(1,land), 100.0*alp/Math.max(1,land));
        }
    }
}
