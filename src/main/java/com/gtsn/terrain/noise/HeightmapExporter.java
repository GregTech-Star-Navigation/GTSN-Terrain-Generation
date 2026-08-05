package com.gtsn.terrain.noise;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 高度图可视化导出工具（开发调试用，非模组功能）。
 *
 * 两种模式：
 * <ul>
 *   <li>PPM 模式（默认，向后兼容）：<code>java -cp &lt;classes&gt; com.gtsn.terrain.noise.HeightmapExporter
 *       &lt;seed&gt; &lt;size&gt; &lt;out.ppm&gt;</code>，单窗口 @(0,0)。P3 ASCII PPM。</li>
 *   <li>PNG 模式：<code>... &lt;seed&gt; &lt;size&gt; &lt;outdir&gt; png</code>，
 *       导出 3 个验收窗口 @(0,0)/@(-1024,0)/@(512,512) 的 256×256 高度图 PNG
 *       到 outdir（不存在则创建），文件名为 window_&lt;x&gt;_&lt;z&gt;.png。</li>
 * </ul>
 *
 * 色带映射（由低到高）：
 *   <0    : 深海 深蓝
 *   0-30  : 浅海 蓝
 *   31-62 : 海岸 浅蓝
 *   63-110: 平原 绿
 *   111-180: 丘陵 黄绿
 *   181-300: 山地 棕
 *   >300  : 雪线 白
 */
public class HeightmapExporter {

    /** 三个验收窗口（与 HeightmapAnalyzer.WINDOWS 口径一致） */
    private static final int[][] M6_WINDOWS = {{0, 0}, {-1024, 0}, {512, 512}};

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260803L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        String out = args.length > 2 ? args[2] : "heightmap.ppm";
        boolean pngMode = args.length > 3 && "png".equalsIgnoreCase(args[3]);

        if (pngMode) {
            File dir = new File(out);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + dir.getAbsolutePath());
            }
            for (int[] win : M6_WINDOWS) {
                int[][] heights = sample(seed, size, win[0], win[1]);
                File f = new File(dir, String.format("window_%d_%d.png", win[0], win[1]));
                exportPNG(heights, size, f);
                System.out.println("Exported " + f.getAbsolutePath()
                    + " size=" + size + "x" + size + " seed=" + seed
                    + " win=(" + win[0] + "," + win[1] + ")");
            }
        } else {
            int[][] heights = sample(seed, size, 0, 0);
            exportPPM(heights, size, out);
            System.out.println("Exported " + out + " size=" + size + "x" + size + " seed=" + seed);
        }
    }

    /** 采样 size×size 网格，坐标自 (winX, winZ) 起（行优先：heights[z][x]） */
    private static int[][] sample(long seed, int size, int winX, int winZ) {
        HeightMapBuilder builder = new HeightMapBuilder(new TerrainConfig(seed));
        int[][] heights = new int[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                heights[z][x] = builder.getHeight(winX + x, winZ + z);
            }
        }
        return heights;
    }

    /** 导出 PNG（色带同 {@link #color(int)}） */
    static void exportPNG(int[][] heights, int size, File out) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int[] rgb = color(heights[z][x]);
                img.setRGB(x, z, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
            }
        }
        ImageIO.write(img, "png", out);
    }

    static void exportPPM(int[][] heights, int size, String out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("P3\n").append(size).append(' ').append(size).append("\n255\n");
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int h = heights[z][x];
                int[] rgb = color(h);
                sb.append(rgb[0]).append(' ').append(rgb[1]).append(' ').append(rgb[2]).append(' ');
            }
            sb.append('\n');
        }
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(sb.toString());
        }
    }

    static int[] color(int h) {
        if (h < 0) return new int[]{10, 10, 90};       // 深海
        if (h < 30) return new int[]{30, 60, 160};     // 浅海
        if (h < 62) return new int[]{90, 140, 200};    // 海岸
        if (h < 110) return new int[]{70, 170, 60};    // 平原
        if (h < 180) return new int[]{160, 190, 60};   // 丘陵
        if (h < 300) return new int[]{140, 100, 60};   // 山地
        return new int[]{240, 240, 240};               // 雪线
    }
}
