package com.gtsn.terrain;

import com.gtsn.terrain.world.WorldGenRegistration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(GtsnTerrain.MOD_ID)
public class GtsnTerrain {
    public static final String MOD_ID = "gtsnterrain";
    private static final Logger LOGGER = LogUtils.getLogger();

    public GtsnTerrain() {
        LOGGER.info("[GTSN-Terrain] Loading GTSN-Terrain Generation 0.1.0");
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 子项目 A：地形生成器注册（M1）
        WorldGenRegistration.register(modEventBus);

        // 子项目 B：气候系统注册（后续里程碑）
    }
}
