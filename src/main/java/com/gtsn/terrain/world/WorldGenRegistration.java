package com.gtsn.terrain.world;

import com.gtsn.terrain.GtsnTerrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * GTSN 世界生成组件注册。
 *
 * 1.20.1 注册方式：DeferredRegister 挂到 vanilla registry
 * （Registries.CHUNK_GENERATOR / Registries.BIOME_SOURCE），
 * element 类型为 Codec（注意：MapCodec 是 1.20.2+ 的写法）。
 */
public final class WorldGenRegistration {

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, GtsnTerrain.MOD_ID);

    public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, GtsnTerrain.MOD_ID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> TERRAIN_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("terrain", () -> TerrainChunkGenerator.CODEC);

    public static final RegistryObject<Codec<? extends BiomeSource>> TERRAIN_BIOME_SOURCE =
            BIOME_SOURCES.register("terrain_biomes", () -> TerrainBiomeSource.CODEC);

    private WorldGenRegistration() {
    }

    public static void register(IEventBus modEventBus) {
        CHUNK_GENERATORS.register(modEventBus);
        BIOME_SOURCES.register(modEventBus);
    }
}
