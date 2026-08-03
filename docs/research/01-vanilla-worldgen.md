# 01 · 原版 1.20.1 地形生成机制

> 来源：Blackjack200/minecraft_client_1_20_1（mojmap 反编译，SHA c129e3e）+ 本地 client.jar 数据 JSON
> 用途：决定自定义 ChunkGenerator 覆写哪些方法、如何复用基类

## 1. 生成流程：4 个独立 ChunkStatus，不是一条方法链

1.20.1 中地形生成拆成 **4 个独立 ChunkStatus**，各调一个 `ChunkGenerator` 方法：

| ChunkStatus | 调用的方法                         | 职责                                                            |
| ----------- | ---------------------------------- | --------------------------------------------------------------- |
| NOISE       | `fillFromNoise`（抽象）            | 密度场 → 填方块（异步线程 wgen_fill_noise）                     |
| SURFACE     | `buildSurface`（抽象）             | 地表方块规则（委托 `randomState.surfaceSystem().buildSurface`） |
| CARVERS     | `applyCarvers`（抽象）             | 雕刻隧道/峡谷（CarvingMask + configured_carver）                |
| FEATURES    | `applyBiomeDecoration`（基类具体） | 结构 + 植被/矿石 feature                                        |

**关键修正**：1.20.1 的 `doFill` **不调 buildSurface**（那是 1.18 的行为）——地表/雕刻/装饰都是独立状态。

**fillFromNoise 内部**（NoiseBasedChunkGenerator L241→doFill L270）：

```
getOrCreateNoiseChunk → 取 OCEAN_FLOOR_WG / WORLD_SURFACE_WG 高度图
→ 按 cell 网格（cellWidth=4, cellHeight=8）updateForX/Y/Z
→ noisechunk.getInterpolatedState() 填方块（非空气才 setBlockState）
→ aquifer.shouldScheduleFluidUpdate() → markPosForPostprocessing
```

## 2. NoiseRouter：1.20.1 是 15 个槽位

**注意**：第 11 槽叫 `initial_density_without_jaggedness`（不存在 `initial_density`，那是旧版/1.21.9 的说法）。

| 槽位                                                          | 消费者                                    | 作用                                                                             |
| ------------------------------------------------------------- | ----------------------------------------- | -------------------------------------------------------------------------------- |
| temperature / vegetation                                      | 群系放置                                  | shifted_noise，xz_scale 0.25                                                     |
| continents                                                    | 群系 + terrain shaper spline              | 大陆度                                                                           |
| ridges                                                        | 群系                                      | weirdness 维原始值                                                               |
| erosion / depth                                               | 群系 + Aquifer                            | 侵蚀度 / 深度                                                                    |
| barrier / fluid_level_floodedness / fluid_level_spread / lava | 含水层                                    | 屏障 / 淹没度(±1) / 液面高度 / 岩浆阈值 0.3                                      |
| initial_density_without_jaggedness                            | NoiseChunk.computePreliminarySurfaceLevel | 自上而下扫 >0.390625 得 preliminary surface（供 above_preliminary_surface 条件） |
| **final_density**                                             | **每方块 solid(>0)/air(≤0)**              | 经 Beardifier 再过 Aquifer                                                       |
| vein_toggle / vein_ridged / vein_gap                          | 矿脉                                      | 开关 / 脉体 / 填充                                                               |

## 3. 3D 密度函数组合（final_density 顶层）

```
min( squeeze(0.64 * interpolated(blend_density(0.1171875 + y_gradient))), caves/noodle )
地表附近：when_in_range: min(sloped_cheese, 5*caves/entrances)
地下：when_out_of_range: max(min(4*square(cave_layer)+clamp(0.27+cave_cheese)+clamp(1.5-0.64*sloped_cheese), entrances),
                              spaghetti_2d + spaghetti_roughness, pillars)
```

关键子函数：

