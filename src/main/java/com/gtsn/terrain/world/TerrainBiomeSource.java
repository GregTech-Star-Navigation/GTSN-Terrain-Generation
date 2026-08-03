package com.gtsn.terrain.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GTSN 自写群系源（M1 骨架版）。
 *
 * 从 biome 注册表收集 minecraft + biomesoplenty 的群系，不依赖 TerraBlender
 * 的 MultiNoiseBiomeSource 注入机制（TerraBlender 只作为 BOP 加载依赖保留）。
 *
 * M1 阶段：getNoiseBiome 用位置哈希确定性分配群系（占位）。
 * 后续里程碑：替换为温度/湿度/高度噪声布局算法（ClimateSampler）。
 */
public class TerrainBiomeSource extends BiomeSource {

    public static final Codec<TerrainBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RegistryCodecs.homogeneousList(Registries.BIOME)
                            .fieldOf("biomes").forGetter((TerrainBiomeSource s) -> s.biomes)
            ).apply(instance, TerrainBiomeSource::new));

    /** 群系列表（codec 反序列化注入，JSON 中为资源列表） */
    private final HolderSet<Biome> biomes;

    public TerrainBiomeSource(HolderSet<Biome> biomes) {
        this.biomes = biomes;
    }

    /**
     * M1：从 registry 收集 minecraft + biomesoplenty 群系。
     * 注：world_preset 走 codec 反序列化（JSON 里已列明群系），此方法供代码内创建时使用。
     */
    public static TerrainBiomeSource fromRegistry(RegistryAccess registryAccess) {
        Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        List<Holder<Biome>> collected = biomeRegistry.holders()
                .filter(holder -> {
                    ResourceLocation id = holder.key().location();
                    return id.getNamespace().equals("minecraft") || id.getNamespace().equals("biomesoplenty");
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return new TerrainBiomeSource(HolderSet.direct(collected));
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        List<Holder<Biome>> list = biomes.stream().toList();
        if (list.isEmpty()) {
            return null; // 不应发生；空列表时由调用方兜底
        }
        // M1 占位：位置哈希 → 群系。后续替换为 ClimateSampler 布局算法。
        long hash = (x * 374761393L + z * 668265263L) ^ (x >> 3) * 0x9E3779B97F4A7C15L;
        hash = (hash ^ (hash >> 13)) * 0x85EBCA77L;
        hash = hash ^ (hash >> 16);
        int idx = (int) (Math.abs(hash) % list.size());
        return list.get(idx);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream();
    }
}
