package com.gtsn.terrain.world;

import com.gtsn.terrain.noise.CaveNoise;
import com.gtsn.terrain.noise.HeightMapBuilder;
import com.gtsn.terrain.noise.TerrainConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * GTSN 自写地形生成器（M3-B/M3-C 版）。
 *
 * <p>从零实现 {@link ChunkGenerator}，只替换"地形形状"相关抽象方法；
 * 基类具体方法（applyBiomeDecoration / createBiomes / createStructures）
 * 全部复用，结构/矿物/植被 feature 管线保留原版。
 *
 * <p>M2 高度图接入：codec 携带 {@code seed}（world_preset 显式写入，与
 * biome_source 同值，保证海陆判定一致）；构造时用 seed 构建
 * {@link HeightMapBuilder} 与 {@link CaveNoise}（均为纯函数、线程安全，
 * 可被 fillFromNoise 的异步列填充共享）。
 *
 * <p>M3-C 洞穴接入：fillFromNoise 逐列用 {@code isCave} 判定，
 * 洞穴处填空气、其余石头；三重防穿帮闸门（海平面掩码 / 地表安全深度 /
 * 近地表衰减）保证洞穴不穿出海面/湖面、不露出地表。applyCarvers 留空
 * （洞穴已在 fillFromNoise 内完成）。
 *
 * <p>buildSurface：按群系温度 + 高度带替换顶部 1-4 格地表方块——
 * 草方块（温带）/ 沙（热带/海岸）/ 雪块（雪线以上/冷带）/ 石头（山地裸露，
 * 保持 fillFromNoise 原样）；水下列换海床（深海砾石、浅海沙）。
 */
public class TerrainChunkGenerator extends ChunkGenerator {

    /** 海平面（水面高度，也是 getSeaLevel 返回值） */
    private static final int SEA_LEVEL = 63;
    /** 世界底 */
    private static final int MIN_Y = -64;
    /** 世界高（Y 640 方案：-64 + 704 = 640 顶） */
    private static final int HEIGHT = 704;
    /** 基岩层厚度 */
    private static final int BEDROCK_THICKNESS = 5;
    /** 深海判定：地表低于该高度视为深海（与 TerrainBiomeSource.DEEP_OCEAN_DEPTH 一致） */
    private static final int DEEP_OCEAN_DEPTH = 30;
    /** 雪线高度（方块）：地表高于该高度铺雪块 */
    private static final int SNOWLINE_Y = 300;
    /** 山地裸露带：地表高于该高度保持石头（与 TerrainBiomeSource MOUNTAINS_BAND_TOP 一致） */
    private static final int MOUNTAIN_BARE_Y = 180;
    /** 冷带温度阈值：baseTemperature &lt;= 该值铺雪块（snowy_plains=0.0，taiga=0.25） */
    private static final float COLD_TEMPERATURE = 0.1f;
    /** 热带温度阈值：baseTemperature &gt;= 该值铺沙（desert=2.0，savanna 亦覆盖） */
    private static final float HOT_TEMPERATURE = 1.5f;
    /** 地表替换深度（buildSurface 覆盖顶部格数上限） */
    private static final int SURFACE_LAYER_DEPTH = 4;

    /** 地形种子（世界种子，由 world_preset 显式写入） */
    private final long seed;
    /** 2D 高度图（M2）：地表高度 / 海陆判定，纯函数线程安全 */
    private final HeightMapBuilder heightMapBuilder;
    /** 3D 洞穴噪声（M3-C）：纯函数线程安全 */
    private final CaveNoise caveNoise;

