# 05 · KubeJS 绑定 + 气候/灾害模组参考

> 来源：KubeJS 2001 分支源码（SHA ba142541）+ SereneSeasons/TAN/Weather2 源码
> 用途：气候 API（事件总线+公开 API 类+KubeJS 绑定）与灾害实现的直接依据

## 1. KubeJS 1.20.1 自定义绑定

**要点**：绑定 API 是 `BindingsEvent`（`dev.latvian.mods.kubejs.script.BindingsEvent`），唯一方法 `add(String name, Object value)`。**1.20.1 没有 `BindingRegistry`**（那是 1.21+ 的名字）。注册入口 `KubeJSPlugin.registerBindings(BindingsEvent event)`，KubeJSPlugin 在**根包** `dev.latvian.mods.kubejs`（不是 `plugin` 子包）。绑定在 STARTUP/SERVER/CLIENT 三个 ScriptManager 初始化时各调一次，用 `event.getType().isServer()` 过滤。

**BindingsEvent 源码**：

```java
package dev.latvian.mods.kubejs.script;
public class BindingsEvent {
    public final ScriptManager manager;
    public final Scriptable scope;
    public ScriptType getType() { return manager.scriptType; }
    public void add(String name, Object value) {
        if (value != null) { manager.context.addToScope(scope, name, value); }
    }
}
```

