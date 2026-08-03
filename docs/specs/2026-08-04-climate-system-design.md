# 规格：GTSN-Terrain 气候系统（子项目 B）

> 状态：**待评审**（起草于 2026-08-04，供实现派发使用）
> 关联文档：`docs/research/05-kubejs-climate.md`（KubeJS 绑定 + 气候/灾害参考）、`docs/research/04-noise-selection.md`（噪声选型）、`README.md` 路线 M4/M5
> 本规格只描述**设计**，不包含实现代码（签名与伪代码除外）。所有类名/方法签名均为 Java 风格契约草案，实现里程碑可微调，但**对外契约（api 包）一经评审通过即冻结**。

---

## 0. 需求映射（已确认范围 → 章节索引）

| #   | 用户确认的需求（原文要点，不得删减）                                                                               | 对应章节 |
| --- | ------------------------------------------------------------------------------------------------------------------ | -------- |
| 1   | 气候数据层：温度/湿度/气流/紫外线/光照 5 维数据（生成方式、取值范围、驱动因素）                                    | §2       |
| 2   | 实际影响：5 维数据对世界有真实效果（温度→方块/实体/作物、湿度→降雨/雾、紫外线→烧伤/生长、光照→亮度等），具体影响表 | §3       |
| 3   | 灾害系统：龙卷风/沙尘暴/洪水 3 种，完整物理效果（卷起实体和方块、影响视野和实体、真实蔓延水位上涨）                | §4       |
| 4   | Forge 事件总线：公开事件供其他模组监听                                                                             | §5       |
| 5   | 公开 API 类：稳定查询 API（供其他模组与 KubeJS 调用）                                                              | §6       |
| 6   | KubeJS 1.20.1 绑定：BindingsEvent.add + kubejs.plugins.txt 发现（无 @KubeJSPlugin 注解）                           | §7       |

---

## 1. 概述与目标

### 1.1 一句话定位

**为 GTSN-Terrain 世界提供基于现有地形噪声的 5 维气候数据层（温度/湿度/气流/紫外线/光照）、对世界机制的真实影响、3 种带完整物理效果的灾害（龙卷风/沙尘暴/洪水），并通过 Forge 事件总线、公开 API 类与 KubeJS 绑定三层通道向其他模组与脚本开放。**

### 1.2 设计原则（约束后续所有实现）

1. **服务器权威**：所有气候计算、影响结算、灾害模拟只在服务端执行；客户端只收结果（方块替换靠 `setBlock(flag 3)` 自动同步，每玩家数据走 SimpleChannel S2C）——见调研报告 §6。
2. **纯函数核心**：气候噪声层（`ClimateNoise`）为纯函数，同一 (seed, x, z, y, tick) 结果恒定、线程安全——延续 `BiomeLayoutNoise` 的既有模式（`docs/research/05` §1 落点 5）。
3. **Forge 事件优先，侵入为零**：项目当前**无 Mixin 配置**（build.gradle 确认），影响系统一律走 Forge 事件 + 自有 tick 服务 + 方块替换队列，不改写原版类。
4. **复用既有地形数据**：温度/湿度直接复用 `BiomeLayoutNoise.temperature/humidity`（seed+101/102）与 `HeightMapBuilder.continentalness/getHeight`，气候层只新增 4 个独立噪声通道（seed+301~304），避免重复计算与串扰。
5. **深模块**：每个模块对外只暴露一个窄接口（参考 api-and-interface-design 的深模块原则），详见 §8。

### 1.3 非目标（YAGNI，明确不做）

| 不做                                                | 理由                                                  |
| --------------------------------------------------- | ----------------------------------------------------- |
| 不引入 Mixin / coremod                              | build.gradle 无对应配置，Forge 事件通道已覆盖全部需求 |
| 不新增灾害类型（冰雹/热浪/暴风雪等）                | 超出确认范围                                          |
| 不修改地形生成算法（高度图/群系/洞穴）              | 子项目 A 已冻结，气候层只**读取**其输出               |
| 不做 3D 风场流体模拟（CFD）                         | 气流维度用噪声 + 高度因子近似，物理不精确但可玩       |
| 不做湿度驱动的植被演化（地图重绘）                  | 成本高、破坏已生成世界                                |
| 不做玩家体温 HUD / 温度状态条 UI（第一版）          | 需要客户端每玩家 UI 与资源，列为后续可选              |
| 不做 NPC/动物行为 AI 对气候的反应                   | 超出确认范围                                          |
| 不做下界/末地气候                                   | 非覆盖维度返回退化值（见 §2.5）                       |
| 不维护多模组温度互操作协议（与 SereneSeasons 联调） | 只做"检测共存并降级"，不做双写协调（见 §11 开放问题） |

---

## 2. 5 维气候数据层设计

### 2.1 总览

| 维度             | 单位                      | 数值范围                          | 主要驱动因素                                               | 动态时间尺度                        |
| ---------------- | ------------------------- | --------------------------------- | ---------------------------------------------------------- | ----------------------------------- |
| 温度 temperature | °C                        | [-40, +45]                        | 纬度（z 向）+ 群系噪声 + 海拔 + 季节 + 日夜 + 天气尺度扰动 | 季节（8 天级）+ 日夜（24k tick 级） |
| 湿度 humidity    | 相对湿度 0~1              | [0.05, 1.0]                       | 大陆度（距海距离）+ 群系噪声 + 降雨动态提升 + 季节         | 降雨（分钟级缓变）                  |
| 气流 airflow     | m/s                       | [0, 30]（灾害期间临时突破至 60+） | 大气环流噪声 + 海拔高度 + 日夜热力 + 灾害增幅              | 日夜 + 灾害瞬变                     |
| 紫外线 uvIndex   | UVI 指数                  | [0, 12]                           | 太阳高度角（时刻）+ 海拔 + 天气遮挡 + 季节 + 噪声          | 日夜 + 天气                         |
| 光照 lightLevel  | 亮度等级（对齐原版 0~15） | [0, 15]                           | 天体亮度 + 大气衰减（雨/雾/沙尘暴）+ 海拔微调              | 日夜 + 天气                         |

**单位决策**：温度对外用摄氏度（影响阈值直观：0°C 结冰、15~~25°C 作物最优）；湿度用 0~~1 相对湿度；气流用 m/s（可对照蒲福风级：>10.8 为 6 级强风，>20.8 为 9 级烈风）；紫外线用标准 UVI 指数（0~~11+，>7 为"极高"）；光照对齐原版亮度 0~~15，便于其他模组直接使用。

### 2.2 噪声基础设施（种子偏移规划）

延续既有偏移模式（BiomeLayoutNoise = seed+101~~103、CaveNoise = seed+201/202），气候层使用**独立段 seed+301~~304**，与地形/群系/洞穴完全隔离、可独立调参：

| 通道                | 种子     | 类型               | 频率   | 八度 | 用途                               |
| ------------------- | -------- | ------------------ | ------ | ---- | ---------------------------------- |
| climateTempNoise    | seed+301 | OpenSimplex2 + FBm | 0.0008 | 4    | 温度天气尺度扰动（数百方块级热团） |
| climateWindNoise    | seed+302 | OpenSimplex2 + FBm | 0.0004 | 3    | 气流强度（大气环流带）             |
| climateWindDirNoise | seed+303 | OpenSimplex2 + FBm | 0.0002 | 2    | 风向角度（0~2π）                   |
| climateUvNoise      | seed+304 | OpenSimplex2 + FBm | 0.0010 | 3    | 紫外线小尺度扰动（云缝/雾霾）      |

