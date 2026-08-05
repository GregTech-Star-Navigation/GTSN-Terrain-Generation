import com.gtsn.terrain.noise.*;

/** T4 诊断：w1(-1024,0) 为什么永远没山——检查 c 分布 + 蒙版值 + 门控 */
public class ProbeW1 {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        // 用反射拿不到私有噪声，改走 rawHeight 反推 + c 分布
        int[][] WINS = {{-1024,0},{0,0},{512,512}};
        for (int[] w : WINS) {
            int wx = w[0], wz = w[1];
            double cSum = 0, cPos = 0, cNeg = 0; int n = 0;
            int cHi = 0; // c > 0.7 的比例（全强度山）
            int cMid = 0; // c in (0.35, 0.7]
            int cLow = 0; // c in (0, 0.35]
            int hMax = Integer.MIN_VALUE, hMin = Integer.MAX_VALUE;
            int over400 = 0, over300 = 0;
            for (int z = 0; z < 256; z += 4) for (int x = 0; x < 256; x += 4) {
                double c = b.continentalness(wx + x, wz + z);
                cSum += c; n++;
                if (c > 0) cPos++; else cNeg++;
                if (c > 0.7) cHi++; else if (c > 0.35) cMid++; else if (c > 0) cLow++;
                int h = Math.round(b.rawHeight(wx + x, wz + z));
                if (h > hMax) hMax = h; if (h < hMin) hMin = h;
                if (h > 400) over400++; if (h > 300) over300++;
            }
            System.out.printf("win=(%d,%d) cAvg=%.3f pos=%.1f%% | cHi=%.1f%% cMid=%.1f%% cLow=%.1f%% neg=%.1f%% | h[%d,%d] >400:%d >300:%d%n",
                wx, wz, cSum/n, 100.0*cPos/n, 100.0*cHi/n, 100.0*cMid/n, 100.0*cLow/n, 100.0*cNeg/n,
                hMin, hMax, over400, over300);
        }
    }
}
