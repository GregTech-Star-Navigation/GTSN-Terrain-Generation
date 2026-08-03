package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A：群系布局噪声 seam 契约测试。
 * 被测 seam：BiomeLayoutNoise.temperature / humidity / elevationWeight 公开接口。
 *
 * 三个 seam：
 *  T1 确定性：同一种子同一坐标两次采样结果一致
 *  T2 纬度效应：z 越大（越北）平均有效温度越低（模拟南北极）
 *  T3 大陆度干燥度：大陆度越低（海洋侧）平均有效湿度越高
 */
class BiomeLayoutNoiseTest {

    /** 固定测试种子（与 HeightMapBuilderTest 一致，便于跨类对照） */
    private static final long SEED = 20260803L;

    @Test
    void t1_sameSeedSameCoordsDeterministic() {
        BiomeLayoutNoise noise = new BiomeLayoutNoise(SEED);
        for (int i = 0; i < 256; i++) {
            int x = ((i * 37) % 251) - 125;
            int z = ((i * 91) % 251) - 125;
            assertEquals(noise.temperature(x, z), noise.temperature(x, z),
                "(" + x + "," + z + ") 温度两次采样不一致");
            assertEquals(noise.humidity(x, z, 0.2), noise.humidity(x, z, 0.2),
                "(" + x + "," + z + ") 湿度两次采样不一致");
            assertEquals(noise.elevationWeight(x, z), noise.elevationWeight(x, z),
                "(" + x + "," + z + ") 高程权重两次采样不一致");
        }
    }

    @Test
    void t2_temperatureDecreasesWithLatitude() {
        BiomeLayoutNoise noise = new BiomeLayoutNoise(SEED);
        double northSum = 0;
        double southSum = 0;
        int n = 0;
        // 南北各采 9×11 个点求均值，抵消噪声扰动、只保留纬度梯度
        for (int x = -1600; x <= 1600; x += 400) {
            for (int z = 3000; z <= 4000; z += 100) {
                northSum += noise.temperature(x, z);
                southSum += noise.temperature(x, -z);
                n++;
            }
        }
        double northMean = northSum / n;
        double southMean = southSum / n;
        assertTrue(northMean < southMean,
            "北方平均温度 " + northMean + " 应低于南方平均温度 " + southMean);
    }

    @Test
    void t3_humidityDecreasesWithContinentalness() {
        BiomeLayoutNoise noise = new BiomeLayoutNoise(SEED);
        double wetSum = 0;
        double drySum = 0;
        int n = 0;
        // 同一坐标仅改变大陆度输入：低大陆度（海洋侧）应显著更湿
        for (int x = -1000; x <= 1000; x += 200) {
            for (int z = -1000; z <= 1000; z += 200) {
                wetSum += noise.humidity(x, z, -0.8);
                drySum += noise.humidity(x, z, 0.8);
                n++;
            }
        }
        double wetMean = wetSum / n;
        double dryMean = drySum / n;
        assertTrue(wetMean - dryMean > 0.4,
            "低大陆度平均湿度 " + wetMean + " 应显著高于高大陆度平均湿度 " + dryMean);
    }
}
