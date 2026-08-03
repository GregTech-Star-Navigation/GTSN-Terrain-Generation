package com.gtsn.terrain.world;

import com.gtsn.terrain.noise.BiomeLayoutNoise;
import com.gtsn.terrain.noise.HeightMapBuilder;
import com.gtsn.terrain.noise.TerrainConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A：TerrainBiomeSource 群系源行为测试（world 包，需要 Minecraft 类）。
 *
 * <p>⚠ 本类全部测试默认 {@link org.junit.jupiter.api.Disabled}（M3-A 交付时跳过）：
 * 纯 JUnit 的 `gradlew test` 任务无法完整引导 Forge 运行时——Bootstrap.bootStrap()
 * 内部 Forge 注入的 NetworkHooks.init 需要 eventbus 类变换（生成事件类的无参构造，
 * 报 NoSuchMethodException: NetworkEvent.&lt;init&gt;），该变换只在 modlauncher 启动
 * （runClient / gameTestServer）时生效。噪声包的 BiomeLayoutNoiseTest 是 M3-A 的主测试。
 *
 * <p>纯 JUnit 环境下通过 standalone {@link Holder.Reference}（带 ResourceKey）直接构造
 * HolderSet&lt;Biome&gt;，不依赖游戏注册表；getNoiseBiome 的 Climate.Sampler 参数
 * 传 null（实现不读取 sampler）。
 *
 * <p>四个 seam（在具备完整启动环境的 test 任务中启用）：
 *  W1 空群系列表 → getNoiseBiome 返回 null（M1 契约保持）
 *  W2 确定性：同一 QuartPos 两次选择一致
 *  W3 海陆映射：海洋坐标（isLand=false）选出 ocean 类群系
 *  W4 海陆映射：陆地坐标（isLand=true）不选出 ocean 类群系
 */
class TerrainBiomeSourceTest {

    private static final long SEED = 20260803L;

    /**
     * 启用本类测试前需先引导 Minecraft 静态注册表（否则 BiomeSource / Registries
     * 的静态初始化抛 "Not bootstrapped"），在具备完整 Forge 启动环境的 test 任务中：
     * <pre>
     * SharedConstants.setVersion(DetectedVersion.BUILT_IN);
     * Bootstrap.bootStrap();
     * </pre>
     */

