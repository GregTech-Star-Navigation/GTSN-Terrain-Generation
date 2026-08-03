package com.gtsn.terrain.world;

import com.gtsn.terrain.noise.BiomeLayoutNoise;
import com.gtsn.terrain.noise.HeightMapBuilder;
import com.gtsn.terrain.noise.TerrainConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GTSN 自写群系源（M3-A：真实群系布局版）。
 *
 * <p>布局管线（输入 QuartPos，先 ×4 还原为方块坐标再查询高度图/噪声）：
 * <ol>
 *   <li>温度：{@link BiomeLayoutNoise#temperature} —— 纬度效应（z 越大越冷，模拟南北极）+ 温度噪声扰动</li>
 *   <li>海陆：{@link HeightMapBuilder#isLand} —— 与 getHeight 共用大陆度噪声（s &lt;= kink 为海）</li>
 *   <li>湿度：{@link BiomeLayoutNoise#humidity} —— 大陆度越高越干 + 湿度噪声扰动</li>
 *   <li>高度：getHeight 地表高度 + 高程权重偏置 → 高度带（海洋/海岸/平原/丘陵/山地/雪线）</li>
 *   <li>映射：温度带(冷/温/热) × 湿度带(干/湿) × 高度带 → 从 biomes 列表中挑选；
 *       列表缺项沿回退链取最近似，最终回退 plains / 列表首个</li>
 * </ol>
 *
 * <p>codec 兼容 M1：JSON 只需 "biomes"；"seed" 为可选字段（默认 0），
 * 用于重建高度图/布局噪声实例（BiomeSource 解码时拿不到世界种子，
 * 故种子显式入 codec，未指定时同一预设所有世界共享同一地形种子）。
 */
public class TerrainBiomeSource extends BiomeSource {

    public static final Codec<TerrainBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RegistryCodecs.homogeneousList(Registries.BIOME)
                            .fieldOf("biomes").forGetter((TerrainBiomeSource s) -> s.biomes),
                    Codec.LONG.optionalFieldOf("seed", 0L)
                            .forGetter((TerrainBiomeSource s) -> s.seed)
            ).apply(instance, TerrainBiomeSource::new));

    // ---------------- 布局常量 ----------------

    /** 温度带切分阈值（有效温度 [-1,1]） */
    private static final double TEMP_BAND_SPLIT = 1.0 / 3.0;
    /** 湿度带切分阈值（有效湿度 [-1,1]） */
    private static final double HUMID_BAND_SPLIT = 0.0;
    /** 高度带上界（方块）：海洋 &lt;62、海岸 62-70、平原 70-110、丘陵 110-180、山地 180-300、雪线 &gt;300 */
    private static final double COAST_BAND_TOP = 70;
    private static final double PLAINS_BAND_TOP = 110;
    private static final double HILLS_BAND_TOP = 180;
    private static final double MOUNTAINS_BAND_TOP = 300;
    /** 深海洋判定：地表高度低于该值视为深海 */
    private static final int DEEP_OCEAN_DEPTH = 30;
    /** 高程权重 → 高度带偏置振幅（方块） */
    private static final double ELEVATION_BIAS = 60.0;

    // ---------------- 群系 ID（只作查找键，实际返回必来自 biomes 列表） ----------------

    private static final ResourceLocation ID_PLAINS = new ResourceLocation("minecraft", "plains");
    private static final ResourceLocation ID_FOREST = new ResourceLocation("minecraft", "forest");
    private static final ResourceLocation ID_DESERT = new ResourceLocation("minecraft", "desert");
    private static final ResourceLocation ID_MOUNTAINS = new ResourceLocation("minecraft", "mountains");
    private static final ResourceLocation ID_SNOWY_PLAINS = new ResourceLocation("minecraft", "snowy_plains");
    private static final ResourceLocation ID_OCEAN = new ResourceLocation("minecraft", "ocean");
    private static final ResourceLocation ID_DEEP_OCEAN = new ResourceLocation("minecraft", "deep_ocean");
    private static final ResourceLocation ID_SWAMP = new ResourceLocation("minecraft", "swamp");
    private static final ResourceLocation ID_TAIGA = new ResourceLocation("minecraft", "taiga");
    private static final ResourceLocation ID_JUNGLE = new ResourceLocation("minecraft", "jungle");

    /** 群系列表（codec 反序列化注入，JSON 中为资源列表） */
    private final HolderSet<Biome> biomes;
    /** 种子（仅用于重建噪声实例；注入构造时恒为 0） */
    private final long seed;
    /** 高度图（海陆判定 + 地表高度），与 M3-B 接入 TerrainChunkGenerator 的实例同源 */
    private final HeightMapBuilder heightMapBuilder;
    /** 群系布局噪声（温度/湿度/高程权重） */
    private final BiomeLayoutNoise layoutNoise;
    /** 群系列表索引：id → Holder（列表内成员才可被选中） */
    private final Map<ResourceLocation, Holder<Biome>> byId;

    /** M1 兼容构造：默认种子 0 */
    public TerrainBiomeSource(HolderSet<Biome> biomes) {
        this(biomes, 0L);
    }

    /** codec 构造：用种子重建高度图与布局噪声 */
    public TerrainBiomeSource(HolderSet<Biome> biomes, long seed) {
        this(biomes, seed, new HeightMapBuilder(new TerrainConfig(seed)), new BiomeLayoutNoise(seed));
    }

    /** 注入构造：外部传入已建好的噪声实例（测试 / 代码内创建用） */
    public TerrainBiomeSource(HolderSet<Biome> biomes, HeightMapBuilder heightMapBuilder, BiomeLayoutNoise layoutNoise) {
        this(biomes, 0L, heightMapBuilder, layoutNoise);
    }

    private TerrainBiomeSource(HolderSet<Biome> biomes, long seed,
                               HeightMapBuilder heightMapBuilder, BiomeLayoutNoise layoutNoise) {
        this.biomes = biomes;
        this.seed = seed;
        this.heightMapBuilder = heightMapBuilder;
        this.layoutNoise = layoutNoise;
        Map<ResourceLocation, Holder<Biome>> map = new LinkedHashMap<>();
        for (Holder<Biome> holder : biomes) {
            holder.unwrapKey().ifPresent(key -> map.putIfAbsent(key.location(), holder));
        }
        this.byId = Map.copyOf(map);
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
        if (biomes.size() == 0) {
            return null; // 空列表契约保持（调用方兜底）
        }
        // QuartPos → 方块坐标：高度图/噪声都以方块坐标采样
        int wx = x * 4;
        int wz = z * 4;

        // 1. 温度：纬度效应 + 温度噪声
        double temp = layoutNoise.temperature(wx, wz);
        // 2. 海陆 + 大陆度（湿度计算参考）
        boolean land = heightMapBuilder.isLand(wx, wz);
        double continentalness = heightMapBuilder.continentalness(wx, wz);
        // 3. 湿度：大陆度越高越干 + 湿度噪声
        double humid = layoutNoise.humidity(wx, wz, continentalness);
        // 4. 高度带：地表高度 + 高程权重偏置
        double bandHeight = heightMapBuilder.getHeight(wx, wz)
                + layoutNoise.elevationWeight(wx, wz) * ELEVATION_BIAS;

        TempBand tempBand = temp < -TEMP_BAND_SPLIT ? TempBand.COLD
                : (temp > TEMP_BAND_SPLIT ? TempBand.HOT : TempBand.TEMPERATE);
        HumidBand humidBand = humid < HUMID_BAND_SPLIT ? HumidBand.DRY : HumidBand.WET;
        HeightBand heightBand;
        boolean deepOcean = false;
        if (!land) {
            heightBand = HeightBand.OCEAN;
            deepOcean = heightMapBuilder.getHeight(wx, wz) < DEEP_OCEAN_DEPTH;
        } else {
            heightBand = bandOf(bandHeight);
        }

        // 5. 映射表挑选 + 回退链
        for (ResourceLocation id : chainFor(tempBand, humidBand, heightBand, deepOcean)) {
            Holder<Biome> found = byId.get(id);
            if (found != null) {
                return found;
            }
        }
        Holder<Biome> plains = byId.get(ID_PLAINS);
        if (plains != null) {
            return plains;
        }
        return biomes.iterator().next();
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream();
    }

    // ---------------- 高度带 / 映射表 ----------------

    private enum TempBand { COLD, TEMPERATE, HOT }

    private enum HumidBand { DRY, WET }

    private enum HeightBand { OCEAN, COAST, PLAINS, HILLS, MOUNTAINS, SNOWLINE }

    private static HeightBand bandOf(double h) {
        if (h < COAST_BAND_TOP) {
            return HeightBand.COAST;
        }
        if (h < PLAINS_BAND_TOP) {
            return HeightBand.PLAINS;
        }
        if (h < HILLS_BAND_TOP) {
            return HeightBand.HILLS;
        }
        if (h < MOUNTAINS_BAND_TOP) {
            return HeightBand.MOUNTAINS;
        }
        return HeightBand.SNOWLINE;
    }

    /**
     * 映射表：温度带 × 湿度带 × 高度带 → 群系选择链（首选在前，越靠后越"近似"）。
     * 链中每个 ID 都可能不在 biomes 列表里——getNoiseBiome 按序查找，
     * 全部缺失时最终回退 plains / 列表首个，保证永不返回列表外的群系。
     */
    private static List<ResourceLocation> chainFor(TempBand temp, HumidBand humid, HeightBand height, boolean deepOcean) {
        switch (height) {
            case OCEAN:
                return deepOcean
                        ? List.of(ID_DEEP_OCEAN, ID_OCEAN, ID_PLAINS)
                        : List.of(ID_OCEAN, ID_DEEP_OCEAN, ID_PLAINS);
            case COAST:
                return humid == HumidBand.WET
                        ? List.of(ID_PLAINS, ID_SWAMP, ID_FOREST, ID_OCEAN)
                        : List.of(ID_PLAINS, ID_FOREST, ID_OCEAN);
            case PLAINS:
                switch (temp) {
                    case COLD:
                        return humid == HumidBand.WET
                                ? List.of(ID_TAIGA, ID_SNOWY_PLAINS, ID_PLAINS)
                                : List.of(ID_SNOWY_PLAINS, ID_TAIGA, ID_PLAINS);
                    case TEMPERATE:
                        return humid == HumidBand.WET
                                ? List.of(ID_FOREST, ID_SWAMP, ID_PLAINS)
                                : List.of(ID_PLAINS, ID_FOREST);
                    case HOT:
                    default:
                        return humid == HumidBand.WET
                                ? List.of(ID_JUNGLE, ID_DESERT)
                                : List.of(ID_DESERT, ID_JUNGLE);
                }
            case HILLS:
                switch (temp) {
                    case COLD:
                        return List.of(ID_TAIGA, ID_MOUNTAINS, ID_SNOWY_PLAINS);
                    case TEMPERATE:
                        return List.of(ID_FOREST, ID_MOUNTAINS, ID_PLAINS);
                    case HOT:
                    default:
                        return humid == HumidBand.WET
                                ? List.of(ID_JUNGLE, ID_MOUNTAINS, ID_DESERT)
                                : List.of(ID_DESERT, ID_MOUNTAINS, ID_JUNGLE);
                }
            case MOUNTAINS:
                switch (temp) {
                    case COLD:
                        return List.of(ID_MOUNTAINS, ID_SNOWY_PLAINS, ID_TAIGA);
                    case TEMPERATE:
                        return List.of(ID_MOUNTAINS, ID_FOREST, ID_TAIGA);
                    case HOT:
                    default:
                        return List.of(ID_MOUNTAINS, ID_DESERT, ID_JUNGLE);
                }
            case SNOWLINE:
            default:
                return List.of(ID_SNOWY_PLAINS, ID_MOUNTAINS, ID_TAIGA);
        }
    }
}