- 构建方式与 `BiomeLayoutNoise.noise(seed, frequency, octaves)` 完全一致（`SetSeed(int)` + `SetNoiseType(OpenSimplex2)` + `SetFractalType(FBm)` + `SetFrequency` + `SetFractalOctaves`），实现可复制该私有工厂。
- 湿度**不新增**噪声通道：直接复用 `BiomeLayoutNoise.humidity(x, z, continentalness)`（其内部已含 seed+102 湿度噪声与大陆度干燥偏移），气候层只叠加动态修正（降雨/季节）。
- `ClimateNoise` 构造器签名：`ClimateNoise(long seed, BiomeLayoutNoise biomeLayout, HeightMapBuilder heightMap)`——依赖注入而非内部 new，这是 JUnit 测试 seam（§9）。
- 所有方法纯函数：`float temperature(double x, double z, int y)`、`float humidity(double x, double z, double continentalness, boolean raining, float rainBoost, Season season)`、`float airflow(...)`、`float uvIndex(...)`、`float windDirection(double x, double z)`。输入输出均为方块坐标。

### 2.3 各维生成公式（参数来源：现有代码 + 调研参考）

#### 2.3.1 温度（°C）

```
base      = BiomeLayoutNoise.temperature(x, z)                  // [-1,1]：纬度效应(±z/8000) + 噪声(seed+101) —— 直接复用
biomeBase = 15.0 + base * 25.0                                  // 映射到 [-10, +40] °C（15°C 为赤道基准）
altitude  = -0.0065 * max(0, y - SEA_LEVEL)                     // 环境递减率：每 1000m -6.5°C（标准大气）
seasonal  = SeasonOffset(season, subSeason)                     // 春 0 / 夏 +8 / 秋 0 / 冬 -8，亚季节间线性插值
diurnal   = 3.0 * cos(2π * (dayTime - 6000) / 24000)            // 原版 6000 tick = 正午，最热；午夜最冷（±3°C）
noise     = climateTempNoise(x, z) * 1.5                        // 天气尺度热团扰动 ±1.5°C（seed+301）
temperature = clamp(biomeBase + altitude + seasonal + diurnal + noise, -40.0, 45.0)
```

- 参数来源：纬度与噪声直接复用 `BiomeLayoutNoise.temperature`（其 `LATITUDE_FULL_RANGE=8000`、`TEMPERATURE_NOISE_AMPLITUDE=0.35` 已定）；季节偏移参考 SereneSeasons 的"群系温度 + 季节修正再 clamp"思路（调研报告 §3 `getBiomeTemperatureInSeason`），但 SS 修正的是原版 biome 温度，我们修正的是自算温度场。
- 海拔项使用 `HeightMapBuilder.getHeight(x, z)` 得到地表 Y 作为参考值（§2.5 缓存策略），查询时按实际 y 差即时修正。

#### 2.3.2 湿度（0~1 相对湿度）

```
base       = 0.5 + 0.5 * BiomeLayoutNoise.humidity(x, z, continentalness)   // [-1,1]→[0,1]
             // continentalness 来自 HeightMapBuilder.continentalness(x, z)（种子 seed+2 段），
             // BiomeLayoutNoise 内部已乘 CONTINENTAL_DRYNESS=0.6 —— 大陆度越高越干，即"距海越远越干"
rainBoost  = raining ? lerp(rainBoost, 0.25, 0.005) : lerp(rainBoost, 0.0, 0.005)
             // 降雨期间每 tick 向 0.25 逼近（平滑上升/回落，避免跳变）
seasonal   = (season ∈ {WINTER, TROPICAL_RAINY}) ? +0.10 : -0.05
humidity   = clamp(base + rainBoost + seasonal, 0.05, 1.0)
```

- 驱动因素即需求原文："湿度随大陆度"——由 `continentalness` 项直接实现。

#### 2.3.3 气流（m/s）+ 风向

```
terrainWind = |climateWindNoise(x, z)| * 12.0 + 3.0             // 环流带基础风 3~15 m/s（seed+302）
heightFactor = clamp(1.0 + (y - 80) / 300.0, 0.5, 3.0)          // 高空风速放大（80 格为基准，y 640 顶约 ×2.9）
diurnal     = 2.0 * max(0.0, sin(2π * (dayTime - 6000) / 24000)) // 午后热力风增强 0~2 m/s
stormBoost  = 沙尘暴活跃 ? 12.0 : (龙卷风活跃 ? 8.0 : 0.0)       // 灾害期间抬升风速（数值由灾害系统覆盖，此处仅输入）
airflow     = clamp(terrainWind * heightFactor + diurnal + stormBoost, 0.0, 30.0)
windDir     = climateWindDirNoise(x, z) * π                      // [0, 2π) 弧度，供粒子偏转/火蔓延偏置
```

#### 2.3.4 紫外线（UVI 0~12）

```
solar       = max(0.0, sin(π * (dayTime - 6000) / 12000))        // 日出 0 → 正午 1 → 日落 0（6000=正午，18000=午夜）
altitude    = 1.0 + max(0, y - 62) / 1000.0 * 0.9                // 每 1000m +90%（臭氧层吸收减薄近似）
weatherAtten = 降雨/雷暴 ? 0.40 : (多云 ? 0.75 : 1.0)            // 云层遮挡
seasonal    = (season == SUMMER) ? +1.5 : (season == WINTER ? -1.5 : 0.0)
noise       = climateUvNoise(x, z) * 0.8                         // 云缝扰动（seed+304）
uvIndex     = clamp((solar * 8.0 + seasonal) * altitude * weatherAtten + noise, 0.0, 12.0)
```

- "多云"判定复用 §3 湿度雾逻辑：湿度 > 0.75 视为多云，与湿度维度联动。

#### 2.3.5 光照（0~15 亮度等级）

```
skyBright = f(level.getSkyDarken())                              // 原版天体亮度 [0,1]（含日夜/天气，昼夜自动覆盖）
atmosphere = 沙尘暴 ? 0.35 : (降雨 ? 0.70 : (湿度 > 0.75 ? 0.85 : 1.0))  // 大气衰减
lightLevel = clamp(skyBright * 15.0 * atmosphere, 0.0, 15.0)
```

- 语义：**环境光照度**（天体-大气有效光照），不含方块遮蔽——方块遮蔽由原版光照系统负责，本维度代表"这个位置的天光被大气吃掉多少"，供作物修正/紫外线联动/脚本使用。
- 这是唯一一个依赖 `Level` 运行态的维度（其余 4 维可纯函数计算），实现上单独抽取。

### 2.4 时间变化（季节/日夜 tick 策略）

- **季节状态机** `SeasonState`（LevelSavedData 持久化，key `gtsn_climate_seasons`）：
  - 主季节 `Season`：SPRING / SUMMER / AUTUMN / WINTER（热带群系判定后切换 TROPICAL_DRY / TROPICAL_RAINY，参考 SereneSeasons 的 `TROPICAL_BIOMES` 思路——用 tag `gtsn:tropical_biomes` 判定，判定发生在查询时，存储仍按四季推进）。
  - 亚季节插值：每季节拆 3 档（早/中/晚），`seasonal` 项按档位线性插值，避免季节切换跳变。
  - 推进节奏：每 `24000 * seasonLengthDays` tick 进一季，默认 `seasonLengthDays = 8`（可配置），由 ServerTickEvent 驱动，**每 1200 tick 检查一次**（不每 tick 写 SavedData，只在季变时 setDirty）。
- **日夜**：不另存状态，查询时用 `level.getDayTime()` 现算时角（`dayTime % 24000`），零存储成本。
- **降雨动态**：`rainBoost` 为 Level 级浮点状态（存 SavedData，随降雨开关缓变），由天气集成（§3.2.3）写入。

### 2.5 存储与查询（缓存与性能）

| 层级                        | 内容                                                                | 策略                                                                                   |
| --------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Level 级 `ClimateDataCache` | `ConcurrentHashMap<ChunkPos, ClimateChunkData>`                     | 服务端每 Level 一份；客户端不计算（按需 S2C，见里程碑 M-B5）                           |
| 区块级 `ClimateChunkData`   | 4×4 采样网格（16 个采样点，间距 4 方块）× 5 维地表参考值 + 生成时刻 | 记录**地表参考值**（按 `HeightMapBuilder.getHeight` 的 Y 采样），TTL 600 tick（30 秒） |
| 查询 `query(level, pos)`    | 双线性插值采样网格 + 即时解析项修正                                 | 海拔/日夜/降雨修正为廉价解析项，不入缓存；温度按 (实际 y − 地表 Y) 即时加海拔修正      |

