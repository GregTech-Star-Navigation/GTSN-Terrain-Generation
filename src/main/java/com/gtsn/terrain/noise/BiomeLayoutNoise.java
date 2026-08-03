package com.gtsn.terrain.noise;

/**
 * 群系布局专用噪声（M3-A，纯 Java，零 Minecraft 依赖）。
 *
 * <p>独立种子偏移（seed+101 ~ seed+103），与 HeightMapBuilder 的 seed+1 ~ seed+6
 * 完全隔离，保证群系布局与地形高度互不串扰、可独立调参。
 *
 * <p>三个通道（输入输出均为方块坐标）：
 * <ul>
 *   <li>{@link #temperature(double, double)}：有效温度 [-1,1]，
 *       纬度效应（z 越大越冷，模拟南北极）+ 温度噪声扰动</li>
 *   <li>{@link #humidity(double, double, double)}：有效湿度 [-1,1]，
 *       大陆度越高越干（距海越远越干）+ 湿度噪声扰动</li>
 *   <li>{@link #elevationWeight(double, double)}：海拔高程权重 [-1,1]，
 *       供高度带选择做地形起伏偏置</li>
 * </ul>
 *
 * <p>全部方法为纯函数：同一种子同坐标结果恒定，线程安全。
 */
public class BiomeLayoutNoise {

    /** 纬度满程距离（方块）：|z| 达到该值即进入全冷/全热区 */
    private static final double LATITUDE_FULL_RANGE = 8000.0;

    /** 温度噪声扰动振幅（相对纬度效应的比例） */
    private static final double TEMPERATURE_NOISE_AMPLITUDE = 0.35;

    /** 大陆度 → 干燥度换算系数：continentalness = ±1 时湿度偏移 ±该值 */
    private static final double CONTINENTAL_DRYNESS = 0.6;

    private final FastNoiseLite temperatureNoise;
    private final FastNoiseLite humidityNoise;
    private final FastNoiseLite elevationNoise;

    public BiomeLayoutNoise(long seed) {
        this.temperatureNoise = noise(seed + 101, 0.0015f, 4);
        this.humidityNoise = noise(seed + 102, 0.0012f, 4);
        this.elevationNoise = noise(seed + 103, 0.003f, 3);
    }

    private static FastNoiseLite noise(long seed, float frequency, int octaves) {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFrequency(frequency);
        n.SetFractalOctaves(octaves);
        return n;
    }

    /**
     * 有效温度：纬度效应（z 越大越冷）+ 温度噪声扰动。
     *
     * @param x 世界 X 坐标（方块）
     * @param z 世界 Z 坐标（方块）
     * @return [-1, 1]，-1 极寒，+1 极热
     */
    public double temperature(double x, double z) {
        double latitude = -z / LATITUDE_FULL_RANGE;
        double noise = temperatureNoise.GetNoise((float) x, (float) z) * TEMPERATURE_NOISE_AMPLITUDE;
        return clamp(latitude + noise, -1.0, 1.0);
    }

    /**
     * 有效湿度：大陆度越高越干（简化：距海越远越干）+ 湿度噪声扰动。
     *
     * @param x              世界 X 坐标（方块）
     * @param z              世界 Z 坐标（方块）
     * @param continentalness 大陆度 [-1,1]（来自 HeightMapBuilder.continentalness）
     * @return [-1, 1]，-1 极干，+1 极湿
     */
    public double humidity(double x, double z, double continentalness) {
        double noise = humidityNoise.GetNoise((float) x, (float) z);
        return clamp(noise - continentalness * CONTINENTAL_DRYNESS, -1.0, 1.0);
    }

    /**
     * 海拔高程权重：地形起伏偏置，供高度带选择微调。
     *
     * @param x 世界 X 坐标（方块）
     * @param z 世界 Z 坐标（方块）
     * @return [-1, 1]
     */
    public double elevationWeight(double x, double z) {
        return elevationNoise.GetNoise((float) x, (float) z);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