    private static Biome biome(float temp, float downfall) {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(temp)
                .downfall(downfall)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .waterColor(4159204)
                        .waterFogColor(329011)
                        .fogColor(12638463)
                        .skyColor(12638463)
                        .build())
                .mobSpawnSettings(new MobSpawnSettings.Builder().build())
                .generationSettings(new BiomeGenerationSettings.PlainBuilder().build())
                .build();
    }

    private static Holder<Biome> holder(String id, float temp, float downfall) {
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, new ResourceLocation(id));
        Holder.Reference<Biome> ref = Holder.Reference.createStandAlone(new HolderOwner<>() {}, key);
        ref.bindValue(biome(temp, downfall));
        return ref;
    }

    private static Holder<Biome>[] vanillaTen() {
        // 与 world_preset gtsn.json 中的 10 个原版群系一一对应
        return new Holder[] {
                holder("minecraft:plains", 0.8f, 0.4f),
                holder("minecraft:forest", 0.7f, 0.8f),
                holder("minecraft:desert", 2.0f, 0.0f),
                holder("minecraft:mountains", 0.2f, 0.3f),
                holder("minecraft:snowy_plains", 0.0f, 0.5f),
                holder("minecraft:ocean", 0.5f, 0.5f),
                holder("minecraft:deep_ocean", 0.5f, 0.5f),
                holder("minecraft:swamp", 0.8f, 0.9f),
                holder("minecraft:taiga", 0.25f, 0.8f),
                holder("minecraft:jungle", 0.95f, 0.9f)
        };
    }

    private static TerrainBiomeSource newSource(Holder<Biome>... holders) {
        HeightMapBuilder hmb = new HeightMapBuilder(new TerrainConfig(SEED));
        BiomeLayoutNoise bln = new BiomeLayoutNoise(SEED);
        return new TerrainBiomeSource(HolderSet.direct(List.of(holders)), hmb, bln);
    }

    @Test
    @Disabled("纯 JUnit test 任务无法引导 Forge 运行时（见类注释），需在完整启动环境运行")
    void w1_emptyBiomeListReturnsNull() {
        TerrainBiomeSource src = new TerrainBiomeSource(
                HolderSet.direct(List.of()),
                new HeightMapBuilder(new TerrainConfig(SEED)),
                new BiomeLayoutNoise(SEED));
        assertNull(src.getNoiseBiome(0, 0, 0, null),
            "空群系列表应返回 null（M1 契约）");
    }

    @Test
    @Disabled("纯 JUnit test 任务无法引导 Forge 运行时（见类注释），需在完整启动环境运行")
    void w2_sameQuartPosDeterministic() {
        TerrainBiomeSource src = newSource(vanillaTen());
        for (int qz = 0; qz < 8; qz++) {
            for (int qx = 0; qx < 8; qx++) {
                Holder<Biome> a = src.getNoiseBiome(qx, 0, qz, null);
                Holder<Biome> b = src.getNoiseBiome(qx, 0, qz, null);
                assertEquals(a, b, "QuartPos (" + qx + "," + qz + ") 两次选择不一致");
            }
        }
    }

    @Test
    @Disabled("纯 JUnit test 任务无法引导 Forge 运行时（见类注释），需在完整启动环境运行")
    void w3_seaQuartPosReturnsOceanBiome() {
        HeightMapBuilder hmb = new HeightMapBuilder(new TerrainConfig(SEED));
        int seaX = Integer.MIN_VALUE;
        int seaZ = 0;
        for (int x = -400; x < 400 && seaX == Integer.MIN_VALUE; x += 4) {
            for (int z = -400; z < 400 && seaX == Integer.MIN_VALUE; z += 4) {
                if (!hmb.isLand(x, z) && hmb.getHeight(x, z) < 40) {
                    seaX = x;
                    seaZ = z;
                }
            }
        }
        assertTrue(seaX != Integer.MIN_VALUE, "未在 [-400,400) 找到海洋采样点");
        TerrainBiomeSource src = newSource(vanillaTen());
        Holder<Biome> h = src.getNoiseBiome(seaX / 4, 0, seaZ / 4, null);
        String id = h.unwrapKey().map(k -> k.location().toString()).orElse("?");
        assertTrue(id.contains("ocean"),
            "海洋坐标 (" + seaX + "," + seaZ + ") 应选出 ocean 类群系，实际 " + id);
    }

    @Test
    @Disabled("纯 JUnit test 任务无法引导 Forge 运行时（见类注释），需在完整启动环境运行")
    void w4_landQuartPosNotOceanBiome() {
        HeightMapBuilder hmb = new HeightMapBuilder(new TerrainConfig(SEED));
        int landX = Integer.MIN_VALUE;
        int landZ = 0;
        for (int x = -400; x < 400 && landX == Integer.MIN_VALUE; x += 4) {
            for (int z = -400; z < 400 && landX == Integer.MIN_VALUE; z += 4) {
                if (hmb.isLand(x, z)) {
                    landX = x;
                    landZ = z;
                }
            }
        }
        assertTrue(landX != Integer.MIN_VALUE, "未在 [-400,400) 找到陆地采样点");
        TerrainBiomeSource src = newSource(vanillaTen());
        Holder<Biome> h = src.getNoiseBiome(landX / 4, 0, landZ / 4, null);
        String id = h.unwrapKey().map(k -> k.location().toString()).orElse("?");
        assertTrue(!id.contains("ocean"),
            "陆地坐标 (" + landX + "," + landZ + ") 不应选出 ocean 类群系，实际 " + id);
    }
}
