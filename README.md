# GTSN-Terrain Generation

GTSN 整合包的自定义地形生成 + 气候环境系统模组（Minecraft 1.20.1 Forge）

## 功能

- **地形生成器（子项目 A）**：自写 `ChunkGenerator`，2D 高度图 + 3D 洞穴噪声，地球大陆感地形（板块大陆、边缘山脉、海岸线、内陆河网），世界高度 Y 640
- **气候环境系统（子项目 B）**：温度 / 湿度 / 气流 / 紫外线 / 光照 5 维气候数据 + 实际影响；龙卷风 / 沙尘暴 / 洪水灾害（完整物理效果）
- **API**：Forge 事件总线 + 公开 API 类 + KubeJS 绑定（`GtsnTerrain.Climate.getTemperature(...)`）

## 设计决策

详见 [docs/research/](docs/research/) 调研报告（原版机制 / Forge 注册 / 开源对比 / 噪声选型 / KubeJS 气候）：

| 决策点   | 结论                                                                                                                  |
| -------- | --------------------------------------------------------------------------------------------------------------------- |
| 生成架构 | 从零实现 `ChunkGenerator`（仿原版 doFill cell 插值循环），复用基类 applyBiomeDecoration/createBiomes/createStructures |
| 噪声引擎 | FastNoiseLite Java 单文件（OpenSimplex2 + FBm + DomainWarp）                                                          |
| 群系     | 自写布局算法（温度/湿度/高度噪声）+ 复用原版/BOP 群系                                                                 |
| 洞穴     | 自写 3D 阈值噪声 + 原版三机制防穿帮                                                                                   |
| 气候     | 群系基准 + 海拔/距海/时间修正（SereneSeasons/TAN 模式）                                                               |
| 灾害     | Weather2 模式：BlockUpdateSnapshot 队列 + setBlock(flag 3) 同步                                                       |

## 开发环境

- JDK 17 + IntelliJ IDEA
- ForgeGradle 6.0.x，mappings `official`，Minecraft 1.20.1-47.3.0
- 构建：`gradlew build`（产物在 `build/libs/`）
- 导入 IDEA：`gradlew genIntellijRuns` 或 IDE 导入 Gradle 项目

## 路线

- [ ] M1 工程骨架 + 空生成器注册 + 世界类型可选
- [ ] M2 高度图算法（纯 Java + JUnit）
- [ ] M3 群系布局 + 地表方块 + 洞穴
- [ ] M4 气候数据层 + API + KubeJS 绑定
- [ ] M5 灾害三件套（龙卷风/沙尘暴/洪水）
- [ ] M6 性能调优 + 全量兼容回归

## 许可证

MIT
