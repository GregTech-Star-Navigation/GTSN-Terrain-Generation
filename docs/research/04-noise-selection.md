# 04 · 噪声算法选型

> 来源：FastNoiseLite 官方仓库（SHA 785f37a9 v1.1.1）+ 原版反编译源码 + 基准表
> 结论：**选定 FastNoiseLite Java 单文件**（直接复制进模组源码）

## 0. 一句话结论

用 **FastNoiseLite Java 单文件**，直接复制 `Java/FastNoiseLite.java` 进模组源码。不要用原版 `net.minecraft...synth` 类（违反零 MC 依赖约束），不必自写 OpenSimplex2（FastNoiseLite 里已是 KdotJPG 官方移植）。**"快一个数量级"主要来自 2D 高度图架构本身**（评估次数少 5-10 倍 + 无逐方块 3D 密度/含水层逻辑），而非噪声库本身。

## 1. FastNoiseLite Java 版

- **功能**：
  - NoiseType：OpenSimplex2 / OpenSimplex2S / Cellular / Perlin / ValueCubic / Value 六种
  - FractalType：None / FBm / Ridged / PingPong / DomainWarpProgressive / DomainWarpIndependent
  - DomainWarpType：OpenSimplex2 / OpenSimplex2Reduced / BasicGrid
  - 3D 旋转类型 ImproveXYPlanes / ImproveXZPlanes（消 3D 切片方向伪影）