- `sloped_cheese` = 4×quarter_negative((depth + jaggedness×half_negative(噪声@1500)) × factor) + base_3d_noise
- `factor`（缩放幅度）= 10 + blend_alpha×(spline(continents→erosion→ridges_folded) - 10)
- `depth` = y_clamped_gradient(-64→320, 1.5→-1.5) + offset
- `offset`（海平面基线）= 大陆/侵蚀/脊线 spline + blend_offset/alpha
- `jaggedness`（山顶锯齿）= spline（0~0.63）
- `base_3d_noise` = old_blended_noise（smear 8, xz_factor 80, y_factor 160）
- **深板岩/基岩层不是密度函数，是 surface rule**（vertical_gradient + random_name）

## 4. 洞穴系统

- 噪声洞穴**没有独立槽位**，全部嵌在 `final_density` 内：
  - Cheese → `cave_cheese`（clamp ±1）+ `cave_layer`（square×4）
  - Spaghetti → `caves/spaghetti_2d` + `spaghetti_roughness_function`（2D 沿 y 伸展）；3D 用 `spaghetti_3d_1/2` + rarity/thickness（weird_scaled_sampler）
  - Noodle → `caves/noodle`（final_density 外层 min 第二参数，y∈[-60,321)，noodle<0→+64 强制掏空）
  - Entrances → `caves/entrances`（y_clamped_gradient(-10→30) 只浅层）
  - Pillars → `caves/pillars`（range_choice <0.03→-1e6）
- Carver 是**另一套机制**：`configured_carver/cave.json`、`cave_extra_underground.json`、`canyon.json` 被群系 JSON `carvers.air` 引用，在 **CARVERS 状态**用 CarvingMask 在已生成地形上挖，可含水层回填。
- 结论：原版洞穴 = 密度函数（noise caves，主要）+ carver（次要，只作用于噪声洞穴没掏空处）。

## 5. surface_rule：决策树，只换皮

- 结构：`sequence`（按序尝试、首个命中）→ `condition(if_true, then_run)` → 叶子 `block`。
- **只替换 `default_block`（stone），不处理空气/流体**。
- overworld 顶层骨架：① vertical_gradient(bedrock_floor)→基岩底；② condition(above_preliminary_surface)→逐群系规则（雪/冰/石、badlands 色带、grass/dirt + 水下砂岩）；③ vertical_gradient(deepslate)→深板岩。
- 关键机制：`stone_depth(floor/ceiling)+offset+add_surface_depth+secondary_depth_range`（基于 SurfaceSystem 逐列追踪 stoneDepthAbove/Below/waterHeight）；`surface_depth = floor(surface噪声×2.75+3+positional_random×0.25)`；`above_preliminary_surface` 防洞穴顶长草。
- **地形高低由密度决定，surface rule 只决定表面方块**——山地 vs 平原的差异全靠 `biome` 条件。

## 6. MultiNoiseBiomeSource 与 Climate.ParameterPoint

- 1.20.1 主世界参数点**不是数据驱动**：`multi_noise_biome_source_parameter_list/overworld.json` 只有 `"preset": "minecraft:overworld"`，实际参数点在代码 `OverworldBiomeBuilder`（built-in `MultiNoiseBiomeSourceParameterLists.OVERWORLD`）。
- 6 维分段：temperature 5 段、humidity 5 段、continentalness 7 段（mushroom/deep_ocean/ocean/coast/near_inland/mid_inland/far_inland）、erosion 7 段、depth（地表 0.0/1.0 双点，地下 0.2-0.9，深暗之域 1.1）、weirdness（ridges 先过 peaksAndValleys() 再切 12 片）。
- `getNoiseBiome` → `parameters().findValue(sampler.sample(...))`：按 6 维**加权欧氏距离**（weirdness/continentalness 权重最大）找最近点，QuartPos（÷4）粒度。
- 调用时机：BIOMES 状态 createBiomes / CARVERS 状态取 carver 列表 / SURFACE 状态每列取群系 / FEATURES 状态遍历 3×3 区块群系。

## 7. 世界高度与 chunk 结构

