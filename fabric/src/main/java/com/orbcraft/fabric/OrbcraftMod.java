package com.orbcraft.fabric;

import com.orbcraft.fabric.combat.CombatInit;
import com.orbcraft.fabric.command.OrbBuildCommand;
import com.orbcraft.fabric.item.OrbItems;
import com.orbcraft.fabric.net.OrbcraftNet;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;
import com.orbcraft.fabric.server.OrbDevCommand;
import com.orbcraft.fabric.server.ServerRules;
import com.orbcraft.fabric.spawn.VanillaSummonSpawner;
import com.orbcraft.fabric.store.OrbcraftStore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口（双端都会加载），也是五路唯一的接线点。
 *
 * <p><b>为什么初始化都在这里、而不是各包自己注册。</b> 五路是并行开发的，
 * 每一路都需要在初始化时挂自己的事件与注册项。如果各自改这个文件，合并时必然冲突；
 * 所以约定是「各路只提供静态 {@code init()}，由主线在这里按顺序调用」。
 * 改这个文件就等于改接线，需要同时看下面这条顺序说明。
 *
 * <p><b>顺序不是随意的</b>，依赖关系如下：
 * <ol>
 *   <li>{@code OrbcraftStore.init()} —— 注册 {@code PlayerStore}。后面三路都要用它：
 *       构筑层要 {@code markDirty}，球物品要 {@code codec()} 读写票据，服务端规则要
 *       {@code ownerKeyOf}。它排在第一位是因为「没有存档就没有其他一切」。</li>
 *   <li>{@code OrbItems.init()} —— 注册 {@code orbcraft:orb} 与投掷物实体类型，
 *       并装 {@code OrbItemContract}。构筑层提交后要立刻造球，所以必须早于命令。</li>
 *   <li>{@code VanillaSummonSpawner.init()} —— 装 {@code SummonSpawner}。
 *       球落地时会调它；它晚一点没关系（落地发生在投掷之后），但必须早于任何一次落地。</li>
 *   <li>{@code ServerRules.init()} —— 全项目唯一的 tick 推进点 + 投掷归属拦截。
 *       它必须在孵化服务可用之后挂：tick 一旦开始跑，就会有人读进度。</li>
 *   <li>{@code OrbBuildCommand.init()} —— 玩家入口，依赖上面四个全部就位。</li>
 * </ol>
 *
 * <p>顺序错了的症状不是崩溃，是「静默不生效」：比如命令先于商店注册，
 * 玩家打 {@code /orb build} 会看到「球物品还没接上」，而日志里什么都没有。
 */
public final class OrbcraftMod implements ModInitializer {

    /** 模组 id。与 fabric.mod.json 的 {@code id} 必须一致，改一处等于改两处。 */
    public static final String MOD_ID = "orbcraft";

    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        OrbcraftStore.init();
        OrbItems.init();
        OrbcraftNet.init();
        VanillaSummonSpawner.init();
        CombatInit.init();
        com.orbcraft.fabric.equip.EquipmentCombat.init();
        com.orbcraft.fabric.equip.EquipmentRules.init();
        ServerRules.init();
        OrbDevCommand.init();
        OrbBuildCommand.init();

        // 换世界（单人退出存档再进另一个存档）时清掉运行期状态。
        //
        // 必须注册在 OrbcraftStore.init() 之后：Fabric 按注册顺序回调，
        // 存档先刷盘、再清内存。反过来会让「最后一次落盘」拿到已经清空的账本，
        // 表现是「退出存档时最后几秒的进度丢了」。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> OrbcraftRuntime.resetForNewWorld());

        String side = FabricLoader.getInstance().getEnvironmentType().name();
        LOG.info("[orbcraft] 初始化完成 · 环境 {} · Java {}", side, Runtime.version().feature());
    }
}