- **为什么缓存地表值而不是每 y 列缓存**：同一列不同 y 温度不同，全列缓存成本爆炸；海拔修正是 O(1) 解析式，插值 + 修正总成本 ≈ 2~3 次浮点运算。
- **失效时机**：TTL 到期、季节变化、降雨开关翻转（全量标记失效，不逐项删）。
- **性能预算**：缓存命中时单次查询 0 次噪声采样；未命中（冷区块）≈ 4 次噪声采样 + 2 次双线性插值。实体影响结算每 20 tick 查一次，单实体年开销可忽略。百万级查询基准测试列入里程碑 M-B5 验收。
- **多维度**：`ClimateDataCache` 按 Level 隔离（key 含 dimension）；下界/末地返回退化值（温度 = 固定 20°C、湿度 0.5、气流 0、UV 0、光照 = 原版亮度），不参与灾害触发。
- 线程安全：`ClimateChunkData` 为不可变 record；map 用 ConcurrentHashMap；写入仅在服务端主线程（LevelTickEvent），读取跨线程安全（§6.4）。

---

## 3. 实际影响表

### 3.1 影响矩阵总览

| 维度   | 影响的游戏机制                                                             | 实现通道                                       | 详见   |
| ------ | -------------------------------------------------------------------------- | ---------------------------------------------- | ------ |
| 温度   | 水面结冰 / 冰融化、降雪 vs 降雨、作物生长速率、玩家寒冷/中暑伤害、耕地冻结 | 方块替换队列 + Forge 事件 + 实体 tick          | §3.3.1 |
| 湿度   | 降雨概率与雨强、雾（能见度）、耕地湿润/干燥、火势蔓延速率                  | 天气集成 + FogEvent + 方块替换队列 + tick 服务 | §3.3.2 |
| 气流   | 强风推挤实体（玩家/掉落物）、火蔓延方向偏置、粒子偏转                      | 实体 tick + 方块随机事件拦截 + 客户端粒子      | §3.3.3 |
| 紫外线 | 玩家晒伤、作物生长修正、冰/雪加速融化、易燃方块自燃概率                    | 实体 tick + Forge 事件 + 方块替换队列          | §3.3.4 |
| 光照   | 作物生长修正（与温度/UV 联动）、环境亮度感知（API/脚本）                   | Forge 事件 + 纯数据（供外部消费）              | §3.3.5 |

### 3.2 实现通道（统一机制，贯穿整个影响系统）

1. **Forge 事件通道**（无侵入）：`CropGrowPostEvent`（修正生长）、`FogEvent.RenderFog`（雾）、`EntityHurtEvent` 之外的自主伤害（见下）。
2. **自有服务 `ClimateTickService`**（LevelTickEvent 驱动，每 20 tick）：实体效果结算（玩家寒冷/晒伤/风推，对**玩家实体**逐人结算，不做全实体扫描——性能红线）；方块效果按**区块轮转**抽样（每 tick 选 1/16 区块的少量方块，全图摊平）。
3. **方块替换队列 `ClimateBlockQueue`**（Weather2 BlockUpdateSnapshot 模式，调研报告 §5）：判定 → 备份旧状态入队 → 消费队列执行 `level.setBlock(pos, newState, 3)`（flag 3 自动同步客户端，调研报告 §6）。结冰/融雪/洪水/龙卷风破坏统一走此队列，每 tick 消费上限见 §4.5。
4. **天气集成**（服务端）：`level.getServer().overworld().setRainLevel(目标值)` 平滑调雨——由湿度 + 温度（<0°C 时雨变雪，走原版降雪机制）驱动，每 20 tick 一次。
5. **客户端表现**：`FogEvent`（雾/沙尘暴视野）、自定义 `ParticleType`（DeferredRegister 注册，`level.addParticle` 派发）、自定义 `SoundEvent`。客户端不持有气候数据源，所有视觉输入由服务端状态驱动（S2C 包按需，见 M-B5）。

### 3.3 逐条细则（阈值 + 修正系数 + 实现机制）

#### 3.3.1 温度影响

| 机制         | 条件/阈值                                                                                                             | 效果                                                 | 实现                                               |
| ------------ | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | -------------------------------------------------- |
| 水面结冰     | 温度 < 0°C 且方块为水源（流动水不结，保原版动态）且上方无遮蔽                                                         | 水源 → 冰                                            | 方块替换队列，区块轮转抽样                         |
| 冰融化       | 温度 > 2°C 且光照维度 > 10                                                                                            | 冰 → 水源                                            | 方块替换队列                                       |
| 降雪 vs 降雨 | 温度 < 0°C                                                                                                            | 雨转为雪（原版雪按 biomes 判定，此处按气候判定覆盖） | 天气集成：setRainLevel + 原版降雪                  |
| 作物生长     | 温度 < 0°C → ×0（冻结停长）；0~~10 → ×0.5；10~~25 → ×1.0；25~35 → ×1.25（高温促进）；>35 → ×0.5（热胁迫）；>40 → ×0.1 | 生长速率修正                                         | `CropGrowPostEvent` 里按 `result` 加权（MODIFIED） |
| 玩家寒冷     | 温度 < 0°C，每 40 tick 结算一次，暴露且非雨天加权                                                                     | 每 80 tick 1 点冰冻伤害（无霜甲时）                  | 实体 tick（仅玩家）                                |
| 玩家中暑     | 温度 > 40°C                                                                                                           | 每 100 tick 1 点热伤害                               | 实体 tick（仅玩家）                                |
| 耕地冻结     | 温度 < -5°C 且耕地水分=0                                                                                              | 耕地 → 泥土                                          | 方块替换队列（低频，仅寒冷区）                     |

#### 3.3.2 湿度影响

| 机制           | 条件/阈值                                                              | 效果                                          | 实现                                                                                       |
| -------------- | ---------------------------------------------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 降雨概率/雨强  | 湿度 > 0.70 且无雨 → 有概率进入降雨；雨强 = (湿度 − 0.70) × 2 映射 0~1 | 干旱区（沙漠 0.1）几乎无雨，雨林（0.95+）多雨 | 天气集成                                                                                   |
| 雾（能见度）   | 湿度 > 0.80 且温度 < 15°C；或湿度 > 0.90（晨雾）                       | 雾距离 64~96 格，湿度越高越浓                 | `FogEvent.RenderFog`                                                                       |
| 耕地湿润       | 湿度 > 0.80 且方块为耕地                                               | 水分置 7（湿润）                              | 方块替换队列                                                                               |
| 耕地干燥       | 湿度 < 0.20                                                            | 水分置 0 且加速向泥土退化（原版逻辑加速）     | 方块替换队列 + tick                                                                        |
| 火势蔓延       | 湿度 > 0.85                                                            | 火蔓延概率 ×0.3（高湿压火）                   | ClimateTickService 随机熄灭 + `FireBlock` 相邻检查拦截（通过替换烧灼方块达成，不碰原版类） |
| 沙尘暴触发输入 | 湿度 < 0.15                                                            | 参与灾害阈值组合（§4.3）                      | 灾害系统                                                                                   |

#### 3.3.3 气流影响

