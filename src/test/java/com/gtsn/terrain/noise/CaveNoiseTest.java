package com.gtsn.terrain.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-C：3D 洞穴噪声 seam 契约测试。
 * 被测 seam：CaveNoise.isCave(int x, int y, int z, int surfaceHeight) 公开接口。
 *
 * 四个 seam：
 *  C1 确定性：同一种子同一坐标同一地表高度，结果恒定（含跨实例）
 *  C2 海平面掩码：y &gt;= 海平面（63）恒不生成洞穴（防洞穴穿出海面/湖面）
 *  C3 深层可生成：存在 y=-30（低于地表）的坐标 isCave 为 true
 *  C4 地表安全：y = surfaceHeight-1 / surfaceHeight-3 处恒不生成洞穴（防地表穿帮）
 */
class CaveNoiseTest {

    /** 固定测试种子（与 HeightMapBuilderTest / BiomeLayoutNoiseTest 一致） */
    private static final long SEED = 20260803L;

    @Test
    void c1_sameSeedSamePositionDeterministic() {
        CaveNoise a = new CaveNoise(SEED);
        CaveNoise b = new CaveNoise(SEED);
        for (int i = 0; i < 256; i++) {
            int x = ((i * 37) % 251) - 125;
            int y = ((i * 53) % 251) - 125;
            int z = ((i * 91) % 251) - 125;
            int surface = 40 + ((i * 7) % 60);
            boolean first = a.isCave(x, y, z, surface);
            assertEquals(first, a.isCave(x, y, z, surface),
                "同实例 (" + x + "," + y + "," + z + ") 地表 " + surface + " 两次结果不一致: " + first);
            assertEquals(first, b.isCave(x, y, z, surface),
                "跨实例 (" + x + "," + y + "," + z + ") 地表 " + surface + " 结果不一致: " + first);
        }
    }

    /** C2 海平面掩码：y >= 63（含边界与更高处）恒 false，即使地表高达 120（模拟山体内部） */
    @Test
    void c2_noCaveAtOrAboveSeaLevel() {
        CaveNoise noise = new CaveNoise(SEED);
        for (int surface : new int[] { 100, 120 }) {
            for (int y = CaveNoise.SEA_LEVEL; y <= 80; y++) {
                for (int z = 0; z < 32; z += 4) {
                    for (int x = 0; x < 32; x += 4) {
                        assertFalse(noise.isCave(x, y, z, surface),
                            "(" + x + "," + y + "," + z + ") 地表 " + surface + "：海平面以上不应生成洞穴");
                    }
                }
            }
        }
    }

    /** C3 深层可生成：y=-30（远低于地表 64）存在洞穴坐标 */
    @Test
    void c3_cavesExistDeepUnderground() {
        CaveNoise noise = new CaveNoise(SEED);
        boolean found = false;
        outer:
        for (int x = 0; x < 128; x += 2) {
            for (int z = 0; z < 128; z += 2) {
                if (noise.isCave(x, -30, z, 64)) {
                    found = true;
                    break outer;
                }
            }
        }
        assertTrue(found, "y=-30 深层（地表 64）未找到任何洞穴坐标");
    }

    /** C4 地表安全：地表下方 1 格与安全深度边界（surfaceHeight-3）恒不生成洞穴 */
    @Test
    void c4_noCaveNearSurface() {
        CaveNoise noise = new CaveNoise(SEED);
        int[] surfaces = { 40, 64, 90 };
        for (int surface : surfaces) {
            for (int z = 0; z < 64; z++) {
                for (int x = 0; x < 64; x++) {
                    assertFalse(noise.isCave(x, surface - 1, z, surface),
                        "(" + x + "," + (surface - 1) + "," + z + ") 地表 " + surface + "：贴近地表处不应生成洞穴");
                    assertFalse(noise.isCave(x, surface - 3, z, surface),
                        "(" + x + "," + (surface - 3) + "," + z + ") 地表 " + surface + "：安全深度边界内不应生成洞穴");
                }
            }
        }
    }
}
