# 03 · 开源地形模组对比（架构/算法/性能）

> 来源：本地克隆源码逐行核验（SHA 见各节）
> 用途：模块边界、算法模式、性能策略的直接借鉴

## 1. Terra（PolyhedralDev/Terra）— 3D 密度场范本

**仓库**：github.com/PolyhedralDev/Terra（SHA `25d5101`，目标 1.21.10）
**1.20.1 Forge 支持**：**不支持**（仅 Fabric Beta；Forge 6.2.1 官方标注 unsupported）。

- **NoiseChunkGenerator3D**：全 3D 密度场 `sampler.sample(x,y,z) > 0`。每群系一组噪声（base/elevation/carving + blendDistance×blendStep 群系间海拔混合 + elevationWeight）。
- **LazilyEvaluatedInterpolator**（核心性能技巧）：雕刻噪声在粗网格采样（默认水平 4×垂直 2）→ 三线性插值；**懒求值 + Double[] samples 数组缓存**（`samples = new Double[yMul * (vSamples + 1)]`）。噪声只在网格点求值、中间块插值。
- **倾斜调色板**：`SlantCalculationMethod.Derivative`（3D 梯度算坡度选悬崖调色板），调色板按深度分层。
- **PipelineBiomeProvider**：阶段化管线（source + replace/expand/border stage）+ 分辨率缩放 + **mutator 噪声做域扭曲**（x += noise×noiseAmp）+ Caffeine 区块缓存(64)。2D 群系放置。
- 河流：6.x 无内置；分支 `dev/fractal-gavoro-pseudoerosion` 在研伪侵蚀。
- **性能实测（Minestom 移植）**：625 区块 19.7s ≈ 31.7 chunks/s（缓存命中 99.99%）；缓存 128 → 84-86 chunks/s ≈ 11.6ms/块。

## 2. TerraForged ⭐（与我们的 2D 高度图路线最接近）

**仓库**：github.com/TerraForged/TerraForged（SHA `6dd607e`）
**1.20.1 支持**：**无，止步 1.18.2**（续作 ReTerraForged 1.19+/TerraScribe 1.21 NeoForge）。

- **2D+3D 混合**：完整 ChunkGenerator 替换 + 纯 2D 高度图驱动。`Generator.fillFromNoise` 从 2D 地形缓存取 `TerrainData` → `ChunkUtil.fillChunk` 直接填 3D（stone/water）；`getBaseHeight` 直接从 2D 数据返回（高度图查询几乎免费）。邻居区块 CompletableFuture + 自建 ThreadPool 并行。
- **域扭曲**：`Domain.warp(simplex(x), simplex(y), 0.004)` 扭曲河流采样坐标；大陆 cell 采样用 cellShape.adjustX/Y。
- **ErosionFilter（水力侵蚀）**：经典 Sebastian Lague 水滴模拟，**每区块 350 水滴**（ErodedNoiseGenerator.java:58 默认值），参数 inertia=0.005 / sedimentCapacityFactor=7 / gravity=2.5 / erosionRadius=7。流程：采样邻居高度 → 中心高度 → 侵蚀 → 河流。
- **RiverGenerator（Voronoi）**：Voronoi cell 图最陡下降连接（每 cell 找最低邻居，双向最低才连 → 树状河网）→ 河道节点曲线化（垂直位移 + 噪声 warp）→ `RiverCarver` 按到河道距离雕刻 **valleyWidth/bankWidth/bankDepth/bedWidth/bedDepth 五级参数** + ridge 噪声做侵蚀调制。
- 性能：自带 GeneratorProfiler（按阶段计时）但 1.18.2 已注释禁用（无实测）；社区定性 "hefty generator，服务器不推荐"。

## 3. Terralith — 纯数据包范本

**仓库**：github.com/Stardust-Labs-MC/Terralith（默认分支 `1.20`，SHA `5d7b5c5`）
**1.20.1**：是。

