package com.orbcraft.fabric.ui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.store.OrbcraftStore;

import net.minecraft.server.level.ServerPlayer;

/**
 * 构筑席位的登记处 —— 只管「一次进行中的构筑」这份纯内存状态。
 *
 * <p><b>这里刻意不持有 {@code IncubationService}，也不持有定向卡账本。</b>
 * 那两样的唯一真相在 {@code OrbcraftStore}（S4）：它登录时 {@code restore}、定时落盘，
 * 并且在 {@code SERVER_STARTED} 时<b>换掉</b>孵化实例（同一 JVM 换存档时必须换）。
 * S2 要是自己再 {@code new} 一个，submit 就把配额扣在 A 账本上、S5 的 tick 推 B 账本，
 * 症状是「球永远是 0%」或者「配额明明满了还能造」—— 而且两份代码各自都自测通过。
 *
 * <p><b>席位按 ownerKey（UUID，来自 {@link PlayerStore#ownerKeyOf}）做键</b>，
 * 不按 {@code ServerPlayer} 实例：玩家过传送门或重登都会换实例，用实例做键会让
 * 进行中的构筑凭空消失，而那时他可能已经花掉当天的定向额度。
 *
 * <p><b>为什么席位可以回收、额度不能</b>：席位只是「这一次选到哪张牌」，
 * 纯内存、丢了不影响存档；额度是「今天已经用掉的次数」，丢了就是给玩家退额度。
 * 所以回收只针对席位，额度在 S4 的会话里。
 */
public final class BuildRuntime {

    /** 席位空闲回收阈值：20 tick/秒 × 60 秒 × 30 分钟。 */
    private static final long SEAT_IDLE_TICKS = 20L * 60L * 30L;

    private static final Map<String, BuildSeat> SEATS = new HashMap<>();

    private BuildRuntime() {
    }

    /** 取（必要时创建）该玩家的构筑席位。 */
    static synchronized BuildSeat seat(ServerPlayer player) {
        String ownerKey = PlayerStore.ownerKeyOf(player.getUUID());
        // 用 S4 的「现在」而不是自己去取世界时间：席位的空闲判定与票据进度必须同一个时钟
        long now = OrbcraftStore.nowTick();
        BuildSeat seat = SEATS.get(ownerKey);
        if (seat == null) {
            purgeIdle(now);
            seat = new BuildSeat(ownerKey, player.getUUID());
            SEATS.put(ownerKey, seat);
        }
        seat.touch(now);
        return seat;
    }

    /**
     * 回收空闲席位。
     *
     * <p>只收没有构筑或构筑已结束的席位：正在选牌的人可能只是去翻了下仓库，
     * 把进行中的流程收掉会让他的卡牌凭空消失，而这不是他能预料的。
     */
    private static void purgeIdle(long nowTick) {
        Iterator<Map.Entry<String, BuildSeat>> it = SEATS.entrySet().iterator();
        while (it.hasNext()) {
            BuildSeat seat = it.next().getValue();
            if (nowTick - seat.lastTouchTick() <= SEAT_IDLE_TICKS) {
                continue;
            }
            if (seat.session() != null && !seat.session().phase().isTerminal()) {
                continue;
            }
            it.remove();
        }
    }
}
