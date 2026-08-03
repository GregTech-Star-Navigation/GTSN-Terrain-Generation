# 02 · Forge 1.20.1 注册自定义生成器

> 来源：Forge 1.20.1 官方映射核验（client.txt）+ MinecraftForge 1.20.1 分支 patch + 真实模组源码
> 用途：模组骨架代码的直接依据

## 1. 注册自定义 ChunkGenerator

1.20.1 中 `ChunkGenerator` 通过 vanilla registry + codec 注册：

```java
public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID);

public static final RegistryObject<Codec<? extends ChunkGenerator>> TERRAIN_CHUNK_GENERATOR =
        CHUNK_GENERATORS.register("terrain", () -> TerrainChunkGenerator.CODEC);
```

关键点：

- **element 是 `Codec`（com.mojang.serialization.Codec），不是 MapCodec**（MapCodec 是 1.20.2+ 的写法）
- `TerrainChunkGenerator` 必须实现 `codec()` 返回 `Codec<? extends ChunkGenerator>`
- codec 用 `RecordCodecBuilder.mapCodec(...)` 内部再 `.codec()` 或直接 `RecordCodecBuilder.create`
- 字段：`biome_source`（BiomeSource.CODEC）+ 自定义字段（如 `settings`: Holder<NoiseGeneratorSettings>）

真实示例（1.20.1 Forge）：

- TerraFirmaCraft：`DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID)`（tfc/world/TFCWorldGen.java）
- Ars-Nouveau：`VOID_CHUNK_GENERATOR_CODEC`（PlanariumChunkGenerator）
- Minestuck / LanteaCraft / Kingdom-Keys 均同模式

## 2. 注册自定义 BiomeSource

同样走 registry + codec：

```java
public static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
        DeferredRegister.create(Registries.BIOME_SOURCE, MOD_ID);

BIOME_SOURCES.register("terrain_biomes", () -> TerrainBiomeSource.CODEC);
```

- `BiomeSource` 抽象方法：`getNoiseBiome(int x, int y, int z, Climate.Sampler sampler)` + `getPossibleBiomes()`（返回群系列表）
- codec 字段：`biomes`（`RegistryCodecs.homogeneousList(Registries.BIOME)`）+ 自定义参数（种子等）
- **注意**：1.20.1 的 BiomeSource codec 注册是 `Codec` 类型（非 MapCodec，与 1.20.2+ 不同）

## 3. 注册自定义 WorldType —— 1.20.1 的正确做法

**重要修正（调研核心发现）**：

- **1.20.1 中 `WorldType` 不是 registry**（vanilla `Registries` 无 `WORLD_TYPE` key）
- `ForgeWorldPreset`/`ForgeRegistries.Keys.WORLD_TYPES` 是 **1.19.x 时代的 API，1.20.1 已弃用**
- 1.20.1 的正确路径 = **datapack world_preset JSON**（`data/<ns>/worldgen/world_preset/<name>.json`）

**world_preset JSON 格式**（1.20.1）：

```json
{
  "type": "minecraft:worldgen",
  "dimensions": {
    "minecraft:overworld": {
      "type": "minecraft:overworld",
      "generator": {
        "type": "gtsn:terrain",
        "biome_source": {
          "type": "gtsn:terrain_biomes"
        },
        "settings": "minecraft:overworld"
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": { "type": "minecraft:noise", "settings": "minecraft:nether" }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": { "type": "minecraft:noise", "settings": "minecraft:end" }
    }
  }
}
```

**维度定义**：1.20.1 用 `LevelStem`（record: type + generator），数据文件在 `data/<ns>/dimension/<name>.json`（同一 JSON 结构）。**没有 DimensionOptions**（那是 1.18.x 的名字）。

**创建世界时如何出现**：world_preset JSON 直接出现在创建世界的 "More World Options" 预设列表里（datapack registry 自动注册），无需代码注册。

## 4. MDK 配置（1.20.1 专属）

- **ForgeGradle 6.0.x**（`plugins { id 'net.minecraftforge.gradle' version '[6.0,6.2)' }`）
- **Java 17**（toolchain 配置）
- **mappings：官方官方映射** `mappings channel: 'official', version: '1.20.1'`
- Gradle 8.x（ForgeGradle 6 要求）
- 依赖：`minecraft 'net.minecraftforge:forge:1.20.1-47.3.0'`（或最新 47.x）
- 注意：Forge 1.20.x 分支的 MDK HEAD 已升 Java 21（那是 1.20.6 的），**1.20.1 必须用 Java 17**

```groovy
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
}

version = '1.0.0'
group = 'com.gtsn.terrain'
archivesBaseName = 'gtsn-terrain'

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'official', version: '1.20.1'
    runs {
        client { workingDirectory project.file('run'); property 'forge.logging.markers', 'REGISTRIES' }
        server { workingDirectory project.file('run') }
        data { workingDirectory project.file('run'); args '--mod', 'gtsn', '--all', '--output', file('src/generated/resources/') }
    }
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.3.0'
}
```

## 5. NoiseGeneratorSettings：自定义 levels（Y 640）

1.20.1 中 NoiseGeneratorSettings 是 **datapack registry**（`Registries.NOISE_SETTINGS`，数据文件 `data/<ns>/worldgen/noise_settings/<name>.json`），不是静态注册。

**两种方式**：

1. **JSON datapack**（推荐，最简单）：mod 的 `data/gtsn/worldgen/noise_settings/overworld_tall.json`，字段包含 `min_y: -64, height: 704`，然后 chunk generator 的 codec 里 `"settings": "gtsn:overworld_tall"` 引用。
2. **代码注册**：`Registry.register(...)` 或通过 Forge `DataPackRegistryEvent`（但 1.20.1 vanilla datapack registry 更简单直接 JSON）。

**重要**：NoiseGeneratorSettings JSON 必须包含完整字段（noise_router 引用的所有 density function 必须存在），否则加载报错。自写 chunk generator 如果完全不读 density function，可只引用一个空壳 settings 提供 levels。

## 6. 世界类型选择后的装配链

```
创建世界界面选择 preset（world_preset JSON）
→ WorldDimensions.bootstrap → LevelStem(type=DimensionType, generator=ChunkGenerator from codec)
→ ChunkGenerator codec 反序列化 → 构造 TerrainChunkGenerator(biomeSource, settingsHolder)
→ 世界生成时：ChunkStatus 管线调 fillFromNoise/buildSurface/applyCarvers/applyBiomeDecoration
```

- 下界/末地维持原版（noise generator + 原版 settings），只替换主世界，结构/任务/进度的兼容面最小。

## 7. 完整开源示例

| 项目                            | 仓库                                             | 关键文件                                                                                                   |
| ------------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| TerraFirmaCraft（1.20.1 Forge） | github.com/TerraFirmaCraft/TerraFirmaCraft       | `src/main/java/net/dries007/tfc/world/TFCWorldGen.java`（CHUNK_GENERATOR + BIOME_SOURCE DeferredRegister） |
| Ars-Nouveau（1.20.1）           | github.com/baileyholl/Ars-Nouveau（1.20.1 分支） | `ModSetup.java`（VOID_CHUNK_GENERATOR_CODEC，PlanariumChunkGenerator）                                     |
| Kingdom-Keys                    | github.com/Wehavecookies56/Kingdom-Keys          | CHUNK_GENERATOR DeferredRegister 示例                                                                      |
| Terra                           | github.com/PolyhedralDev/Terra                   | MinecraftChunkGeneratorWrapper（mixin-common）                                                             |