| 机制                  | 条件/阈值                       | 效果                                                | 实现                                                |
| --------------------- | ------------------------------- | --------------------------------------------------- | --------------------------------------------------- |
| 强风推挤实体          | 气流 > 10 m/s，玩家/掉落物/箭矢 | 沿风向施加 0.02~0.08 加速度（越强越大），掉落物翻倍 | 实体 tick（仅影响玩家 + 掉落物 + 箭，低成本白名单） |
| 火蔓延偏置            | 气流 > 5 m/s                    | 顺风方向蔓延概率 ×2.0，逆风 ×0.5                    | ClimateTickService 对火方块邻域概率修正             |
| 粒子偏转              | 任何风速                        | 雨/雪/沙尘粒子沿 windDir 水平偏移                   | 客户端粒子派发参数                                  |
| 龙卷风/沙尘暴触发输入 | 气流 > 12 / 20 m/s              | 参与灾害阈值组合（§4）                              | 灾害系统                                            |

#### 3.3.4 紫外线影响

| 机制          | 条件/阈值                                  | 效果                                              | 实现                                       |
| ------------- | ------------------------------------------ | ------------------------------------------------- | ------------------------------------------ |
| 玩家晒伤      | UVI > 7 且正午窗口且无方块/水遮挡          | 每 60 tick 1 点伤害，护甲不减免（可用图腾等保命） | 实体 tick（仅玩家）                        |
| 作物生长      | UVI 3~7 → ×1.2（促进）；>10 → ×0.6（灼伤） | 生长速率修正                                      | `CropGrowPostEvent`                        |
| 冰/雪加速融化 | UVI > 6                                    | 融化判定温度阈值从 2°C 降到 0°C                   | 方块替换队列（与温度共享，UV 作修正项）    |
| 自燃          | UVI > 9 且温度 > 38°C 且湿度 < 0.15        | 易燃方块（干草/树叶）小概率自燃                   | ClimateTickService 轮转抽样，setBlock 火焰 |

#### 3.3.5 光照影响

| 机制         | 条件/阈值 | 效果                                           | 实现                       |
| ------------ | --------- | ---------------------------------------------- | -------------------------- |
| 作物生长     | 光照 < 8  | 生长 ×0.5（与温度/UV 乘性叠加）                | `CropGrowPostEvent`        |
| 环境亮度感知 | 任意      | 对外提供统一光照值（沙尘暴遮天时作物生长骤降） | 纯数据，供 API/KubeJS 消费 |
| 与温度联动   | 光照 < 4  | 寒冷结算加快 1.5 倍（夜晚更冷）                | 实体 tick 修正项           |

**影响表实现优先级**：第一版实现 3.3 表内全部条目，其中"耕地冻结/自燃/火蔓延偏置"为低频低优先级（若里程碑排期紧张可降级为后续补丁，验收时以 M-B2 清单为准）。

---

## 4. 灾害系统设计

### 4.1 通用框架（3 种灾害共享）

```
enum DisasterState { DORMANT, FORMING, ACTIVE, DISSIPATING }
enum DisasterType { TORNADO, DUST_STORM, FLOOD }
interface ActiveDisaster {
    DisasterType type();
    DisasterState state();
    BlockPos center();
    float strength();            // 0~1，由超阈程度决定
    int radius();
    void tick(Level level, long dayTime);   // 状态机推进 + 物理效果
    boolean shouldPersist();     // 是否写入 SavedData（崩溃恢复）
}
class DisasterManager {          // LevelSavedData：List<ActiveDisaster> + 触发统计
    void onServerTick(Level level, long tick);   // 每 100 tick 触发判定 + 每 tick 推进活跃灾害
    void spawn(DisasterType type, BlockPos center, float strength);
    List<ActiveDisasterInfo> snapshot();          // 供 API/事件/网络
}
```

- **触发判定**（每 100 tick = 5 秒，对每个**已加载**区域中心采样气候，不扫全图）：阈值组合 + 概率 + config 开关。判定逻辑抽成纯函数 `DisasterTrigger.evaluate(ClimateData data, long tick)` → `Optional<DisasterType>`——这是 JUnit 测试主目标（§9）。
- **生命周期**：DORMANT →（触发）→ FORMING →（酝酿完成）→ ACTIVE →（时长到）→ DISSIPATING →（消散完）→ DORMANT。转移非法性由状态机校验，测试覆盖。
- **持久化**：`DisasterManager` 存 SavedData（key `gtsn_climate_disasters`），服务端重启恢复进行中灾害（龙卷风从 DISSIPATING 恢复或直接终止——设计为**终止**，避免重启后残留破坏源）。
- **事件钩子**：状态机每次合法转移 post 对应 Forge 事件（§5）。

### 4.2 龙卷风（TornadoDisaster）

**触发**：气流 > 20 m/s 持续 ≥ 300 tick + 温度 > 25°C + 湿度 > 0.80（雷暴条件）→ 概率 30%/5 秒检查窗口。

**生命周期**（tick 数均可配置）：

| 阶段        | 时长                           | 特征                                                                            |
| ----------- | ------------------------------ | ------------------------------------------------------------------------------- |
| FORMING     | 1200（60 秒）                  | 漏斗雏形可见，中心向触发点漂移（路径噪声驱动），无破坏                          |
| ACTIVE      | 1800~~3600（90~~180 秒，随机） | 全物理效果；中心沿低频噪声路径漂移（简化 CatmullRomSpline，调研报告 §5 落点 4） |
| DISSIPATING | 600（30 秒）                   | 强度线性衰减至 0，停止破坏与吸引                                                |

**物理效果实现机制**（逐条对齐调研报告 §5 Weather2）：

1. **方块破坏（卷起方块）**：
   - 判定 `shouldRemoveBlock(state)`：非基岩/黑曜石/命令方块/方块实体底座、硬度 ≤ 2.5、不在 tag `gtsn:tornado_immune`（玩家可扩展保护）；
   - 命中 → 旧状态入 `BlockUpdateSnapshot` 队列（含维度、位置、旧状态），**玩家近距离（< 48 格）时实体化**：将方块变为 `ItemEntity` 抛起（参考 Weather2 `shouldEntityify`——防全图掉物刷，调研报告 §5 L89-L97）；
   - 队列消费：`level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)`（flag 3 通知客户端，调研报告 §5 L184-L192）；
   - 破坏范围：中心半径 `radius`（默认 12）内、`topY = 地表 + maxHeight`（默认 96）柱体；每 tick 破坏上限 `tornadoMaxBlockOps = 48`（§4.5）。
2. **实体吸引（卷起实体）**：
   - 每 5 tick：`level.getEntitiesOfClass(Entity.class, new AABB(center).inflate(radius, maxHeight * 3.8, radius))`（对齐 Weather2 AABB 方案，调研报告 §5 L655-L720）；
   - 对 `canGrabEntity(e)`（非玩家时可抓，玩家给强推力不抓取——防玩家被控死）执行 `spinEntity`：切向速度（绕中心旋转）+ 上抛分量 + 向中心收缩，`setDeltaMovement(vec)` + `setYRot(angle)`；
   - 结束后实体落地自然摔落（原版摔落伤害兜底）。
3. **客户端表现**：自定义 `TornadoParticle`（漏斗旋转粒子，SimpleParticleType 轻量方案，调研报告 §5 落点 3）；风声 `SoundEvent`（客户端 tick 依距离衰减播放）；可选屏幕震动（S2C 包，M-B5 网络清单内）。
4. **防误伤**：破坏不越过 `gtsn:tornado_immune` tag 方块；玩家建筑默认**会被破坏**（物理真实），config 可整体关闭破坏只留实体吸引。

### 4.3 沙尘暴（DustStormDisaster）

**触发**：温度 > 35°C + 湿度 < 0.15 + 气流 > 12 m/s，持续 ≥ 600 tick → 概率 40%/5 秒窗口。典型发生地：沙漠/干旱区（由气候维度自动定位，无需硬编码群系）。

**生命周期**：FORMING 600 → ACTIVE 2400~~6000（2~~5 分钟）→ DISSIPATING 600。

**物理效果**：

