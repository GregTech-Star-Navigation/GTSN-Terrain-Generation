package com.gtsn.terrain.noise;

import java.io.FileWriter;
import java.io.IOException;

/**
 * 高度图可视化导出工具（开发调试用，非模组功能）。
 *
 * 输出 PPM 格式（P6 二进制 PPM 无法用文本工具看，改用 P3 ASCII PPM），
 * 或输出 CSV。运行方式：java -cp <classes> com.gtsn.terrain.noise.HeightmapExporter <seed> <size> <out>
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

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260803L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        String out = args.length > 2 ? args[2] : "heightmap.ppm";

        HeightMapBuilder builder = new HeightMapBuilder(new TerrainConfig(seed));
        int[][] heights = new int[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                heights[z][x] = builder.getHeight(x, z);
            }
        }
        exportPPM(heights, size, out);
        System.out.println("Exported " + out + " size=" + size + "x" + size + " seed=" + seed);
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