    public static final Codec<TerrainChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(TerrainChunkGenerator::getBiomeSource),
                    Codec.LONG.optionalFieldOf("seed", 0L).forGetter(c -> c.seed)
            ).apply(instance, TerrainChunkGenerator::new));

    /** M1 兼容构造：默认种子 0（代码内创建用；world_preset 走 codec） */
    public TerrainChunkGenerator(BiomeSource biomeSource) {
        this(biomeSource, 0L);
    }

    /** codec 构造：seed 重建高度图与洞穴噪声（与 biome_source 同值保证海陆一致） */
    public TerrainChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        TerrainConfig config = new TerrainConfig(seed);
        this.heightMapBuilder = new HeightMapBuilder(config);
        this.caveNoise = new CaveNoise(seed);
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        // 捕获纯函数实例（线程安全），供异步列填充共享
        HeightMapBuilder heightMap = this.heightMapBuilder;
        CaveNoise caves = this.caveNoise;
        return CompletableFuture.supplyAsync(() -> {
            ChunkPos chunkPos = chunk.getPos();
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            BlockState water = Blocks.WATER.defaultBlockState();
            BlockState air = Blocks.AIR.defaultBlockState();

            int minY = chunk.getMinBuildHeight();
            int maxY = chunk.getMaxBuildHeight();
            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = baseX + x;
                    int wz = baseZ + z;
                    int surfaceY = heightMap.getHeight(wx, wz);
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(wx, minY, wz);
                    for (int y = minY; y < maxY; y++) {
                        pos.setY(y);
                        BlockState state;
                        if (y <= MIN_Y + BEDROCK_THICKNESS) {
                            state = bedrock;
                        } else if (y <= surfaceY) {
                            // 地表及以下：洞穴处空气，其余石头（顶部 3-4 格留待 buildSurface）
                            state = caves.isCave(wx, y, wz, surfaceY) ? air : stone;
                        } else if (y <= SEA_LEVEL) {
                            state = water;
                        } else {
                            state = air;
                        }
                        chunk.setBlockState(pos, state, false);
                    }
                }
            }
            return chunk;
        }, executor);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        HeightMapBuilder heightMap = this.heightMapBuilder;
        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int surfaceY = heightMap.getHeight(wx, wz);

                if (surfaceY <= MIN_Y + BEDROCK_THICKNESS) {
                    continue; // 不可能（高度图下限 -59），防御
                }

                if (surfaceY < SEA_LEVEL) {
                    // 水下列：海床换沙/砾石（fillFromNoise 已填水与石头，不碰水体）
                    BlockState floor = surfaceY < DEEP_OCEAN_DEPTH
                            ? Blocks.GRAVEL.defaultBlockState()
                            : Blocks.SAND.defaultBlockState();
                    pos.set(wx, surfaceY, wz);
                    chunk.setBlockState(pos, floor, false);
                    continue;
                }

                // 陆下列：群系温度 + 高度带决定地表方块
                Holder<Biome> biome = level.getBiome(pos.set(wx, surfaceY, wz));
                float temperature = biome.value().getBaseTemperature();

                if (surfaceY >= SNOWLINE_Y || temperature <= COLD_TEMPERATURE) {
                    // 雪线以上 / 冷带：雪块（下方保持石头）
                    pos.set(wx, surfaceY, wz);
                    chunk.setBlockState(pos, Blocks.SNOW_BLOCK.defaultBlockState(), false);
                } else if (temperature >= HOT_TEMPERATURE) {
                    // 热带（沙漠）：沙，往下铺 3 格
                    for (int d = 0; d < Math.min(SURFACE_LAYER_DEPTH - 1, 3); d++) {
                        pos.set(wx, surfaceY - d, wz);
                        chunk.setBlockState(pos, Blocks.SAND.defaultBlockState(), false);
                    }
                } else if (surfaceY >= MOUNTAIN_BARE_Y) {
                    // 山地裸露：保持 fillFromNoise 的石头（不替换）
                } else {
                    // 温带：草方块 + 下方 3 格泥土
                    pos.set(wx, surfaceY, wz);
                    chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                    for (int d = 1; d < SURFACE_LAYER_DEPTH; d++) {
                        pos.set(wx, surfaceY - d, wz);
                        chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        // 洞穴已在 fillFromNoise 内完成（M3-C），雕刻留空
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        ChunkPos chunkPos = level.getCenter();
        Holder<Biome> biome = level.getBiome(chunkPos.getWorldPosition().atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())));
        NaturalSpawner.spawnMobsForChunkGeneration(level, biome, chunkPos, level.getRandom());
    }

    @Override
    public int getGenDepth() {
        return HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        // 结构找地面用：与 fillFromNoise 的地表高度完全一致
        return this.heightMapBuilder.getHeight(x, z);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        // 与 fillFromNoise 同逻辑：基岩 + 石头/洞穴空气 + 水 + 空气
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        int surfaceY = this.heightMapBuilder.getHeight(x, z);
        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            if (y <= MIN_Y + BEDROCK_THICKNESS) {
                column[i] = Blocks.BEDROCK.defaultBlockState();
            } else if (y <= surfaceY) {
                column[i] = this.caveNoise.isCave(x, y, z, surfaceY)
                        ? Blocks.AIR.defaultBlockState()
                        : Blocks.STONE.defaultBlockState();
            } else if (y <= SEA_LEVEL) {
                column[i] = Blocks.WATER.defaultBlockState();
            } else {
                column[i] = Blocks.AIR.defaultBlockState();
            }
        }
        return new NoiseColumn(minY, column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("GTSN Terrain (M2 heightmap + M3-C caves, seed " + this.seed + ")");
    }
}
