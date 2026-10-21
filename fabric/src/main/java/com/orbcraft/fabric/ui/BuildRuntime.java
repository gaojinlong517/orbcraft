package com.orbcraft.fabric.ui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 构筑席位的登记处 —— 只管「一次进行中的构筑」这份纯内存状态。
 *
 * <p><b>这里刻意不持有 {@code IncubationService}，也不持有定向卡账本。</b>
 * 那两样是运行期单例，归 {@link OrbcraftRuntime}（主线所有）。孵化服务的状态在实例里、
 * 不在库里，S2 自己再 {@code new} 一个就是两套互不相干的票据表：submit 进的票在 A，
 * S1 投掷时在 B 里查不到，球永远 0% 而且不报错。
 *
 * <p><b>席位按 ownerKey（UUID，来自 {@link PlayerStore#ownerKeyOf}）做键</b>，
 * 不按 {@code ServerPlayer} 实例：玩家过传送门或重登都会换实例，用实例做键会让
 * 进行中的构筑凭空消失，而那时他可能已经花掉当天的定向额度。
 *
 * <p><b>为什么席位可以回收、额度不能</b>：席位只是「这一次选到哪张牌」，
 * 纯内存、丢了不影响存档；额度是「今天已经用掉的次数」，丢了就是给玩家退额度。
 * 所以回收只针对席位，额度在运行期单例里。
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
        // 与票据进度共用同一个「现在」：席位空闲判定用自己的时钟，就会出现
        // 「玩家看着还有构筑，实际上已经被判空闲收回」这种只在跨维度时复现的怪事
        long now = nowTick(player);
        BuildSeat seat = SEATS.get(ownerKey);
        if (seat == null) {
            purgeIdle(now);
            seat = new BuildSeat(ownerKey, player.getUUID());
            SEATS.put(ownerKey, seat);
        }
        seat.touch(now);
        return seat;
    }

    /** 玩家所在服务器的 game time；服务端未就绪时返回 0（此时也敲不了命令）。 */
    static long nowTick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0L : OrbcraftRuntime.nowTick(server);
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
