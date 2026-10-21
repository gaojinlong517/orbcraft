package com.orbcraft.fabric.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 孵化时间的唯一推进器。
 *
 * <p><b>本类是 {@code IncubationService.tick(nowTick)} 在全项目里唯一的调用点。</b>
 * 任何人再写一处「顺手 tick 一下」，同一张票就会在两次调用里各被判一次
 * 「本次新就绪」，玩家聊天栏收到两条一样的完成提示；更隐蔽的症状是
 * 进度看起来比别的票快 —— 因为 tick 只做状态迁移，不改进度，
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
 * <p><b>本类不持有孵化状态。</b> 唯一实例归主线
 * {@link OrbcraftRuntime#hatch()} —— S2 提交票、S1 投掷、S4 登录恢复、本类推进
 * 必须操作同一份内存状态。这里曾经自己 new 过一份，症状是「登录恢复成功、进度条永不增长、
 * 且没有任何报错」；那类错误在日志里查不到，只能从「实例有几个」这个结构上一劳永逸地排除。
 */
public final class HatchRuntime {

    private HatchRuntime() {
    }

    /**
     * 推进一次并播报「本次新就绪」。只应由 {@link ServerRules} 注册的
     * {@code END_SERVER_TICK} 调用。
     *
     * <p>异常在这里被吃掉而不上抛：一个玩家的票据数据坏掉，不该让整台服务器
     * 的 tick 循环中断（那会让所有人都卡住）。坏数据会在下次扫描继续报错，日志里能看到。
     */
    static void advance(MinecraftServer server) {
        long nowTick = OrbcraftRuntime.nowTick(server);

        List<HatchTicket> ready;
        try {
            ready = OrbcraftRuntime.hatch().tick(nowTick);
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
                // 注意下面那轮标脏并**不跳过**离线的 ownerKey —— 该票能真的写进存档，
                // 依赖 S4 的会话不随下线删除（下线只 saveNow、不销毁会话）。
                // 若哪天 S4 改成「下线即删会话」，这里会静默退化成不落盘：
                // 症状是离线玩家就绪后重启又收到一次播报。
                OrbcraftMod.LOG.info("[orbcraft] 票据 {} 在主人离线时就绪，保留 READY 等待上线",
                        ticket.ticketId());
                continue;
            }
            owner.sendSystemMessage(Component.literal(
                    "[宝珠] 「" + ticket.spec().displayName() + "」充能完成，右键投出"));
        }

        // CHARGING → READY 是真正的状态变更，必须让存档知道：否则崩服后重启，
        // 票还是 CHARGING，下次扫描又翻一次 READY —— 玩家会收到第二条完成的提示。
        // 只标脏不同步写盘，落盘时机归 S4。
        PlayerStore store = ServiceLookup.playerStore();
        if (store != null) {
            for (String ownerKey : distinctOwners(ready)) {
                store.markDirty(ownerKey);
            }
        }
    }

    /** 该玩家在途（占着孵化栏）的票据。用于命令输出与离线日志，不改状态。 */
    static List<HatchTicket> inFlight(String ownerKey) {
        List<HatchTicket> out = new ArrayList<>();
        for (HatchTicket t : OrbcraftRuntime.hatch().ticketsOf(ownerKey)) {
            if (t.occupiesSlot()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> distinctOwners(List<HatchTicket> tickets) {
        List<String> owners = new ArrayList<>(2);
        for (HatchTicket t : tickets) {
            if (!owners.contains(t.ownerKey())) {
                owners.add(t.ownerKey());
            }
        }
        return owners;
    }
}
