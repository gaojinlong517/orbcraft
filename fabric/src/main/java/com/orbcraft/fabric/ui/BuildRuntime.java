package com.orbcraft.fabric.ui;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.target.TargetAllowance;
import com.orbcraft.fabric.contract.PlayerStore;

import net.minecraft.server.level.ServerPlayer;

/**
 * 构筑入口的进程级运行时：唯一的孵化服务实例 + 每个玩家的席位 + 定向卡账本。
 *
 * <h2>为什么要有一个「唯一实例」的地方</h2>
 * <p>{@link IncubationService} 内部同时持有配额和票据。构筑入口的 {@code preview/submit}、
 * 联机层的 {@code tick}、存档层的 {@code restore} 必须作用在<b>同一份</b>实例上，
 * 否则玩家会在 A 账本里扣了额度、在 B 账本里看进度条 —— 症状是「球一直是 0%」或
 * 「配额明明满了还能造」，而两份代码各自都自测通过。
 *
 * <p>目前这个实例由 S2 托管（构筑是它的第一个使用者）。S4 / S5 请直接调
 * {@link #incubation()} 取用，<b>不要自己 new</b>。如果主线决定把实例挪到别处，
 * 这里是唯一需要改的引用点。
 *
 * <h2>席位（{@link BuildSeat}）的回收</h2>
 * <p>席位只是「这一次构筑的过程状态」，纯内存、丢了不影响存档，所以 30 分钟无操作就回收。
 * 定向卡账本（{@link TargetAllowance}）不能跟着回收 —— 它是当天已经用掉的次数，
 * 丢掉等于给玩家退额度，所以两份数据分开存。
 */
public final class BuildRuntime {

    private static final Logger LOG = LoggerFactory.getLogger("orbcraft/build");

    /** 每类型每日定向次数。与 {@code TargetDeck} 的「三类各 1 次、总量 3 次」一致。 */
    private static final int TARGETS_PER_KIND_PER_DAY = 1;

    /** 席位空闲回收阈值：20 tick/秒 × 60 秒 × 30 分钟。 */
    private static final long SEAT_IDLE_TICKS = 20L * 60L * 30L;

    private static final IncubationService INCUBATION = new IncubationService();

    private static final Map<String, BuildSeat> SEATS = new HashMap<>();
    private static final Map<String, TargetAllowance> ALLOWANCES = new HashMap<>();

    private BuildRuntime() {
    }

    /**
     * 全服唯一的孵化服务。
     *
     * <p>S4 用它 {@code restore}、S5 用它 {@code tick}、S2 用它 {@code preview/submit}。
     * 三处拿到的必须是同一个对象，理由见类注释。
     */
    public static IncubationService incubation() {
        return INCUBATION;
    }

    /**
     * 「今天」的 epochDay —— 定向卡每日额度的日期键。
     *
     * <p>用服务器本地日期而不是 UTC：玩家感觉到的是「我这儿过了零点」，
     * 用 UTC 会让东八区的玩家在早上 8 点才刷新额度，看起来像 bug。
     */
    public static long todayEpochDay() {
        return LocalDate.now().toEpochDay();
    }

    /** 取（必要时创建）该玩家的构筑席位。 */
    static synchronized BuildSeat seat(ServerPlayer player) {
        String ownerKey = PlayerStore.ownerKeyOf(player.getUUID());
        long now = player.level().getGameTime();
        BuildSeat seat = SEATS.get(ownerKey);
        if (seat == null) {
            purgeIdle(now);
            seat = new BuildSeat(ownerKey, player.getUUID());
            SEATS.put(ownerKey, seat);
        }
        seat.touch(now);
        return seat;
    }

    /** 该玩家今天的定向卡账本；没有记录时给一份全新账本（不会凭空写入记录）。 */
    public static synchronized TargetAllowance allowanceOf(String ownerKey) {
        TargetAllowance book = ALLOWANCES.get(ownerKey);
        if (book == null) {
            book = TargetAllowance.daily(TARGETS_PER_KIND_PER_DAY);
            ALLOWANCES.put(ownerKey, book);
        }
        return book;
    }

    /**
     * 存档层恢复账本时调用。
     *
     * <p>现在是内存账本，重启即归零；S4 接上以后，登录时把存档里的断面
     * {@code TargetAllowance.restore(...)} 出来，再从这里装回来，额度才能真正跨重启。
     */
    public static synchronized void installAllowance(String ownerKey, TargetAllowance allowance) {
        if (ownerKey == null || allowance == null) {
            throw new IllegalArgumentException("ownerKey 与 allowance 都不能为空");
        }
        ALLOWANCES.put(ownerKey, allowance);
    }

    /**
     * 记一次定向卡使用 —— 全项目<b>唯一</b>允许扣定向额度的地方。
     *
     * <p>{@code TargetAllowance.use} 返回的是<b>新实例</b>，旧实例一个字节都不改。
     * 调用方拿到新实例却不存回来，这次操作就等于没发生（这是设计，不是 bug）。
     * 所以「存回」这件事被收进这个方法里，外面拿不到「忘了存」的机会。
     *
     * @return 扣减后的账本；额度已用完时为空（不抛异常 —— 每天都会遇到，是正常玩法分支）
     */
    static synchronized Optional<TargetAllowance> spendTarget(String ownerKey, long epochDay, BaseKind kind) {
        TargetAllowance current = allowanceOf(ownerKey);
        Optional<TargetAllowance> next = current.use(ownerKey, epochDay, kind);
        next.ifPresent(after -> {
            ALLOWANCES.put(ownerKey, after);
            LOG.info("[orbcraft/build] 定向卡额度扣减：{} {} @{} 剩余 {}",
                    ownerKey, kind.zh(), epochDay, after.remaining(ownerKey, epochDay, kind));
        });
        return next;
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