1. **视野影响**：`FogEvent.RenderFog` 覆盖——沙黄染色 + 能见度压到 24~48 格（ACTIVE 时最低 16 格），消散期线性恢复。
2. **实体影响**：
   - 室外（无方块遮蔽头）每 40 tick 1 点窒息伤害（参考 Weather2 沙尘暴伤害语义，但不扫全实体——仅玩家 + 范围内的被动生物按区块轮转抽检）；
   - 玩家移动减速 30%（施加与 windDir 反向的阻力加速度）；
   - 掉落物/箭矢沿风向强力偏转（复用 §3.3.3 强风通道，系数 ×3）。
3. **方块影响（克制）**：不做沙子堆积（非目标）；ACTIVE 期间火方块自然熄灭概率提升（每 100 tick 轮转抽 N 个火焰方块替换为空气）。
4. **客户端表现**：`DustParticle`（SimpleParticleType，棕色尘土，沿 windDir 高速水平飘移）+ 沙沙声 `SoundEvent` + 上述雾效果。

### 4.4 洪水（FloodDisaster）

**触发**：湿度 > 0.95 持续 ≥ 1200 tick 且正在降雨 → 必然进入 FORMING（洪水是持续降雨的累积结果，不做概率）。

**生命周期**：FORMING 600（水位从海面/河面基准开始酝酿）→ ACTIVE（持续至降雨停止后 600 tick）→ DISSIPATING 600~2400（退水）。

**物理效果（真实蔓延水位上涨）**——参考调研报告 §7（flooded 模组：纯服务端 setBlock 改水位）与 §6（setBlock flag 3 自动同步）：

1. **目标水位计算**：`targetWaterY = seaLevel(62) + 1 + floodDepth`；`floodDepth` 由降雨累积器决定（降雨强度 × 时间，封顶 +8 格），ACTIVE 期间每 600 tick 重算一次（雨停后冻结）。
2. **蔓延执行**（每 tick，按区块轮转分片）：
   - 对每个已加载区块，取 `HeightMapBuilder.getHeight` 地表列，凡 `地表Y < targetWaterY` 且当前非水/非基岩/非黑曜石 → 入队 `setBlock(pos, WATER.defaultBlockState(), 3)`；
   - 每 tick 每区块最多 16 列（分片预算，§4.5）；先低洼处（按地表 Y 升序）再高地，保证"真实蔓延"而非全图同时抬水。
3. **退水执行**：降雨停止 600 tick 后，每 600 tick 水位 −1；源块替换回空气时**先 setBlock 空气再触发 `neighborChanged`**（让水按原版流体物理自然流散，调研报告 §7 实现要点），退水速率受 config 控制。
4. **实体影响**：不额外吸引/伤害——水流冲击与原版溺水由原版流体物理自然呈现（真实蔓延的副产物），洪水 ACTIVE 时玩家在水中获得上浮修正（避免卡底）。
5. **客户端表现**：水为原版渲染（零额外成本）；可选水面涟漪粒子（低优先级，可后置）。

### 4.5 性能约束（3 种灾害统一）

| 约束                         | 默认值                  | 说明                                  |
| ---------------------------- | ----------------------- | ------------------------------------- |
| 方块替换队列每 tick 消费上限 | 龙卷风 48 / 洪水 16 列  | 超出的延迟到下 tick，绝不单 tick 爆量 |
| 实体 AABB 扫描频率           | 每 5 tick               | 龙卷风专用；扫描半径 ≤ 32             |
| 触发判定频率                 | 每 100 tick             | 全维度，成本可忽略                    |
| 灾害同时活跃上限             | 2（同维度）             | 超限时新灾害排队（防叠爆）            |
| 粒子预算                     | 单灾害 ≤ 200 活粒子     | 客户端侧，用粒子存活计数自限          |
| 存档写入                     | 仅状态机转移时 setDirty | 不在每 tick 写 SavedData              |
| config 总开关                | `gtsn.disaster.enabled` | 整合包可一键关闭灾害系统              |

---

## 5. Forge 事件 API 设计

**注册方式**：全部 post 到 **`MinecraftForge.EVENT_BUS`**（Forge 总线，非 MOD 总线）——这是 1.20.1 生态标准，其他模组直接 `@SubscribeEvent` 即可监听，无需依赖本模组的注册器。事件类定义在 `com.gtsn.terrain.api.event` 包（api 模块，零实现依赖）。

| 事件类（extends Event）      | 关键字段                                                                                                                          | 触发时机                                                                                       | 备注                                            |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| `ClimateDataChangedEvent`    | `Level level`、`BlockPos pos`、`ClimateData oldData`、`ClimateData newData`                                                       | 区块缓存刷新且任一维度变化超阈值（温度 ±1.0°C、湿度 ±0.05、气流 ±1.0 m/s、UV ±0.5、光照 ±1.0） | 数据层显著变化通知，供 HUD/联动模组             |
| `ClimateSeasonChangedEvent`  | `Level level`、`Season from`、`Season to`、`int dayOfSeason`                                                                      | 季节状态机推进（每 8 天一换）                                                                  | 供作物计划/活动联动                             |
| `DisasterStartedEvent`       | `Level level`、`DisasterType type`、`BlockPos center`、`float strength`、`int radius`；**可取消**（`setCanceled(true)` 阻止生成） | FORMING 阶段开始（触发判定通过）                                                               | `@Cancelable`，脚本 `event.cancel()` 对应（§7） |
| `DisasterEndedEvent`         | `Level level`、`DisasterType type`、`BlockPos center`、`EndReason reason`（NATURAL / CANCELLED / CONFIG_DISABLED）                | DISSIPATING 结束或中途取消                                                                     | 清理钩子                                        |
| `ClimateWeatherChangedEvent` | `Level level`、`boolean raining`、`float rainStrength`、`boolean isSnow`                                                          | 天气集成切换降雨/降雪状态时                                                                    | 供其他模组联动原版天气表现                      |
| `DisasterPhaseChangedEvent`  | `Level level`、`DisasterType type`、`BlockPos center`、`DisasterState from`、`DisasterState to`                                   | 活跃灾害状态机每次合法转移                                                                     | 细粒度观察点（如"ACTIVE 开始"）                 |

**事件发布约定**：

1. 所有事件**只在服务端** post（客户端不触发）；
2. `DisasterStartedEvent` 之外的取消语义由 `@Cancelable` 显式声明，默认不可取消；
3. 事件处理器内**禁止**调用会再触发事件的 API（防重入），实现中在 post 前设置 `publishing` 标志；
4. 监听器异常不得影响灾害 tick（post 包 try-catch + LOGGER.error，不吞异常）。

**KubeJS 事件组**（§7 的 `GtsnTerrainEvents`）是上述 Forge 事件的可脚本化镜像，二者独立发布，不互相转化（避免双倍 post 语义混乱）。

---

## 6. 公开 API 设计

**包边界**：`com.gtsn.terrain.api`（冻结契约，里程碑 M-B1 评审后不得破坏性修改；新增只允许加方法）。

### 6.1 核心数据类型

```java
// 不可变值对象：一次查询的 5 维快照
public record ClimateData(
    float temperature,   // °C，[-40, +45]
    float humidity,      // 相对湿度 [0, 1]
    float airflow,       // m/s [0, 30]（灾害期可 >30）
    float uvIndex,       // UVI [0, 12]
    float lightLevel,    // 亮度 [0, 15]
    long timestamp       // 查询时刻（level tick），用于调用方判断时效
) {}

public enum Season { SPRING, SUMMER, AUTUMN, WINTER, TROPICAL_DRY, TROPICAL_RAINY }

public enum DisasterType { TORNADO, DUST_STORM, FLOOD }

// 灾害快照（只读，供 API/事件/网络共享）
public record ActiveDisasterInfo(DisasterType type, DisasterState state,
                                 BlockPos center, float strength, int radius) {}
public enum DisasterState { DORMANT, FORMING, ACTIVE, DISSIPATING }
```

### 6.2 服务接口（供其他模组注入/获取）