- overworld：`min_y=-64, height=384, size_horizontal=1, size_vertical=2, sea_level=63`（NoiseGeneratorSettings.overworld 硬编码）。
- section 数 = 384/16 = 24 个 LevelChunkSection；min_y/height 必须 16 的倍数。
- 密度 cell：cellWidth = size_horizontal×4 = **4 格**、cellHeight = size_vertical×4 = 8；NoiseChunk.forChunk → cellCountXZ=4、cellCountY=48 → **插值网格 5×49×5 = 1225 角点/区块**。
- **性能核心**：密度只在 cell 角点采样、块内三线性插值；逐方块最终评估仍要 16×384×16 = 98,304 次（但内层是 lerp3 + aquifer.computeSubstance）。
- seaLevel 链路：JSON sea_level → NoiseGeneratorSettings.seaLevel() → ChunkGenerator.getSeaLevel()；水位判定在 createFluidPicker（lava 阈 -54、水到 seaLevel）。

## 8. ChunkStatus 管线（11 状态，8 个由 ChunkGenerator 负责）

| 状态                                    | 负责者                                                       |
| --------------------------------------- | ------------------------------------------------------------ |
| STRUCTURE_STARTS / STRUCTURE_REFERENCES | ChunkGenerator.createStructures / createReferences（半径 8） |
| BIOMES                                  | createBiomes（fillBiomesFromNoise）                          |
| NOISE                                   | fillFromNoise（抽象）                                        |
| SURFACE                                 | buildSurface（抽象）                                         |
| CARVERS                                 | applyCarvers（抽象）                                         |
| FEATURES                                | applyBiomeDecoration（基类具体）                             |
| SPAWN                                   | spawnOriginalMobs（NoiseBasedChunkGenerator 实现）           |
| INITIALIZE_LIGHT / LIGHT                | ThreadedLevelLightEngine（与 generator 无关）                |
| FULL                                    | ServerChunkCache/ChunkMap（与 generator 无关）               |

## 9. 覆写策略（对我们项目的直接指导）

**必须实现（ChunkGenerator 抽象方法）**：`fillFromNoise`、`buildSurface`、`applyCarvers`、`getBaseHeight`、`getBaseColumn`、`getSeaLevel/getMinY/getGenDepth`、`spawnOriginalMobs`、`codec()`。

**可复用基类（别覆写）**：`getBiomeSource()`（构造器传 BiomeSource）、`applyBiomeDecoration`、`createBiomes`、`createStructures/createReferences`。

**推荐梯度**：

1. 最省事——继承 NoiseBasedChunkGenerator 只覆写 createNoiseChunk/fillFromNoise（换 final_density），或纯数据换 noise_router+surface_rule（零 Java）
2. **我们的路线**——继承 ChunkGenerator 仿 doFill 的 cell 插值循环 + 仿 SurfaceSystem.buildSurface 逐列扫描，getBaseHeight 仿 iterateNoiseColumn（密度插值 + 不经过 surface rule）
3. 绝不要覆写 getBiomeSource/applyBiomeDecoration/createBiomes（除非完全绕开原版生态）

## 1.20.1 的 Codec 细节（供 codec() 实现参考）

- 1.20.1 `ChunkGenerator.CODEC` = `com.mojang.serialization.Codec`（**不是 MapCodec，那是 1.20.2+**）
- `codec()` 抽象方法返回 `Codec`（无行号 = 抽象）
- `generatorSettings()` 返回 `Holder<NoiseGeneratorSettings>`（**没有 getBaseGenerationSettings，那是 1.20.2+ 的名字**）
- 维度定义类 = **`LevelStem`**（record: type: Holder<DimensionType>, generator: ChunkGenerator，带 CODEC）——**不是 DimensionOptions**（1.20.1 官方映射中不存在 DimensionOptions）
- `Registries.NOISE_SETTINGS` 存在（NOISE_GENERATOR_SETTINGS 不存在）
- `createStructures(RegistryAccess, ChunkGeneratorStructureState, StructureManager, ChunkAccess, StructureTemplateManager)` 是具体方法（有 body）