出处：[BindingsEvent.java (2001)](https://github.com/KubeJS-Mods/KubeJS/blob/ba142541dcc1d230383f4a55e38dd92ff10d1029/common/src/main/java/dev/latvian/mods/kubejs/script/BindingsEvent.java)

**真实插件示例（MnaJS，1.20.1）**：

```java
public class MnaJSPlugin extends KubeJSPlugin {
    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("Affinity", Affinity.class);
        event.add("PlayerMagic", PlayerUtil.class);
        event.add("WorldMagic", WorldMagic.class);
        if (event.getType().isServer()) { event.add("EntityUtil", EntityUtil.class); }
        if (event.getType().isClient()) { event.add("WorldRenderUtils", WorldRenderUtils.class); }
    }
}
```

出处：[MnaJSPlugin.java](https://github.com/PickAID/MnaJS/blob/cab894f8febdb120cd79a1d430e7d8555fda875c/src/main/java/com/pickaid/mnajs/kubejs/MnaJSPlugin.java)

**插件发现机制（1.20.1 确认）**：

- **没有 `@KubeJSPlugin` 注解**（那是 1.21+ 的 `dev.latvian.mods.kubejs.plugin.KubeJSPlugin` 接口 + BindingRegistry）
- 1.20.1 用 **`kubejs.plugins.txt` 资源文件**声明插件类全限定名，`KubeJSPlugins.load()` 启动时扫描每个 mod jar 的 `findResource("kubejs.plugins.txt")`
- 文件格式：每行一个类名，可带 `client` 标记（仅客户端）和依赖 mod 过滤（如 `com.x.Plugin client kubejs`）；另有可选 `kubejs.classfilter.txt`（`+` 开头 = allow 包）

出处：[KubeJSPlugins.java L37-L90 (2001)](https://github.com/KubeJS-Mods/KubeJS/blob/ba142541dcc1d230383f4a55e38dd92ff10d1029/common/src/main/java/dev/latvian/mods/kubejs/util/KubeJSPlugins.java)

**脚本调用 `GtsnTerrain.Climate.getTemperature` 方案**：

- 绑定**类对象** `event.add("GtsnTerrain", GtsnTerrainAPI.class)`，`Climate` 为 `public static` 嵌套类、`getTemperature` 为 `public static` 方法
- 或绑定实例 `event.add("GtsnTerrain", GtsnTerrainAPI.INSTANCE)`
- 脚本：`let t = GtsnTerrain.Climate.getTemperature(level, player.blockPosition());`
- ⚠️ **关键**：自定义 API 类须经 `registerClasses(ScriptType, ClassFilter)` 的 `filter.allow("com.gtsn.terrain.api")` 放行，否则 Rhino 报 `Cannot access class`
- 其他钩子：`init()`/`clientInit()`/`afterInit()`/`onServerReload()`/`attachServerData`/`attachLevelData`/`attachPlayerData`（气候缓存挂 `attachLevelData`）

## 2. KubeJS 自定义事件（EventGroup）

**三件套**：`EventGroup`（事件组，组名即脚本全局对象名）+ `EventHandler`（单个事件，`hasResult()` 支持 cancel）+ `EventJS`（事件实例）。注册时机：插件 `registerEvents()` 里 `group.register()`（**必须调用**）；发布用 `handler.post(...)`。

**EventGroup API**：

```java
public static EventGroup of(String name) { return new EventGroup(name); }
public void register() { MAP.put(name, this); }
public EventHandler add(String name, ScriptTypePredicate scriptType, Supplier<Class<? extends EventJS>> eventType) {...}
public EventHandler startup(...) / server(...) / client(...) / common(...)
```

出处：[EventGroup.java (2001)](https://github.com/KubeJS-Mods/KubeJS/blob/ba142541dcc1d230383f4a55e38dd92ff10d1029/common/src/main/java/dev/latvian/mods/kubejs/event/EventGroup.java)、[EventHandler.post()](https://github.com/KubeJS-Mods/KubeJS/blob/ba142541dcc1d230383f4a55e38dd92ff10d1029/common/src/main/java/dev/latvian/mods/kubejs/event/EventHandler.java)

**组定义 + 发布 + 脚本监听**：

```java
public interface GtsnEvents {
    EventGroup GROUP = EventGroup.of("GtsnTerrainEvents");   // 脚本全局名
    EventHandler TORNADO_SPAWN   = GROUP.server("tornadoSpawn", () -> TornadoSpawnEventJS.class);
    EventHandler DUST_STORM_TICK = GROUP.server("dustStormTick", () -> DustStormTickEventJS.class);
    EventHandler FLOOD_START     = GROUP.server("floodStart", () -> FloodStartEventJS.class).hasResult(); // 可 cancel
}
// 插件里： @Override public void registerEvents() { GtsnEvents.GROUP.register(); }
// 发布：  GtsnEvents.TORNADO_SPAWN.post(new TornadoSpawnEventJS(pos, strength));
// 可取消：EventResult r = GtsnEvents.FLOOD_START.post(ScriptType.SERVER, new FloodStartEventJS(...));
```

```js
// server_scripts/灾害挂钩.js
GtsnTerrainEvents.tornadoSpawn((event) => {
  console.log(`龙卷风生成于 ${event.pos}`);
});
GtsnTerrainEvents.floodStart((event) => {
  if (someCondition) event.cancel();
});
```

真实范例：[MnaJSEvents.java](https://github.com/PickAID/MnaJS/blob/cab894f8febdb120cd79a1d430e7d8555fda875c/src/main/java/com/pickaid/mnajs/kubejs/MnaJSEvents.java)

## 3. SereneSeasons 1.20.1（温度系统参考）

**架构**：`Season` 枚举（四季+亚季节+热带旱雨季，`sereneseasons.api.season.Season`）→ `SeasonHandler`（世界 tick 推进季节周期，存 SeasonSavedData）→ `SeasonHooks`（ASM hook 改写 Biome 温度判定）→ `SeasonsConfig`（数据驱动 SeasonProperties）。**SS 不重算温度场，而是在原版 biome 温度上加季节偏移再 clamp**。

**getBiomeTemperatureInSeason 完整逻辑**：

```java
public static float getBiomeTemperatureInSeason(Season.SubSeason subSeason, Holder<Biome> biome, BlockPos pos) {
    boolean tropicalBiome = biome.is(ModTags.Biomes.TROPICAL_BIOMES);
    float biomeTemp = biome.value().getTemperature(pos);
    if (!tropicalBiome && biome.value().getBaseTemperature() <= 0.8F && !biome.is(ModTags.Biomes.BLACKLISTED_BIOMES)) {
        biomeTemp = Mth.clamp(biomeTemp + ModConfig.seasons.getSeasonProperties(subSeason).biomeTempAdjustment(), -0.5F, 2.0F);
    }
    return biomeTemp;
}
```

出处：[SeasonHooks.java L100-L115](https://github.com/Glitchfiend/SereneSeasons/blob/61df18c2029d50e1294e7fc8d61824bbd6facb75/common/src/main/java/sereneseasons/season/SeasonHooks.java)、[SeasonHelper.java](https://github.com/Glitchfiend/SereneSeasons/blob/61df18c2029d50e1294e7fc8d61824bbd6facb75/common/src/main/java/sereneseasons/api/season/SeasonHelper.java)（`SeasonHelper.getSeasonState(level)` 返回 `ISeasonState`）

**注意**：1.20.1 用 `SeasonProperties` 配置 record，**不是** `ClimateSettings`（那是 1.21 概念）。

## 4. ToughAsNails（温度档位参考）

**要点**：TAN **无官方 1.20.1 分支**（最早 TAN-1.20.2，结构近似）。**1.20.x 已移除湿度系统**（湿度是 1.12 老版 HumidityHandler）。现代 TAN 温度 = 玩家附身温度：biome 基础温度分档 + 时间/海拔/方块/装备修正器。

```java
private static TemperatureLevel getBiomeTemperatureLevel(Level level, BlockPos pos) {
    float biomeTemperature = biome.value().getBaseTemperature();
    if (biomeTemperature < 0.15F) return TemperatureLevel.ICY;
    else if (biomeTemperature >= 0.15F && biomeTemperature < 0.45F) return TemperatureLevel.COLD;
    else if (biomeTemperature >= 0.45F && biomeTemperature < 0.75F) return TemperatureLevel.NEUTRAL;
    else if (biomeTemperature >= 0.75F && biomeTemperature < 0.9F)  return TemperatureLevel.WARM;
    else return TemperatureLevel.HOT;
}
```

出处：[TemperatureHelperImpl.java](https://github.com/Glitchfiend/ToughAsNails/blob/b796f17a9c33aca383971d48ad5acaca70b449ec/src/main/java/toughasnails/temperature/TemperatureHelperImpl.java)

## 5. Weather2（龙卷风/沙尘暴物理参考）— 分支 1.20，SHA c023f460

**架构**：`weather2/weathersystem/` 下 StormObject.java（105KB 主模拟）、TornadoHelper.java（30KB 龙卷风物理）、WeatherObject.java 基类、WeatherObjectParticleStorm/WeatherObjectSandstormOld（沙尘暴）、tornado/ 下 TornadoManagerTodoRenameMe.java（漏斗路径 CatmullRomSpline/CubicBezierCurve）。

**方块破坏：判定移除 + 入队 BlockUpdateSnapshot + setBlock 应用**：

```java
if (WeatherUtil.shouldRemoveBlock(state)) {
    removeCount++;
    boolean shouldEntityify = blockCount <= ConfigTornado.Storm_Tornado_maxFlyingEntityBlocks;
    listBlockUpdateQueue.put(pos, new BlockUpdateSnapshot(parWorld.dimension(),
        Blocks.AIR.defaultBlockState(), state, pos, playerClose && shouldEntityify)); // 旧状态备份进队列
    if (playerClose && shouldEntityify && (state.canOcclude() || state.getBlock().defaultMapColor() == MapColor.PLANT)) {
        ((WeatherManagerServer) this.storm.manager).syncBlockParticleNew(pos, state, storm); // 客户端粒子
    }
}
// 队列消费处：world.setBlock(snapshot.getPos(), snapshot.getState(), 3);   // flag=3 → 通知客户端
```

出处：[TornadoHelper.java L89-L97（队列）](https://github.com/Corosauce/weather2/blob/c023f4606218a3e49f66ba91fa4cd982f9aebff6/src/main/java/weather2/weathersystem/storm/TornadoHelper.java)、[L184-L192（应用）](https://github.com/Corosauce/weather2/blob/c023f4606218a3e49f66ba91fa4cd982f9aebff6/src/main/java/weather2/weathersystem/storm/TornadoHelper.java)

**实体吸引：AABB 扫描 + spinEntityv2 改速度**：

```java
AABB aabb = new AABB(storm.pos.x, storm.currentTopYBlock, storm.pos.z, storm.pos.x, storm.currentTopYBlock, storm.pos.z)
        .inflate(dist, this.storm.maxHeight * 3.8, dist);
List list = parWorld.getEntitiesOfClass(Entity.class, aabb);
// 对每个 canGrabEntity(entity1) 的实体：storm.spinEntityv2(entity1);
// StormObject.spinEntityv2：基于实体相对中心的切向速度 + 上抛分量，setDeltaMovement(vec) + setYRot(...)
```

出处：[TornadoHelper.java L655-L720](https://github.com/Corosauce/weather2/blob/c023f4606218a3e49f66ba91fa4cd982f9aebff6/src/main/java/weather2/weathersystem/storm/TornadoHelper.java)、[StormObject.java spinEntityv2](https://github.com/Corosauce/weather2/blob/c023f4606218a3e49f66ba91fa4cd982f9aebff6/src/main/java/weather2/weathersystem/storm/StormObject.java)

**沙尘暴粒子**：WeatherObjectParticleStorm/WeatherObjectSandstormOld + extendedrenderer 自定义粒子渲染框架（ParticleFog/ParticleRotate，不走原版 ParticleEngine）+ ClientTickHandler 派发。轻量替代：SimpleParticleType + DeferredRegister<ParticleType<?>> + level.addParticle。

## 6. Forge 1.20.1 S2C 数据包（SimpleChannel）

**要点**：`NetworkRegistry.newSimpleChannel` + `registerMessage`；S2C 包 handler 里用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 包一层，最后 `setPacketHandled(true)`。**块状态修改客户端同步不用手写包**——`level.setBlock(pos, state, 3)`（flag 3 = BLOCK_UPDATE|NOTIFY_NEIGHBORS）自动广播；手写包只用于每玩家单独数据（气候 HUD 等）。

```java
public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
    new ResourceLocation("gtsn", "main"),
    () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
public static void register() {
    INSTANCE.registerMessage(id++, SyncClimatePacket.class,
        SyncClimatePacket::encode, SyncClimatePacket::decode, SyncClimatePacket::handle);
    INSTANCE.registerMessage(id++, TornadoS2CPacket.class,
        TornadoS2CPacket::encode, TornadoS2CPacket::decode, TornadoS2CPacket::handle);
}
```

```java
public static void handle(SyncClimatePacket msg, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() ->
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            GtsnClientClimateCache.INSTANCE.update(msg.pos, msg.temperature, msg.humidity, msg.uvIndex)));
    ctx.get().setPacketHandled(true);
}
```

发送端：单玩家 `INSTANCE.send(PacketDistributor.PLAYER.with(player), packet)`；区块追踪（灾害视觉首选）`PacketDistributor.TRACKING_CHUNK.with(levelChunk)`；全体 `PacketDistributor.ALL.noArg()`。

出处：Forge 官方文档 [docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/](https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/)；真实参考 [SereneSeasons SyncSeasonCyclePacket.java](https://github.com/Glitchfiend/SereneSeasons/blob/61df18c2029d50e1294e7fc8d61824bbd6facb75/common/src/main/java/sereneseasons/network/SyncSeasonCyclePacket.java)（writeUtf 维度 + writeInt tick）、[Weather2 WeatherNetworking.java](https://github.com/Corosauce/weather2/blob/c023f4606218a3e49f66ba91fa4cd982f9aebff6/src/main/java/weather2/WeatherNetworking.java)（ChannelBuilder + registerMessage + PLAY_TO_CLIENT，全 NBT 载荷）

## 7. 洪水参考

| 参考                          | 仓库                                                      | 特点                                                    |
| ----------------------------- | --------------------------------------------------------- | ------------------------------------------------------- |
| flooded                       | github.com/maruohon/flooded                               | 下雨时缓慢淹全世界，纯服务端 setBlock 改水位，GPL-3.0   |
| Water-Physics-Overhaul-1.20.1 | github.com/dev-willbird1936/Water-Physics-Overhaul-1.20.1 | 自定义流体存储 + 最大水位 8 + 块状态注入 + packet hooks |
| puddles-floods                | github.com/PigCart/puddles-floods                         | 河流涨水视觉（MIT）                                     |

**实现要点**：服务器权威按区块算目标水位 Y，`level.setBlock(pos, waterState, 3)` 逐列填充（flag 3 自动同步客户端）；水位下降时把源块换回空气并触发 `neighborChanged` 让水自然流散。

## 8. 对我们的直接落点

1. **KubeJS 绑定**：`GtsnKubeJSPlugin extends KubeJSPlugin`（根包），`registerBindings` + `registerEvents`，资源文件 `kubejs.plugins.txt`
2. **气候缓存**：挂 `attachLevelData`（每世界一份），脚本经 `level.attached.gtsn_climate` 读取；`onServerReload` 清缓存
3. **灾害同步**：方块破坏/洪水直接用 `setBlock(pos, state, 3)`；每玩家数据（气候 HUD）走 SimpleChannel S2C
4. **龙卷风物理**：BlockUpdateSnapshot 队列 + AABB 吸引 + spinEntityv2 模式
5. **气候公式**：温度 = 群系基准 + 季节偏移（SS 式 clamp）+ 海拔梯度 + 时间；湿度 = 群系基准 + 距海衰减；紫外线 = 光照 × 海拔 × 天气遮挡
6. **已知局限**：TAN 无 1.20.1 官方分支；Weather2 代码社区维护、接口旧（大量 raw type），建议只借鉴算法不照抄结构