```java
public interface ClimateService {
    ClimateData getClimate(Level level, BlockPos pos);          // 5 维快照（缓存 + 修正）
    float temperature(Level level, BlockPos pos);               // °C —— 便捷方法，语义同 getClimate().temperature()
    float humidity(Level level, BlockPos pos);                  // 0~1
    float airflow(Level level, BlockPos pos);                   // m/s
    float uvIndex(Level level, BlockPos pos);                   // UVI
    float lightLevel(Level level, BlockPos pos);                // 0~15
    Season getSeason(Level level);                              // 当前主季节
    int getSeasonDay(Level level);                              // 当前季节第几天
    boolean isDisasterActive(Level level, DisasterType type);   // 该维度该灾害是否活跃
    List<ActiveDisasterInfo> getActiveDisasters(Level level);   // 全量快照
}

// 服务定位：懒加载单例，实现类在服务端注册
public final class ClimateServices {
    public static ClimateService get() { /* 返回已注册实现；未初始化时返回 Noop 占位（防 ClassNotFound 崩服） */ }
}
```

### 6.3 静态门面（KubeJS 绑定目标，README 已约定形态）

```java
public final class GtsnTerrainAPI {
    private GtsnTerrainAPI() {}
    public static final class Climate {
        public static float getTemperature(Level level, BlockPos pos);
        public static float getHumidity(Level level, BlockPos pos);
        public static float getAirflow(Level level, BlockPos pos);
        public static float getUVIndex(Level level, BlockPos pos);
        public static float getLightLevel(Level level, BlockPos pos);
        public static ClimateData getClimate(Level level, BlockPos pos);
        public static Season getSeason(Level level);
        public static boolean isDisasterActive(Level level, DisasterType type);
        public static List<ActiveDisasterInfo> getActiveDisasters(Level level);
    }
    public static final class Disasters {
        public static boolean forceStart(ServerLevel level, DisasterType type, BlockPos center); // 调试/脚本强触发
    }
}
```

### 6.4 线程安全说明

1. `ClimateData` / `ActiveDisasterInfo` 不可变；`ClimateServices.get()` 返回单例，线程安全。
2. 数值计算层（`ClimateNoise`）无状态纯函数，任意线程可调。
3. 缓存层：`ConcurrentHashMap` 读写安全；但**含 `Level` 参数的方法要求在拥有该 Level 的线程调用**（Forge 规则：世界对象访问限主线程），API 文档注明——其他模组在 ServerTickEvent 内调用为推荐姿势。
4. 禁止在客户端调用返回灾害状态的查询（服务端权威，客户端返回 `isDisasterActive=false` 占位，避免双端状态分叉）。

---

## 7. KubeJS 绑定设计

### 7.1 插件类（根包约束，调研报告 §1 确认）

```java
// 包：com.gtsn.terrain.kubejs —— 继承 dev.latvian.mods.kubejs.KubeJSPlugin（1.20.1 根包，无 @KubeJSPlugin 注解）
public class GtsnKubeJSPlugin extends KubeJSPlugin {
    @Override public void registerBindings(BindingsEvent event) {
        event.add("GtsnTerrain", GtsnTerrainAPI.class);   // 类对象绑定 → 脚本直接调静态方法
        event.add("ClimateData", ClimateData.class);      // record 类，供脚本构造/阅读
        event.add("DisasterType", DisasterType.class);
    }
    @Override public void registerClasses(ScriptType type, ClassFilter filter) {
        filter.allow("com.gtsn.terrain.api");             // 关键：否则 Rhino 报 Cannot access class（调研报告 §1 落点）
    }
    @Override public void registerEvents() {
        GtsnTerrainEvents.GROUP.register();               // EventGroup 必须 register（调研报告 §2）
    }
    @Override public void attachLevelData(AttachDataEvent<LevelData> event) {
        // 可选：气候缓存挂 level.attached.gtsn_climate（调研报告 §1 落点 2），供脚本免查询取缓存
    }
}
```

### 7.2 插件发现（kubejs.plugins.txt，1.20.1 唯一机制）

```
# 资源文件：src/main/resources/kubejs.plugins.txt（每行一个全限定类名）
com.gtsn.terrain.kubejs.GtsnKubeJSPlugin
```

- KubeJS 启动时扫描每个 mod jar 的 `findResource("kubejs.plugins.txt")` 自动发现（调研报告 §1 L46），**无需任何注解**。
- **依赖声明**：KubeJS 为可选依赖（mods.toml `optional` 声明 + build.gradle `compileOnly`），插件类所在包仅在 KubeJS 存在时被扫描；`GtsnKubeJSPlugin` 类初始化时先 `ModList.get().isLoaded("kubejs")` 守卫（防 ClassNotFound）。
- **类过滤**：`kubejs.classfilter.txt` 可选添加（`+com.gtsn.terrain.api`）——`registerClasses` 已覆盖，此文件仅作冗余保险，不强制。

### 7.3 脚本事件组（EventGroup 三件套，调研报告 §2）

```java
// 包：com.gtsn.terrain.kubejs
public interface GtsnTerrainEvents {
    EventGroup GROUP = EventGroup.of("GtsnTerrainEvents");                    // 脚本全局名
    EventHandler DISASTER_STARTED = GROUP.server("disasterStarted", () -> DisasterStartedEventJS.class);
    EventHandler DISASTER_ENDED   = GROUP.server("disasterEnded",   () -> DisasterEndedEventJS.class);
    EventHandler FLOOD_START      = GROUP.server("floodStart",      () -> FloodStartEventJS.class).hasResult();
}
// EventJS 子类字段：type(DisasterType)、center(BlockPos)、strength、radius + cancel()（hasResult 事件）
```

### 7.4 脚本示例（server_scripts/gtsn_climate.js）

```js
// —— 查询气候（BindingsEvent.add 绑定类对象 → 静态方法直调）——
// 建议挂在玩家 tick / 定时器回调里
const pos = player.blockPosition();
let temp = GtsnTerrain.Climate.getTemperature(level, pos);
let uv = GtsnTerrain.Climate.getUVIndex(level, pos);
console.log(
  `[GTSN] 温度 ${temp.toFixed(1)}°C，紫外线 ${uv.toFixed(1)}，季节 ${GtsnTerrain.Climate.getSeason(level)}`,
);

// —— 5 维快照一次性查询 ——
let c = GtsnTerrain.Climate.getClimate(level, pos);
// c.temperature / c.humidity / c.airflow / c.uvIndex / c.lightLevel

// —— 监听灾害事件（EventGroup 全局名）——
GtsnTerrainEvents.disasterStarted((e) => {
  if (e.type == "TORNADO")
    console.log(`[GTSN] 龙卷风生成于 ${e.center}，强度 ${e.strength}`);
});

GtsnTerrainEvents.floodStart((e) => {
  if (e.level.dimension == "minecraft:the_nether") e.cancel(); // 下界禁止洪水
});
```

---

## 8. 模块化拆分

### 8.1 模块清单与依赖方向

```
                    ┌──────────────┐
                    │  api(纯契约)  │  ← 事件类 + ClimateData/Season/DisasterType + ClimateService 接口 + GtsnTerrainAPI 门面
                    └──────┬───────┘
                           │ 依赖（实现指向契约）
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
    ┌───────────────┐ ┌───────────┐ ┌────────────┐
    │ climate-data   │ │ climate-  │ │ disasters  │
    │ 数据层(深模块) │ │ effects   │ │ 灾害三件套 │
    └───────┬───────┘ └─────┬─────┘ └─────┬──────┘
            └───────────────┼─────────────┘
                            ▼
                   ┌────────────────┐
                   │ kubejs-bridge   │  ← 插件 + 绑定 + 事件组
                   └────────────────┘
                   另有 network 小模块（SimpleChannel S2C），被 climate-effects 与 disasters 依赖（客户端表现用）
```

