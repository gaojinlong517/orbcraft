package com.orbcraft.fabric.server;

import java.util.ArrayList;
import java.util.List;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.Services;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * S5 的接线入口 —— 由 {@code OrbcraftMod.onInitialize()} 调一行 {@link #init()}。
 *
 * <p><b>这个类在链路里的位置</b>：它不实现任何契约，只把四个「时刻」接进本包的逻辑：
 * <table border="1">
 *   <caption>四个接线点</caption>
 *   <tr><th>时刻</th><th>挂点</th><th>做什么</th></tr>
 *   <tr><td>每个服务端 tick 结束</td><td>{@code ServerTickEvents.END_SERVER_TICK}</td>
 *       <td>推进孵化（全项目唯一一处 {@code IncubationService.tick} 调用）</td></tr>
 *   <tr><td>右键使用物品</td><td>{@code ItemEvents.USE} / {@code ItemEvents.USE_ON}</td>
 *       <td>投掷前的归属/状态拦截（对空与对方块是两条原版路径，都要拦）</td></tr>
 *   <tr><td>玩家断线</td><td>{@code ServerPlayConnectionEvents.DISCONNECT}</td>
 *       <td>只清本地缓存，票据保持在途</td></tr>
 *   <tr><td>命令树组建</td><td>{@code CommandRegistrationCallback}</td>
 *       <td>{@code /orbadmin}</td></tr>
 * </table>
 *
 * <p><b>为什么用 {@code END_SERVER_TICK} 而不用 {@code END_WORLD_TICK}</b>：
 * 世界 tick 每个维度各触发一次（主世界、下界、末地就是三次）。孵化票据是全球性的，
 * 没有「这个球在主世界」的概念，挂在世界 tick 上等于把推进次数乘以维度数，
 * 症状就是「同一个球收到多条完成提示」——而 3 个维度时很难看出是 3 倍关系。
 *
 * <p><b>重复初始化会被挡住</b>：{@link #init()} 重复调用会注册第二份监听器，
 * 于是 tick 被推进两次、拒绝提示发两遍。这属于「看起来完全正常」的错，
 * 所以这里用一次性开关把它变成一条明确的日志。
 */
public final class ServerRules {

    private static boolean initialized;

    private ServerRules() {
    }

    /**
     * 注册全部服务端监听器。只允许调用一次。
     *
     * <p>接线位置：{@code OrbcraftMod.onInitialize()} 的第一行。
     * 放在最前面是因为它只注册监听器、不依赖任何实现已就位
     * （契约实现由各路自己的 init 注册，事件在运行期才触发）。
     */
    public static void init() {
        if (initialized) {
            OrbcraftMod.LOG.warn("[orbcraft] S5 规则层被重复初始化，本次忽略 —— "
                    + "重复注册会让 tick 推进两次、提示发两遍");
            return;
        }
        initialized = true;

        ServerTickEvents.END_SERVER_TICK.register(HatchRuntime::advance);
        ItemEvents.USE.register(ThrowGuard::onUse);
        // 对空右键与对方块右键在原版是两条路径（use / useOn），只拦一条会留下绕过口。
        ItemEvents.USE_ON.register(ThrowGuard::onUseOn);
        ServerPlayConnectionEvents.DISCONNECT.register(ThrowGuard::onDisconnect);
        ServerLifecycleEvents.SERVER_STARTED.register(ServerRules::selfCheck);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) ->
                AdminCommands.register(dispatcher));

        OrbcraftMod.LOG.info("[orbcraft] S5 联机规则装载：tick 推进点唯一 · 投掷归属拦截 · /orbadmin（权限等级 2）");
    }

    /**
     * 开服自检 —— <b>把「静默不生效」变成一行日志。</b>
     *
     * <p>为什么需要它：本包的降级策略是「契约没装就放行」（{@link ServiceLookup}），
     * 这在并行开发期是必要的，但它有个致命副作用 —— 若 S1 没接线，
     * 「A 的球 B 扔不了」会<b>静默失效</b>：球照样扔得出去，日志里只有一行 warn，
     * 验收的人会以为拦截逻辑写错了。这里在开服时一口气把结论说清楚：
     * 谁没装、后果是什么、不是谁的问题。
     *
     * <p>用 {@code Services.ready()} 的同一套判据做自检不是越界 ——
     * 契约里写明它「只给启动自检用，业务代码不要拿它做分支」。
     *
     * <p>本方法检不出「{@code ServerRules.init()} 压根没被调用」：那种情况下
     * 本类一行都不会执行，只能靠「日志里没有 S5 装载那一行」反推。
     */
    private static void selfCheck(net.minecraft.server.MinecraftServer server) {
        List<String> missing = new ArrayList<>();
        if (!ServiceLookup.installed(Services::orbItem)) {
            missing.add("OrbItemContract（S1：item/OrbItems.init()）");
        }
        if (!ServiceLookup.installed(Services::summonSpawner)) {
            missing.add("SummonSpawner（S3：spawn/VanillaSummonSpawner.init()）");
        }
        if (!ServiceLookup.installed(Services::playerStore)) {
            missing.add("PlayerStore（S4：store/OrbcraftStore.init()）");
        }

        if (missing.isEmpty()) {
            OrbcraftMod.LOG.info("[orbcraft] S5 自检通过：三个契约均已就位，投掷归属拦截与孵化推进正常生效");
            return;
        }
        OrbcraftMod.LOG.error("[orbcraft] S5 自检未通过：{} 未就位。"
                        + "在此之前「A 的球 B 扔不了」会直接放行（不是拦截逻辑写错，是 init() 没接线），"
                        + "孵化的恢复与该玩家的落盘也会缺一半。"
                        + "请检查 OrbcraftMod.onInitialize() 是否按顺序调用了各路 init()。",
                String.join("、", missing));
    }
}
