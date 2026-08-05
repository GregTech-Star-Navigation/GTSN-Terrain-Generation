import com.gtsn.terrain.noise.*;

/** T4 扫描：全图找高峰区（>400）与深沟区（<-30），定位窗口偏移 */
public class ProbeScan {
    static long SEED = 20260803L;
    public static void main(String[] args) {
        TerrainConfig cfg = new TerrainConfig(SEED);
        HeightMapBuilder b = new HeightMapBuilder(cfg);
        // 粗扫 200 格步长覆盖 ±4000
        int bestPx=0, bestPz=0, bestPts=0;
        int deepX=0, deepZ=0, deepH=0;
        int[][] hot = new int[41][41];
        for (int gz = -40; gz <= 40; gz += 2) {
            for (int gx = -40; gx <= 40; gx += 2) {
                int x = gx * 100, z = gz * 100;
                int over400 = 0, minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
                for (int dz = 0; dz < 400; dz += 16) for (int dx = 0; dx < 400; dx += 16) {
                    int h = Math.round(b.rawHeight(x + dx, z + dz));
                    if (h > 400) over400++;
                    minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                }
                if (over400 > bestPts) { bestPts = over400; bestPx = x; bestPz = z; }
                if (minH < deepH) { deepH = minH; deepX = x; deepZ = z; }
                if (over400 > 0) hot[(gz+40)/2][(gx+40)/2] = Math.min(9, over400/4);
            }
        }
        System.out.printf("best peak region: @(%d,%d) pts>400=%d%n", bestPx, bestPz, bestPts);
        System.out.printf("deepest: @(%d,%d) min=%d%n", deepX, deepZ, deepH);
        // 打印热点地图（40x40 网格 = 4000x4000 范围）
        for (int gz = 0; gz < 41; gz++) {
            StringBuilder sb = new StringBuilder();
            for (int gx = 0; gx < 41; gx++) sb.append(hot[gz][gx] == 0 ? "." : hot[gz][gx]);
            System.out.println(sb.toString());
        }
    }
}