- 机制：1.18+ 原版把地形管线全暴露为 JSON，Terralith 覆盖 `noise_settings/overworld.json` + `noise_router` 12 个密度函数。
- `final_density.json` 在 `sloped_cheese` 上用 range_choice 分区处理，max 叠加 `extra_terrain_sum`、min 减 `subtract_terrain_sum`；extra_terrain_sum 用 cache_once 包装（每列只算一次）。
- `continents.json = add(0, effective_continentalness)` 保留原版大陆度——地形差异在 final_density + 表面规则，不在大陆形状。
- 巨型 surface_rule 里海量 noise_threshold 按群系换表面方块。
- 局限：只能组合原版密度函数原语，无法做水滴侵蚀/SDF 岛层等自建算法。

## 4. BetterEnd / BetterNether — SDF 岛层范本

**仓库**：官方迁至 github.com/quiqueck/BetterEnd（分支 1.20/1.20.3）；Forge 1.20.1 = 非官方移植 Reijin2312/BetterEnd_Forge（依赖 BCLib/WunderLib）。
**本地核验**：1.18.2 分支 SHA `d4cd8e0`（算法与 1.20 一致）

- **SDF 岛层**：三层 IslandLayer（大/中/小），每层网格摆岛，岛 = `SDFCappedCone`（锥体）× `SDFRadialNoiseMap`（径向噪声）× `SDFSmoothUnion`（平滑并集）；密度 = -min(到各岛 SDF 距离)。**3 级域扭曲**：noise×0.1×20 + noise×0.2×10 + noise×0.4×5。
- **群系感知高度**：getAverageDepth 按 7×7 邻域群系地形高度加权平均；isLand() 用 2D TerrainBoolCache（64 节缓存）避免重复 3D 采样。
- **洞穴雕刻**：密度场承担主体（岛 SDF 负值即虚空），特定洞穴在装饰阶段用 feature 雕刻（EndCaveFeature：半径 10-30 球体 SDF 挖空 + 单独洞穴群系）。**"密度场 + feature 补洞"分工最适合抄**。

## 5. BOP 1.20.1 + TerraBlender — 群系注册范本

**仓库**：github.com/Glitchfiend/BiomesOPlenty（分支 1.20.1，SHA `68b81ee`）；TerraBlender（分支 1.20.1，SHA `f9de090`）

- BOP **无自定义世界类型**（1.18+ 已移除），群系全部经 TerraBlender Region 注册：`Regions.register(new BOPOverworldRegionPrimary(weight))` ×5 区域（3 主世界 + 2 下界）。
- **BOPOverworldBiomeBuilder 参数映射**（群系布局算法模板）：温度 5 段 × 湿度 5 段 × 侵蚀 7 段 × 大陆度 7 段（海岸 -0.19/-0.11、近内陆 -0.11/0.03、中内陆 0.03/0.3、远内陆 0.3/1.0），addBiomeSimilar 复用原版参数点。
- **OriginCaveWorldCarver**：继承原版洞穴雕刻的自建 carver。
- **TerraBlender 核心 hook**：MixinMultiNoiseBiomeSource 拦截 getNoiseBiome → 改走 IExtendedParameterList.findValuePositional（位置相关）；RegionUtils 用原版 OverworldBiomeBuilder 参数点作基底合并 region 参数点；区域归属用原版式 Area/ZoomLayer 噪声 + 权重。
- **⚠️ 移除 TerraBlender 后 BOP 群系不能独立使用**：BOP 的 mods.toml 硬依赖 terrablender（versionRange=[version,)）；移除后 Forge 缺依赖不加载。想独立用 BOP 群系必须复刻 Region→ParameterList 注入逻辑。

## 6. Tectonic / Amplified-Nether — cache_2d 性能技巧

**仓库**：github.com/Apollounknowndev/tectonic（SHA `34241bd`，1.20.1 有 v2.0 数据包/v2.4.1 Forge）；github.com/Stardust-Labs-MC/Amplified-Nether（分支 1.20，SHA `75f7e7e`）

