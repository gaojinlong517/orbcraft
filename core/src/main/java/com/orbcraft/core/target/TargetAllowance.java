package com.orbcraft.core.target;

import com.orbcraft.core.draft.BaseKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 每日定向配额的不可变账本：装备 / 消耗品 / 召唤物三类各自独立计次。
 *
 * <p>旧的规则是「每日 3 次」笼统计数，问题是配额会被错误的类型吃掉 ——
 * 玩家想定向召唤物，却因为点了装备卡池而把当天的额度用在装备上，且无法反悔。
 * 现在改成三类各 1 次（总量仍是 3 次），先选类型再选卡，浪费只可能发生在同一类内。
 *
 * <p><b>为什么不可变。</b>配额是「已经发生的事实」，不是可回滚的变量。
 * {@link #use} 返回新实例，调用方只有把新实例存回自己的会话快照，扣减才生效；
 * 忘记保存等于这次操作没发生，而不是「扣了一半」。这同时让 UI 可以放心地把账本
 * 挂在快照里做前后对比（{@code before.remaining()} / {@code after.remaining()}），
 * 不必担心渲染期间被别人改掉。
 *
 * <p><b>跨天归零</b>以 {@code epochDay} 为键做，不依赖任何定时清理任务：
 * 「今天的额度」只按 (玩家, 今天) 这个键查表，昨天的记录根本不参与计算，
 * 因此服务器停机一夜、或玩家跨时区上线，都能得到正确结果。
 *
 * <p><b>淘汰策略。</b>每次成功 {@link #use} 产生的新实例里，早于
 * {@code newestDay - retentionDays}（默认 7 天）的记录被丢弃，其中 {@code newestDay}
 * 是截至目前见过的最晚一天。等价于只保留最近 {@code retentionDays} 天（含当天）的账本。
 * 理由：账本只需要回答「今天用了几次」，历史数据没有任何业务用途；
 * 而玩家数 × 天数的笛卡尔积如果不淘汰，进程活一个月就会变成内存里的隐形泄漏。
 * 被淘汰的天再查会返回 0，效果等同「那天没花过额度」—— 7 天前的补扣没有意义。
 */
public final class TargetAllowance {

    /** 默认保留天数。见类注释的淘汰策略。 */
    public static final int DEFAULT_RETENTION_DAYS = 7;

    private final int perKindPerDay;
    private final int retentionDays;
    private final long newestDay;
    private final Map<Slot, EnumMap<BaseKind, Integer>> book;

    private record Slot(String ownerKey, long epochDay) {
    }

    private TargetAllowance(int perKindPerDay,
                            int retentionDays,
                            long newestDay,
                            Map<Slot, EnumMap<BaseKind, Integer>> book) {
        this.perKindPerDay = perKindPerDay;
        this.retentionDays = retentionDays;
        this.newestDay = newestDay;
        this.book = book;
    }

    /**
     * 每类型每日 {@code perKindPerDay} 次，保留最近 {@link #DEFAULT_RETENTION_DAYS} 天。
     *
     * @throws TargetException {@code BAD_ARGUMENT} 配额为负
     */
    public static TargetAllowance daily(int perKindPerDay) {
        return daily(perKindPerDay, DEFAULT_RETENTION_DAYS);
    }

    /**
     * @param retentionDays 保留天数，必须 ≥ 1
     * @throws TargetException {@code BAD_ARGUMENT} 配额为负或保留天数 &lt; 1
     */
    public static TargetAllowance daily(int perKindPerDay, int retentionDays) {
        if (perKindPerDay < 0) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                    "每类型每日配额不能为负：" + perKindPerDay);
        }
        if (retentionDays < 1) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                    "保留天数必须 ≥ 1：" + retentionDays);
        }
        return new TargetAllowance(perKindPerDay, retentionDays, Long.MIN_VALUE, Map.of());
    }

    /**
     * 记一次使用。
     *
     * <p>额度够：返回**新实例**，本实例一个字节都不改。
     * 额度不够：返回 {@link Optional#empty()}，<b>不抛异常</b>。
     *
     * <p>为什么是 Optional 而不是异常：配额用完是玩法内的正常分支 —— 玩家每天都会遇到，
     * UI 的正常反应是灰化按钮、显示「今天召唤物定向已用 1/1」。用异常表达一个每秒都可能
     * 发生的业务分支，会让调用链被迫写 try/catch 做流程控制，也把
     * 「参数写错了」这种真正的 bug 淹掉。异常留给 {@link TargetException.Code#BAD_ARGUMENT}。
     *
     * @throws TargetException {@code BAD_ARGUMENT} 玩家 id 为空 / 类型为 null
     */
    public Optional<TargetAllowance> use(String ownerKey, long epochDay, BaseKind kind) {
        requireOwner(ownerKey);
        requireKind(kind);
        if (used(ownerKey, epochDay, kind) >= perKindPerDay) {
            return Optional.empty();
        }
        Map<Slot, EnumMap<BaseKind, Integer>> next = deepCopy(book);
        next.computeIfAbsent(new Slot(ownerKey, epochDay), key -> emptyCounts())
                .merge(kind, 1, Integer::sum);

        long newest = Math.max(newestDay, epochDay);
        long cutoff = newest - retentionDays;
        next.keySet().removeIf(slot -> slot.epochDay() <= cutoff);

        return Optional.of(new TargetAllowance(
                perKindPerDay, retentionDays, newest, Collections.unmodifiableMap(next)));
    }

    /** 该玩家该天该类型已用次数；未知组合一律 0。 */
    public int used(String ownerKey, long epochDay, BaseKind kind) {
        requireOwner(ownerKey);
        requireKind(kind);
        EnumMap<BaseKind, Integer> counts = book.get(new Slot(ownerKey, epochDay));
        return counts == null ? 0 : counts.getOrDefault(kind, 0);
    }

    /** 该玩家该天该类型剩余次数，不会为负。 */
    public int remaining(String ownerKey, long epochDay, BaseKind kind) {
        return Math.max(0, perKindPerDay - used(ownerKey, epochDay, kind));
    }

    /** 该玩家该天三类合计已用次数，正常情况下不会超过 {@code 3 × perKindPerDay}。 */
    public int totalUsed(String ownerKey, long epochDay) {
        requireOwner(ownerKey);
        int total = 0;
        for (BaseKind kind : BaseKind.values()) {
            total += used(ownerKey, epochDay, kind);
        }
        return total;
    }

    /** 每类型每日配额。 */
    public int perKindPerDay() {
        return perKindPerDay;
    }

    /** 保留天数。 */
    public int retentionDays() {
        return retentionDays;
    }

    /** 当前账本里还在的天数（诊断用，也给自测验证淘汰行为）。 */
    public int trackedDays() {
        return book.size();
    }

    private static Map<Slot, EnumMap<BaseKind, Integer>> deepCopy(
            Map<Slot, EnumMap<BaseKind, Integer>> source) {
        Map<Slot, EnumMap<BaseKind, Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<Slot, EnumMap<BaseKind, Integer>> entry : source.entrySet()) {
            EnumMap<BaseKind, Integer> counts = emptyCounts();
            counts.putAll(entry.getValue());
            copy.put(entry.getKey(), counts);
        }
        return copy;
    }

    private static EnumMap<BaseKind, Integer> emptyCounts() {
        return new EnumMap<>(BaseKind.class);
    }

    private static String requireOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "玩家标识不能为空");
        }
        return ownerKey;
    }

    private static BaseKind requireKind(BaseKind kind) {
        if (kind == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "基调不能为 null");
        }
        return kind;
    }
}
