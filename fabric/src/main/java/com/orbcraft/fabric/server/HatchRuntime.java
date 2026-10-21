package com.orbcraft.fabric.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orbcraft.core.hatch.HatchSnapshot;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.PlayerStore;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 孵化时间的唯一推进器。
 *
 * <p><b>本类是 {@code IncubationService.tick(nowTick)} 在全项目里唯一的调用点。</b>
 * 任何人再写一处「顺手 tick 一下」，同一张票就会在两次调用里各被判一次
 * 「本次新就绪」，玩家聊天栏收到两条一样的完成提示；更隐蔽的症状是
 * 进度看起来比别的票快 —— 因为 tick 只做状态迁移，重复调用不改进度，
 * 只重复播报，于是「有 bug 但说不清哪里错」。
 *
 * <p><b>tick 频率 = 每 tick 一次（20 次/秒）</b>，理由不是「实时」，而是两条正确性要求：
 * <ul>
 *   <li>进度条满的瞬间到状态变成 READY 之间不能有可感知缝隙。
 *       {@code IncubationService.throwBall} 会给「条已满但状态还是 CHARGING」就地补迁移，
 *       所以即便频率低也不会出现「满了投不出去」；但 S1 的 {@code checkThrow} 在
 *       投掷回调里先跑，它看到的是<b>已经落库的状态</b>。tick 若 1 秒一次，
 *       玩家会在这 1 秒里看到「条满了」却被拒绝 —— 一次眨眼里的失败最容易被当成 bug。</li>
 *   <li>开销是「每 tick 遍历一次票据表」，票据上限是每玩家 5 张（{@code maxConcurrent}），
 *       服务端 tick 本来就要遍历所有实体，这点量测不出来。反过来，为了省这点开销
 *       去做「按秒节流」，就是拿一个玩家可感知的怪行为换一个测不出来的性能收益。</li>
 * </ul>
 *
 * <p><b>为什么挂在 END_SERVER_TICK 而不是 END_WORLD_TICK。</b> 世界 tick 每个维度各发一次
 * （主世界 + 下界 + 末地 = 三次），tick 就会被推进三遍 —— 正是上面说的重复播报，
 * 而且数量随维度增加而增加。END_SERVER_TICK 每个服务端 tick 恰好一次。
 *
 * <p>孵化服务实例由本类持有并对外暴露，是因为四份契约里没有它
 * （它属于 core，不属于平台层契约面）。S2 的构筑、S4 的存档读写、S5 的推进
 * <b>必须是同一个实例</b>：两份 {@code IncubationService} 就是两套配额账本，
 * 症状是「造完了但配额没扣」和「球在背包里但服务端查不到票据」。
 */
public final class HatchRuntime {

    /** 全局唯一实例。S2 / S4 用 {@link #incubation()} 取，不要自己 new。 */
    private static final IncubationService INCUBATION = new IncubationService();

    private HatchRuntime() {
    }

    /**
     * 全项目唯一的孵化服务实例。
     *
     * <p>不要 new 第二个：{@code IncubationService} 自带配额表与票据表，
     * 两个实例之间不共享任何东西。
     */
    public static IncubationService incubation() {
        return INCUBATION;
    }

    /**
     * <b>全项目统一的 tick 时间基准。</b>{@code submit} 的 {@code nowTick}、
     * {@code lastActiveTick}、{@code tick} / {@code throwBall} / {@code deploy} 的时间参数
     * 都必须来自这里，否则同一张票的进度会算出错误的值。
     *
     * <p>用的是<b>主世界存档里的 gameTime</b>（{@code level.dat} 里持久化），选它的理由只有一条：
     * 它跨重启连续。改成 {@code MinecraftServer.getTickCount()} 会得到「每次启动从 0 开始」的计数器 ——
     * 存盘前 {@code startTick=100000}、重启后 {@code nowTick=200}，进度变成负数，
     * 玩家的球会永远停在读条起点（症状就是「重启后进度条归零」）。
     *
     * <p>为什么不用 {@code getDayTime()}：它会被 {@code /time set} 和睡觉跳夜改动，
     * 一次 {@code /time set 0} 就能让所有在孵票据的进度倒退几天。
     *
     * <p>为什么固定取<b>主世界</b>而不是「玩家所在维度」：各维度的 gameTime 是各自独立的
     * 计数，下界/末地可能比主世界小一大截；按维度取会让同一张票在跨维度时进度跳变。
     */
    public static long nowTick(MinecraftServer server) {
        return server.overworld().getLevelData().getGameTime();
    }

