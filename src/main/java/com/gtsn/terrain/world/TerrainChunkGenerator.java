package com.gtsn.terrain.world;

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
 * GTSN 自写地形生成器（M1 骨架版）。
 *
 * 从零实现 {@link ChunkGenerator}，只替换"地形形状"相关抽象方法；
 * 基类具体方法（applyBiomeDecoration / createBiomes / createStructures）
 * 全部复用，结构/矿物/植被 feature 管线保留原版。
 *
 * M1 阶段：fillFromNoise 用恒定高度图（Y=72 石头平台 + 基岩底 + 海水）
 * 验证管线可跑通，后续里程碑替换为 2D 高度图噪声管线。
 */
public class TerrainChunkGenerator extends ChunkGenerator {

    /** M1 平台高度 */
    private static final int PLATFORM_Y = 72;
    /** 海平面 */
    private static final int SEA_LEVEL = 63;
    /** 世界底 */
    private static final int MIN_Y = -64;
    /** 世界高（Y 640 方案：-64 + 704 = 640 顶） */
    private static final int HEIGHT = 704;
    /** 基岩层厚度 */
    private static final int BEDROCK_THICKNESS = 5;

    public static final Codec<TerrainChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(TerrainChunkGenerator::getBiomeSource)
            ).apply(instance, TerrainChunkGenerator::new));

    public TerrainChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
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
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(baseX + x, minY, baseZ + z);
                    for (int y = minY; y < maxY; y++) {
                        pos.setY(y);
                        BlockState state;
                        if (y <= MIN_Y + BEDROCK_THICKNESS) {
                            state = bedrock;
                        } else if (y <= PLATFORM_Y) {
                            state = stone;
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
        // M1：恒定地形无需地表规则，后续里程碑实现 SurfaceSystem 逐列扫描
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        // M1：无雕刻，后续里程碑自写 3D 洞穴
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
        return PLATFORM_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            if (y <= MIN_Y + BEDROCK_THICKNESS) {
                column[i] = Blocks.BEDROCK.defaultBlockState();
            } else if (y <= PLATFORM_Y) {
                column[i] = Blocks.STONE.defaultBlockState();
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
        info.add("GTSN Terrain (M1 constant platform)");
    }
}
