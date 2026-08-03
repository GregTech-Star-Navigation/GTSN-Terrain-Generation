# GTSN-Terrain Generation 调研报告

> 调研日期：2026-08-03
> 项目：GTSN-Terrain Generation（Minecraft 1.20.1 Forge 自定义地形 + 气候环境模组）
> 目标：把"性能 ↑ / 山高 ↑ / 真实感 ↑"三个诉求落实为可执行的技术方案

## 调研范围

| 报告                                                     | 内容                        | 关键结论                                                                                                            |
| -------------------------------------------------------- | --------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| [01-vanilla-worldgen.md](01-vanilla-worldgen.md)         | 原版 1.20.1 地形生成机制    | 15 槽位 NoiseRouter、4 独立 ChunkStatus、密度插值网格 1225 角点/区块、覆写策略                                      |
| [02-forge-registration.md](02-forge-registration.md)     | Forge 注册自定义生成器      | 1.20.1 用 `Codec`（非 MapCodec）、`LevelStem`（非 DimensionOptions）、world_preset JSON、MDK ForgeGradle 6.0+Java17 |
| [03-open-source-projects.md](03-open-source-projects.md) | 开源地形模组对比            | Terra(3D密度+懒插值)、TerraForged(2D高度图+350水滴侵蚀+Voronoi河流)、Terralith(纯数据包)、Tectonic(cache_2d)        |
| [04-noise-selection.md](04-noise-selection.md)           | 噪声算法选型                | **选定 FastNoiseLite Java 单文件**（SHA 785f37a9 v1.1.1）、2D 高度图比 3D 密度场快一个数量级                        |
| [05-kubejs-climate.md](05-kubejs-climate.md)             | KubeJS 绑定 + 气候/灾害模组 | `kubejs.plugins.txt` 发现机制（非注解）、BindingsEvent.add、Weather2 灾害物理、SimpleChannel S2C                    |

## 核心设计决策（由调研修正）

| 决策点   | 调研结论                                                                      | 影响                                                                                                                                   |
| -------- | ----------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 生成架构 | **从零实现 ChunkGenerator**，仿原版 doFill 的 cell 插值循环                   | 4 个抽象方法必覆写：fillFromNoise/buildSurface/applyCarvers/getBaseHeight；applyBiomeDecoration/createBiomes/createStructures 复用基类 |
| 噪声引擎 | FastNoiseLite Java 单文件，OpenSimplex2 + FBm + DomainWarpProgressive         | 2D 高度图 256 列 × ~15 次采样 ≈ 4k/区块，vs 原版 ~2-3 万次 3D 八度评估 + 98k 逐方块插值                                                |
| 群系布局 | 复用原版/BOP 群系 + 自写参数映射（BOP OverworldBiomeBuilder 模板）            | BOP 依赖 TerraBlender 无法独立移除（mods.toml 硬依赖）→ **保留 TerraBlender 或去掉 BOP 群系**                                          |
| 世界高度 | Y 640（minY=-64, height=704）                                                 | cellCountY 从 48 增至 88，插值网格成本线性增加，2D 方案仍可控                                                                          |
| 洞穴     | 自写 3D 阈值噪声 + 原版三机制防穿帮（rangeChoice 门控/entrances 掩码/y 限制） | 洞穴密度函数嵌 final_density，carver 用于次要雕刻                                                                                      |
| 气候数据 | 参考 SereneSeasons（群系温度+偏移 clamp）与 TAN（档位映射）                   | 5 维气候 = 群系基准 + 海拔/距海/时间修正，KubeJS 绑定类对象                                                                            |
| 灾害     | 参考 Weather2：BlockUpdateSnapshot 队列 + setBlock flag 3 + AABB 实体吸引     | 服务器权威，setBlock(flag 3) 自动客户端同步                                                                                            |

## 关键来源索引

- 原版源码（mojmap）：[Blackjack200/minecraft_client_1_20_1](https://github.com/Blackjack200/minecraft_client_1_20_1)（SHA c129e3e）
- FastNoiseLite：[Auburn/FastNoiseLite](https://github.com/Auburn/FastNoiseLite)（SHA 785f37a9）
- KubeJS 1.20.1：[KubeJS-Mods/KubeJS](https://github.com/KubeJS-Mods/KubeJS) 分支 `2001`（SHA ba142541）
- TerraForged：[TerraForged/TerraForged](https://github.com/TerraForged/TerraForged)（SHA 6dd607e）
- Weather2：[Corosauce/weather2](https://github.com/Corosauce/weather2) 分支 `1.20`（SHA c023f460）
- BOP 1.20.1：[Glitchfiend/BiomesOPlenty](https://github.com/Glitchfiend/BiomesOPlenty) 分支 `1.20.1`（SHA 68b81ee）
- TerraBlender 1.20.1：[Glitchfiend/TerraBlender](https://github.com/Glitchfiend/TerraBlender) 分支 `1.20.1`（SHA f9de090）