    /** 便捷重载：内部仍然取主世界的时间，避免调用方顺手写成 {@code player.level().getGameTime()}。 */
    public static long nowTick(ServerPlayer player) {
        return nowTick(player.level().getServer());
    }

    /**
     * 登录恢复：把存档里的在途票据接回唯一实例，<b>跳过本进程里已经存在的票据</b>。
     *
     * <p>为什么必须过滤：{@code IncubationService.restore} 对重复的 {@code ticketId}
     * 直接抛 {@link IllegalArgumentException}（它防的是「存档被写了两次」）。
     * 而票据一旦进了服务就没有移除接口 —— 同一进程内玩家断线重连时，
     * 存档里那张票<b>本来就还在内存里</b>，原样喂回 restore 会抛异常，
     * 而 restore 在登录路径上，抛出去就是「玩家进不了服」。
     * 过滤后的语义恰好是想要的：内存里的版本更新（可能已经从 CHARGING 走到 READY），
     * 存档只用来补内存里没有的那些。
     *
     * <p>配额仍然按存档值恢复：{@code restore} 内部用「服务里全部在途票据」重算底线，
     * 所以过滤掉票据不会让配额低于在途所需。
     *
     * @return 恢复结果；{@code quotaRepaired=true} 表示存档里的配额低于在途票据所需，
     *         即存档可能写过一半，调用方应当告警
     */
    public static IncubationService.RestoreResult restoreOnJoin(HatchSnapshot snapshot) {
        List<HatchTicket> missing = new ArrayList<>();
        for (HatchTicket t : snapshot.tickets()) {
            if (INCUBATION.ticket(t.ticketId()).isEmpty()) {
                missing.add(t);
            }
        }
        return INCUBATION.restore(new HatchSnapshot(snapshot.ownerKey(), snapshot.quota(), missing));
    }

    /**
     * 推进一次并播报「本次新就绪」。只应由 {@link ServerRules} 注册的
     * {@code END_SERVER_TICK} 调用。
     *
     * <p>异常在这里被吃掉而不上抛：一个玩家的票据数据坏掉，不该让整台服务器
     * 的 tick 循环中断（那会让所有人都卡住）。坏数据会在下次扫描继续报错，日志里能看到。
     */
    static void advance(MinecraftServer server) {
        long nowTick = nowTick(server);

        List<HatchTicket> ready;
        try {
            ready = INCUBATION.tick(nowTick);
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.error("[orbcraft] 孵化推进失败，本次扫描跳过（tick={}）：{}", nowTick, e.toString(), e);
            return;
        }
        if (ready.isEmpty()) {
            return;
        }

        // 只有真的有人就绪时才建这张表：平时不产生任何分配。
        Map<String, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.put(PlayerStore.ownerKeyOf(player.getUUID()), player);
        }

        for (HatchTicket ticket : ready) {
            ServerPlayer owner = online.get(ticket.ownerKey());
            if (owner == null) {
                // 离线就绪：票据停在 READY 等玩家上线（S4 恢复时不会重播，见 restore 语义）。
                OrbcraftMod.LOG.info("[orbcraft] 票据 {} 在主人离线时就绪，保留 READY 等待上线",
                        ticket.ticketId());
                continue;
            }
            owner.sendSystemMessage(Component.literal(
                    "[宝珠] 「" + ticket.spec().displayName() + "」充能完成，右键投出"));
        }
    }

    /** 该玩家在途（占着孵化栏）的票据。用于命令输出与离线日志，不改状态。 */
    static List<HatchTicket> inFlight(String ownerKey) {
        List<HatchTicket> out = new ArrayList<>();
        for (HatchTicket t : INCUBATION.ticketsOf(ownerKey)) {
            if (t.occupiesSlot()) {
                out.add(t);
            }
        }
        return out;
    }
}