- **单文件零依赖**：整个文件无任何 import，自带 Vector2/Vector3，纯 java.lang
- **MIT 许可**（文件头 L1-L22）
- 出处：[Java/FastNoiseLite.java](https://github.com/Auburn/FastNoiseLite/blob/785f37a9ad76e283586a379675085f2063ae03f7/Java/FastNoiseLite.java)

**基准数据**（README，C++ 标量版，M samples/s）：

| 维度 | Value  | Perlin | OpenSimplex2 | Cellular |
| ---- | ------ | ------ | ------------ | -------- |
| 2D   | 114.01 | 92.83  | 71.30        | 39.15    |
| 3D   | 64.13  | 47.93  | 36.83        | 12.49    |

**诚实修正**：

- FastNoiseLite 自己不做"比 Perlin 快 10 倍"承诺——表中只比 libnoise/stb perlin 快约 1.4-1.75×
- "10 倍"是 SIMD 版 FastNoise2 的说法（实际 3D Perlin 261 vs 47.93 ≈ 5.4×）
- **其 C++ 基准里 OpenSimplex2 反而比 Perlin 慢**（2D 71.30 vs 92.83）
- Java 版无官方基准，JIT 后通常与 C++ 标量同量级
- → 选型应按**伪影质量**而非单次采样速度

## 2. OpenSimplex2 vs 2S vs Perlin

| 类型            | 顶点        | 伪影                           | 适用                                             |
| --------------- | ----------- | ------------------------------ | ------------------------------------------------ |
| Perlin          | 4 角点      | 45°/90° 网格对齐明显           | 不推荐做地形主层                                 |
| OpenSimplex2(F) | 3 三角格    | "最明显的对角伪影"（作者原话） | fBm 高度图/大陆度/域扭曲（默认首选）             |
| OpenSimplex2S   | 4/8 顶点    | 最平滑                         | **山脊层、细节层**（作者明说 2S 是 ridged 推荐） |
| 原版 MC synth   | 经典 Perlin | 网格伪影最重                   | 避免                                             |

出处：[KdotJPG/OpenSimplex2 README](https://github.com/KdotJPG/OpenSimplex2)、[FastNoise2 Wiki: Understanding Noise Types](https://github.com/Auburn/FastNoise2/wiki/Understanding-Noise-Types)、Java 性能优化参考 [jaskarth/OptimizedOpenSimplexNoise](https://github.com/jaskarth/OptimizedOpenSimplexNoise)（5-15%）

## 3. 2D 高度图 vs 3D 密度场性能（有具体数字）

**原版 1.20.1 主世界**：

- min_y -64、height 384、size_horizontal 1、size_vertical 2
- cellWidth = 4 格、cellHeight = 8
- cellCountXZ=4、cellCountY=48 → 插值网格 5×49×5 = **1225 个角点/区块**
- 逐方块：finalDensity 对每个方块求值（16×384×16 = **98,304 次**），内层 lerp3 插值 + aquifer.computeSubstance
- 每角点成本：finalDensity 全链 ≈ 地形 spline + 4 八度 3D Perlin + 洞穴链（20 个洞穴噪声）+ 含水层 4 + 矿脉 4，router 共约 30 个 NormalNoise 实例

**推算量级**：原版每区块 ≈ 1225 角点 × 10-20 次八度采样 ≈ **2-3 万次 3D 八度评估 + 98,304 次逐方块插值/含水层**

**我们的 2D 方案**：256 列 × 10-20 次 2D 采样 ≈ **2.5k-5k 次/区块**

- 评估次数少 4-8×
- 2D 单次比 3D 便宜 ~1.8×（114 vs 64 M/s）
- 砍掉含水层/矿脉/面条洞
- → **墙钟时间一个数量级可达，纯噪声评估量约原版 1/5-1/10**

出处：Blackjack200/minecraft_client_1_20_1（SHA c129e3e）+ wiki（Noise router / Density function / Noise settings）

## 4. 域扭曲（Domain Warping）

- 做法：采样前扭曲坐标再采样；**用独立第二个实例**（官方文档明确建议，warp 的 seed/frequency 与主噪声不同）
- 示例：`warp.DomainWarp(coord)` 原地改坐标，再 `main.GetNoise(coord)`；振幅 20-40 格
- 实现：DomainWarpSingle / DomainWarpFractalProgressive（逐八度叠加）/ DomainWarpFractalIndependent
- **性能代价**：每次 warp 采样 dx、dy 两个位移分量 + 最终 1 次采样 ≈ **每层约 3× 成本**
- 建议：大陆/山脊层 1 层 warp，细节层不 warp

## 5. 山脊噪声（Ridged）

- 经典做法：abs + 取反。`GenFractalRidged`：每八度 `sum += (1 - 2|n|) × amp`，加权强度抑制过冲
- 标准流程：FractalType.Ridged 低频 → 脊线；与大陆度相乘/相加控山位置高度；可选 abs 后 pow 锐化
- **山脊基底建议 OpenSimplex2S**（KdotJPG 明说 2S 是 ridged 推荐）

## 6. 3D 洞穴噪声 / cheese cave

**1.20.1 原版真实公式**（NoiseRouterData.underground() L181-L193）：

```
noise(CAVE_LAYER, 8).square()×4 + (0.27 + noise(CAVE_CHEESE, 0.6667)).clamp(-1,1) + (1.5 - 0.64×slopedCheese).clamp(0,0.5)
→ min(该值, ENTRANCES) + 意面粗糙度
```

**`y³ - (x²+z²)·a` 形状**对应原版 spaghetti_2d 厚度项：`|噪声高程 + y 线性梯度|³`（.cube()），用 y 三次方做尖锐垂直收窄。

**避免洞穴穿出海面，原版三机制**（都可抄）：

1. 范围门控 `rangeChoice(slopedCheese, -inf, 1.5625, 洞, 地形)`——只有地形密度低于阈值才激活洞穴
2. 洞口掩码 `min(洞穴密度, ENTRANCES)`（y_clamped_gradient(-10→30) 只浅层）
3. `yLimitedInterpolatable` 限制 y 范围 + 含水层灌水

**2D 方案建议**：独立 3D OpenSimplex2 阈值（caveNoise3D(x×2, y, z×2) > threshold）+ 靠近海平面衰减系数 + 每列按 2D 高度只挖 `y < surfaceHeight - k`

## 7. 确定性 / 线程安全

- **FastNoiseLite**：seed 在 SetSeed 后只读；GetNoise/GenFractalRidged 全用局部变量，不修改实例状态 → **并发只读采样线程安全、顺序无关、结果确定**
- 原版：SimplexNoise 构造时用 RandomSource 填置换表后只读；PerlinNoise 每八度 forkPositional().fromHashOf 派生。**RandomSource 本身非线程安全，随机数生成必须在构造期单线程完成**
- 实践守则：世界种子 → 构造所有噪声实例（不同种子偏移）→ 缓存 → 采样；不要在生成循环里调 Random
- 精度：Java float 同 JVM 内确定（OpenJDK 默认不做 FMA 收缩）；跨 JVM 极端情况可能微差，要绝对一致用 double

## 8. 依赖注入方式

- **推荐**：直接复制 `Java/FastNoiseLite.java` 进 `src/main/java/`，包名随意。官方未把 Java 版发布到 Maven Central → 直接 vendor 最干净
- 备选（要 Maven）：[tommyettinger/make-some-noise](https://github.com/tommyettinger/make-some-noise)（Maven Central 有坐标，含 FOAM/HONEY，API 非 FastNoise 风格）
- 排除原版 synth 类：构造依赖 Mth + RandomSource，单测必须带 MC jar，违反零依赖约束

## 9. 落地配置（v1 推荐参数）

| 层      | 噪声类型      | 分形         | 频率         | 振幅     | 域扭曲                       |
| ------- | ------------- | ------------ | ------------ | -------- | ---------------------------- |
| 大陆度  | OpenSimplex2  | FBm 4-5 八度 | 低频 ~0.0005 | 1.0      | 1 层（独立实例，振幅 20-40） |
| 山脊    | OpenSimplex2S | Ridged       | 中频         | 山体增益 | 1 层                         |
| 细节    | OpenSimplex2S | FBm 2-3 八度 | 高频         | 小       | 无                           |
| 河网    | OpenSimplex2  | FBm          | 低频         | 阈值切割 | 可加                         |
| 洞穴 3D | OpenSimplex2  | None         | ~0.01        | 阈值     | 无                           |