- **Tectonic 算法**：`final_density = min(base_terrain, caves) + noodle + underground_river + lava_tunnel`。base_terrain 用 2D 样条（terrain_spline）+ 大陆形状 region（club/diamond/heart/spade 通过温度/植被/山脊样条）；山脉 = mountain_ridges/ridges_folded；深度截断洞穴（depth_cutoff）。
- **核心性能技巧**：凡不依赖 Y 的子树包进 `minecraft:cache_2d`/`cache_once`/`flat_cache`，整列只求值一次（region/diamond.json 用 cache_2d/flat_cache；underground_river/parameters.json 用 cache_once + range_choice，y∈[32,72) 走廊掩码 + ridges_folded）。
- **地下河公式**：2D 气候样条（大陆×侵蚀×高程×山脊）乘积 × 密度增量 + y∈[32,72) 走廊（range_choice）+ cache_once。
- **Amplified-Nether**：`initial_density_without_jaggedness = 4 × quarter_negative((0+depth) × cache_2d(factor))` + `size_vertical: 2`——数据包版"2D 高度图 + 3D 雕刻"最小示例。

## 7. 对比总表

| 项目             | 架构                    | 算法                               | 1.20.1 Forge    | 性能              | 可借鉴                            |
| ---------------- | ----------------------- | ---------------------------------- | --------------- | ----------------- | --------------------------------- |
| Terra            | 3D 密度场 + 懒插值      | 每群系噪声 + 群系混合 + 倾斜调色板 | ❌（仅 Fabric） | 84-86 chunks/s    | 懒求值插值、倾斜调色板、管线群系  |
| TerraForged      | **2D 高度图 + 3D 填充** | 水滴侵蚀 + Voronoi 河流            | ❌（止 1.18.2） | 无实测（"hefty"） | **2D 路线整体、侵蚀、河流、并行** |
| Terralith        | 纯数据包                | density_function 覆写              | ✅              | 同原版            | cache_once、表面规则              |
| BetterEnd        | SDF 岛层                | 3 级域扭曲 + feature 补洞          | ✅（移植）      | 中                | SDF、域扭曲分层、2D 缓存          |
| BOP+TerraBlender | Region 注入 MultiNoise  | 参数点矩阵                         | ✅              | 低（性能负担）    | 群系参数映射模板                  |
| Tectonic         | 数据包+mod              | 2D 样条 + 地下河 + cache_2d        | ✅              | 同原版            | **cache_2d/cache_once**、走廊掩码 |

## 8. 对我们项目的直接结论

1. **模块边界**：照 Terra 的 addon 式拆（噪声引擎/高度图/洞穴/群系布局/调色板独立），模块间用数据对象（TerraForged 的 NoiseSample/TerrainData 模式）通信。
2. **2D 高度图 + 3D 洞穴**：照 TerraForged（2D 填区块 + getBaseHeight 走 2D + 邻居并行）；洞穴用 Terra 的 4×4×2 低分辨率插值或 BetterEnd 的 feature 雕刻。
3. **大陆感**：大陆 = 低八度噪声/Voronoi（TerraForged ShapeGenerator）；山脉 = 山脊噪声 + 域扭曲（Tectonic ridges_folded）；海岸线 = 大陆度阈值 + 群系参数段（BOP -0.19/-0.11 海岸段）。
4. **河流**：抄 TerraForged 的 cell 图最陡下降 + 距离场谷地雕刻（成本低效果稳），或 Tectonic 的 2D 走廊掩码做地下河。
5. **性能坑**：避免每块全分辨率 3D 采样（Terra 插值、Tectonic cache_2d 都是为此）；水滴侵蚀 350/块是"重"的根源，可降频或只在关键地形区做。
6. **BOP 处理**：保留 TerraBlender（BOP 硬依赖）或用原版群系 + 自写布局——需要用户决策。