| 模块（包前缀 com.gtsn.terrain.*） | 职责                                                                          | 对外接口（窄）                                                                                      | 内部（可以复杂）                                      |
| --------------------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `api`                             | 契约定义：事件类、值类型、服务接口、门面                                      | `ClimateService`、`GtsnTerrainAPI`、`api.event.*` 事件类                                            | 无（纯声明，零实现）                                  |
| `climate-data`                    | 数据层：`ClimateNoise`、`SeasonState`、`ClimateDataCache`、`ClimateDataStore` | **`ClimateDataStore.query(level, pos) → ClimateData`**（深模块：内部 4 噪声 + 缓存 + 插值全部隐藏） | 噪声通道、缓存 TTL、插值、SavedData                   |
| `climate-effects`                 | 影响结算：作物/实体/方块替换/天气集成/Fog                                     | 无公开类（全部经 Forge 事件与 tick 服务对外）                                                       | `ClimateTickService`、`ClimateBlockQueue`、各 Handler |
| `disasters`                       | 灾害：状态机 + 3 种灾害 + `DisasterManager`                                   | `DisasterManager`（经 api 快照暴露）                                                                | `BlockUpdateSnapshot` 队列、AABB 吸引、水位计算       |
| `kubejs-bridge`                   | KubeJS 插件/绑定/事件组                                                       | 插件类（KubeJS 发现机制入口）                                                                       | EventGroup 定义、脚本事件适配                         |
| `network`                         | SimpleChannel S2C 包（气候 HUD 快照、灾害视觉触发）                           | `GtsnNetwork` 注册入口                                                                              | 编解码                                                |

### 8.2 边界纪律（深模块原则落地）

1. `climate-data` 是**深模块**典型：外部只见一个 `query`，噪声/缓存/季节全内聚；`climate-effects` 与 `disasters` 只允许调 `ClimateDataStore.query` 与 `api` 契约，**禁止**触碰 `ClimateNoise` 内部。
2. `api` 是**浅模块但稳定契约**：事件类零实现（避免其他模组依赖实现类）；`ClimateService` 为接口，实现类放 `climate-data`，`ClimateServices.get()` 做注册查找。
3. `climate-effects` 与 `disasters` **互不依赖**（灾害的风速输入经气候数据传递，不直接调 effects）。
4. `kubejs-bridge` 只依赖 `api` + `ClimateDataStore` 查询——KubeJS 缺失时该模块类不被加载，模组主体零影响。
5. 新增模块不得反向依赖（`api` 永不 import 其他模块实现）。
6. 包结构 `src/main/java/com/gtsn/terrain/{api,climate,effects,disasters,kubejs,network}/`（`climate` 内含 data 子包），测试镜像 `src/test/java/com/gtsn/terrain/...`。

---

## 9. TDD 测试策略

### 9.1 可纯 JUnit 测试（build.gradle 已配 junit-jupiter 5.10.2，`gradlew test`）

| 测试目标                   | 用例                                                                               | 测试 seam                                                    |
| -------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| `ClimateNoise` 确定性      | 同种子同坐标同 tick → 相同结果（快照断言）                                         | 构造器注入 seed + `BiomeLayoutNoise`/`HeightMapBuilder` 实例 |
| 数值范围不变式             | 5 维在各自范围（随机 1 万点不越界）                                                | 直接调 `ClimateNoise` 纯函数                                 |
| 温度单调性                 | 同 x/z 下 y 越高越冷；z 越北越冷；冬季 < 夏季                                      | 固定 (x,z,y) 扫描                                            |
| 湿度-大陆度方向            | continentalness +0.5 比 −0.5 干                                                    | `humidity(x,z,c)` 双参数对比                                 |
| 阈值判定 `DisasterTrigger` | 阈值组合矩阵（气流/温度/湿度达标→TORNADO 等）                                      | `evaluate(ClimateData, tick)` 纯函数 + 表驱动用例            |
| 灾害状态机                 | 合法转移链 DORMANT→FORMING→ACTIVE→DISSIPATING；非法转移（ACTIVE→DORMANT 直跳）抛错 | 状态机与 tick 源解耦（注入 `long tick` 假时钟）              |
| 季节推进 `SeasonState`     | 8 天推进：SPRING→SUMMER→...；SavedData 序列化 round-trip                           | 假 Level 或纯逻辑剥离（tick→Season 映射纯函数）              |
| 影响系数函数               | `computeCropMultiplier(temp, uv, light)` 阈值段正确（<0°C→0 等）                   | 影响计算抽纯函数，Handler 只做装配                           |
| 水位计算                   | `computeTargetWaterY(rainAccum, seaLevel)` 封顶 +8                                 | 纯函数                                                       |

### 9.2 需 gameTest（gameTestServer run config 已配置，`forge.enabledGameTestNamespaces=gtsnterrain`）

| 测试目标     | 用例                                                                       | 说明                                                                                                                   |
| ------------ | -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| 方块替换通道 | 龙卷风破坏：放测试方块（硬度 ≤ 2.5）→ tick 后断言 AIR；基岩/黑曜石不被破坏 | `GameTestHelper.getLevel()` + `assertBlock`                                                                            |
| 洪水蔓延     | 建凹地形 + 触发洪水 → 断言低洼处变水、水位 ≤ targetWaterY                  | gameTest 里直接调 `DisasterManager.spawn`（跳过随机触发）                                                              |
| 退水         | 停止降雨 → tick 后断言源块回空气、水自然流散                               | 同上                                                                                                                   |
| 实体吸引     | 放测试实体 → 龙卷风 tick 后断言 `getDeltaMovement()` 非零且向中心          | `GameTestHelper.spawn` 假实体                                                                                          |
| 结冰/融雪    | 温度阈值可控（注入固定 ClimateData）→ 水源变冰 → 升温融回                  | gameTest 世界温度由种子决定——需**测试钩子**：`ClimateDataStore#overrideForTest(pos, data)`（仅 gameTest 命名空间启用） |
| 作物修正     | 固定气候下 `CropGrowPostEvent` 结果倍率正确                                | 事件级断言（可降级为 JUnit 纯函数断言，若事件钩子难注入）                                                              |

**测试 seam 汇总**：① `ClimateNoise` 构造器注入；② `DisasterTrigger.evaluate` 纯函数；③ 状态机假时钟；④ `ClimateDataStore.overrideForTest` 测试钩子（生产路径默认禁用，gameTest 命名空间启用）；⑤ 影响系数纯函数。

**明确不可自动化**（手动验收清单）：雾/沙尘暴视觉、粒子效果、音效、S2C 包在真实客户端的行为——列入各里程碑人工验收项。

---

## 10. 实现里程碑拆分

按依赖排序，每个里程碑独立可派发、独立验收：

### M-B1 气候数据层 + API 契约（依赖：无；前提：子项目 A 的 BiomeLayoutNoise/HeightMapBuilder 已就绪 ✓）

- **目标**：5 维数据层全部纯函数实现 + 缓存 + 季节状态机 + `api` 契约（事件类/值类型/接口/门面）冻结。
- **涉及文件**：`climate/ClimateNoise.java`、`climate/data/ClimateDataStore.java`、`climate/data/ClimateDataCache.java`、`climate/data/SeasonState.java`、`api/ClimateData.java`、`api/Season.java`、`api/DisasterType.java`、`api/ClimateService.java`、`api/ClimateServices.java`、`api/GtsnTerrainAPI.java`、`api/event/*.java`（6 事件类）、`GtsnTerrain.java` 注册钩子（子项目 B 区块）。
- **验收标准**：§9.1 全部 JUnit 绿；`/gtsnclimate` 调试命令（F3 旁挂，可输出 5 维值）；api 包评审冻结；事件可 post（用临时监听器验证）。
- **不做**：任何影响结算、任何灾害物理、任何 KubeJS 文件。

### M-B2 实际影响系统（依赖：M-B1）

