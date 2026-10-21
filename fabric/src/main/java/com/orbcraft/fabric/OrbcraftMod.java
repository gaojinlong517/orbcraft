package com.orbcraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口（双端都会加载）。
 *
 * <p>S0 阶段这里<b>故意什么都注册</b>：骨架的验收标准是「Loom 能编、runServer 能起、
 * 日志里有这一行」，不是「有功能」。任何玩法代码都属于 S1–S5，写在这里会让
 * 五路并行时第一件事就变成抢同一个文件。
 *
 * <p>打印的信息里带上 core 的版本与运行环境，是为了让 S1 一上来就能确认
 * 「我的代码确实和 core 在同一个 classloader 里」，而不用先写一个临时命令去试。
 */
public final class OrbcraftMod implements ModInitializer {

    /** 模组 id。与 fabric.mod.json 的 {@code id} 必须一致，改一处等于改两处。 */
    public static final String MOD_ID = "orbcraft";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        String side = FabricLoader.getInstance().getEnvironmentType().name();
        LOG.info("[orbcraft] 初始化完成 · 环境 {} · Java {}",
                side, Runtime.version().feature());
    }
}