- **目标**：§3 影响表全部条目落地（作物/实体/方块/天气/Fog）。
- **涉及文件**：`effects/ClimateTickService.java`、`effects/ClimateBlockQueue.java`、`effects/CropGrowHandler.java`、`effects/PlayerTemperatureEffects.java`、`effects/PlayerUvEffects.java`、`effects/WindHandler.java`、`effects/WeatherIntegration.java`、`effects/FogHandler.java`、`effects/FireInteraction.java`。
- **验收标准**：§9.1 影响系数 JUnit 绿；§9.2 gameTest（结冰/融雪/作物修正）绿；人工验收：雾可见、降雨切换平滑、寒冷伤害生效。
- **不做**：灾害系统、KubeJS。

### M-B3 灾害框架 + 龙卷风（依赖：M-B1，可与 M-B2 并行——只读气候数据，不依赖 effects）

- **目标**：通用状态机 + `DisasterManager` + `BlockUpdateSnapshot` 队列 + 龙卷风全物理 + 粒子/音效。
- **涉及文件**：`disasters/DisasterManager.java`、`disasters/DisasterStateMachine.java`、`disasters/DisasterTrigger.java`、`disasters/BlockUpdateSnapshot.java`、`disasters/BlockUpdateQueue.java`、`disasters/TornadoDisaster.java`、`disasters/particle/TornadoParticle.java`、`network/GtsnNetwork.java`（龙卷风视觉 S2C 起步）。
- **验收标准**：§9.1 状态机/触发 JUnit 绿；§9.2 破坏/吸引 gameTest 绿；人工验收：漏斗粒子、风声、破坏流畅（无卡顿）。
- **不做**：沙尘暴、洪水、KubeJS 事件组。

### M-B4 沙尘暴 + 洪水（依赖：M-B1 + M-B3 框架，可与 M-B2 并行）

- **目标**：沙尘暴（Fog/粒子/窒息/减速）+ 洪水（水位计算/蔓延/退水）全物理。
- **涉及文件**：`disasters/DustStormDisaster.java`、`disasters/FloodDisaster.java`、`disasters/FloodWaterCalculator.java`、`disasters/particle/DustParticle.java`、`disasters/FloodRippleParticle.java`（低优先级）。
- **验收标准**：§9.1 水位计算 JUnit 绿；§9.2 洪水蔓延/退水 gameTest 绿；人工验收：沙暴雾效、洪水真实蔓延。
- **不做**：性能压测（M-B5）、KubeJS 事件组。

### M-B5 KubeJS 绑定 + 网络同步 + 性能调优（依赖：M-B1；与 M-B2/B3/B4 并行）

- **目标**：KubeJS 插件/绑定/事件组 + kubejs.plugins.txt + S2C 完整化 + 全系统性能验证。
- **涉及文件**：`kubejs/GtsnKubeJSPlugin.java`、`kubejs/GtsnTerrainEvents.java`、`kubejs/event/DisasterStartedEventJS.java`、`kubejs/event/DisasterEndedEventJS.java`、`kubejs/event/FloodStartEventJS.java`、`resources/kubejs.plugins.txt`、`network/` 补齐、build.gradle（`compileOnly kubejs` 依赖、mods.toml optional 声明——**需新增构建配置**）。
- **验收标准**：启动日志出现插件加载；§7.4 脚本样例可运行（控制台输出 5 维值、灾害事件触发）；S2C 包客户端接收（人工：另一客户端联机验证）；百万次查询基准 < 5ms/千次（压测脚本入 test 资源）；与 SereneSeasons 共存检测（§11）。
- **不做**：新功能。

> 依赖图：`M-B1 → M-B2`、`M-B1 → M-B3 → M-B4`、`M-B1 → M-B5`（M-B2/M-B3/M-B4/M-B5 相互并行）。README 路线 M4/M5 对应关系：M4 ≈ M-B1 + M-B2 + M-B5，M5 ≈ M-B3 + M-B4。

---

## 11. 风险与开放问题

### 11.1 已知风险

| 风险                                                                                                      | 等级 | 缓解                                                                                                                |
| --------------------------------------------------------------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------- |
| **性能**：实体多时逐实体 tick 结算、灾害方块替换量大                                                      | 高   | §2.5 区块缓存 + §4.5 预算上限；实体结算白名单（仅玩家/掉落物/箭）；M-B5 压测基准                                    |
| **与子项目 A 耦合**：`BiomeLayoutNoise`/`HeightMapBuilder` 签名变更破坏气候层                             | 中   | 气候层只经注入的公开方法读取；若 A 重构，只动 `ClimateNoise` 适配层，api 契约不受影响                               |
| **多世界维度**：下界/末地/自定义维度行为未定义                                                            | 中   | §2.5 退化值 + §7.4 脚本可 cancel；洪水脚本示例展示维度守卫                                                          |
| **与原版天气/SereneSeasons 冲突**：SS 用 ASM hook 改 biome 温度，可能与我们的温度场叠加（整合包双份季节） | 中   | `ModList.get().isLoaded("sereneseasons")` 检测：共存时我们的季节偏移关闭（保留数据层），post 文档说明；不做双写协调 |
| **存档膨胀**：SavedData 频繁写                                                                            | 低   | 仅状态机转移/季变时 setDirty（§2.4/§4.5）                                                                           |
| **玩家体验**：龙卷风破坏玩家建筑引发争议                                                                  | 中   | `gtsn:tornado_immune` tag + config 开关（§4.2），文档明示默认行为                                                   |

### 11.2 待用户确认的决策点（评审时逐条拍板）

| #   | 决策点                              | 规格默认值                                                   | 备选                        |
| --- | ----------------------------------- | ------------------------------------------------------------ | --------------------------- |
| D1  | 温度单位                            | 摄氏度 °C                                                    | 华氏 / 归一化 [-1,1] 双接口 |
| D2  | 季节长度                            | 8 天/季（config 可调）                                       | 4 天 / 16 天                |
| D3  | 龙卷风是否默认破坏玩家建筑          | 默认破坏（tag 可豁免 + config 总关）                         | 默认保护只破坏自然方块      |
| D4  | 玩家寒冷/中暑伤害是否受难度影响     | 简单=减半，困难=×1.5（随难度缩放）                           | 恒定                        |
| D5  | KubeJS 依赖方式                     | compileOnly + optional mods.toml（无 KubeJS 也能跑）         | 硬依赖                      |
| D6  | 洪水是否影响下界（通过脚本 cancel） | 下界默认无洪水（退化值天然不触发）                           | 允许维度配置                |
| D7  | 灾害视觉 S2C 范围                   | 第一版仅"每玩家气候 HUD 快照 + 灾害视觉触发"，区块追踪包后置 | 区块追踪全视觉              |
| D8  | 与 SereneSeasons 共存策略           | 检测到 SS 时关闭本模组季节偏移（数据层保留）                 | 完全忽略 SS                 |

### 11.3 明确不做（延续 §1.3，实施中禁止悄悄加入）

- 新增灾害类型、新气候维度、地形算法改动、Mixin 引入、HUD UI、湿度植被演化。

---

## 附录 A：术语表（精简）

| 术语                   | 含义                                                                                                         |
| ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| flag 3                 | `Level.setBlock(pos, state, 3)` 的更新标志 = BLOCK_UPDATE \| NOTIFY_NEIGHBORS，自动广播客户端（调研报告 §6） |
| BlockUpdateSnapshot    | Weather2 的方块变更队列条目：旧状态备份 + 新状态 + 位置，队列式应用（调研报告 §5）                           |
| S2C                    | Server-to-Client 数据包，SimpleChannel 每玩家/区块追踪发送（调研报告 §6）                                    |
| 大陆度 continentalness | `HeightMapBuilder.continentalness(x,z)` ∈ [-1,1]，负=海洋性湿润，正=内陆干燥                                 |
| 深模块                 | 一个窄接口背后藏着大量内部逻辑的模块（api-and-interface-design 深模块原则）                                  |
