package com.orbcraft.core.target;

import com.orbcraft.core.draft.BaseKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 账本一格的持久化断面：某玩家某天三类各用了几次。
     *
     * <p>只记已用次数，不记「剩余」：剩余是 {@code perKindPerDay} 减出来的派生值，
     * 存两个数就会出现「配额改成每天 2 次了，存档里还剩 3 次」这种自相矛盾的状态。
     */
    public record Snapshot(String ownerKey, long epochDay, Map<BaseKind, Integer> used) {

        public Snapshot {
            if (ownerKey == null || ownerKey.isBlank()) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT, "断面里的玩家标识不能为空");
            }
            if (used == null) {
                used = Map.of();
            }
            EnumMap<BaseKind, Integer> copy = emptyCounts();
            copy.putAll(used);
            used = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 导出全部账本记录，供平台层落盘。
     *
     * <p><b>顺序是确定的</b>（先按天、再按玩家），不是随 {@link #book} 的哈希序。
     * 序列化结果要能逐字节比较 —— 同样的账本导出两次必须相等，否则「存档有没有变」
     * 只能靠时间戳判断，脏写永远查不出来。
     *
     * <p>只导出非零格：{@link #use} 只会写入被用掉的类型，没人用过的格本来就不在 {@code book} 里。
     */
    public List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>(book.size());
        book.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Slot, EnumMap<BaseKind, Integer>>>comparingLong(
                                e -> e.getKey().epochDay())
                        .thenComparing(e -> e.getKey().ownerKey()))
                .forEach(e -> out.add(
                        new Snapshot(e.getKey().ownerKey(), e.getKey().epochDay(), e.getValue())));
        return List.copyOf(out);
    }

    /**
     * 从断面重建账本，用于进程重启后恢复。
     *
     * <p>三个刻意的选择：
     * <ul>
     *   <li><b>不按 {@code perKindPerDay} 夹取</b>。存档里记了 2 次，而配额被调成 1 次，
     *       保留 2 才是事实；夹成 1 等于替玩家把额度退了，且改配置会静默改写历史。
     *       {@link #remaining} 本身已经取 {@code Math.max(0, ...)}，不会算出负数。</li>
     *   <li><b>重复格直接拒绝</b>。同一 (玩家, 天) 出现两条只可能是序列化写重了；
     *       悄悄累加或覆盖都会把一个 bug 变成看不出来的数值偏差。</li>
     *   <li><b>淘汰规则与 {@link #use} 一致</b>，都相对 {@code newestDay} 计算，
     *       否则「停机 10 天再上线」会恢复出一堆本该被淘汰的旧格子。</li>
     * </ul>
     *
     * @throws TargetException {@code BAD_ARGUMENT} 参数非法 / 次数为负 / 同一格重复
     */
    public static TargetAllowance restore(int perKindPerDay, int retentionDays,
                                          Collection<Snapshot> snapshots) {
        if (perKindPerDay < 0) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                    "每类型每日配额不能为负：" + perKindPerDay);
        }
        if (retentionDays < 1) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                    "保留天数必须 ≥ 1：" + retentionDays);
        }
        if (snapshots == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "断面集合不能为空引用");
        }

        Map<Slot, EnumMap<BaseKind, Integer>> loaded = new LinkedHashMap<>();
        long newest = Long.MIN_VALUE;
        for (Snapshot s : snapshots) {
            if (s == null) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT, "断面集合里含 null 元素");
            }
            EnumMap<BaseKind, Integer> counts = emptyCounts();
            for (Map.Entry<BaseKind, Integer> e : s.used().entrySet()) {
                int n = e.getValue() == null ? 0 : e.getValue();
                if (n < 0) {
                    throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                            "断面次数不能为负：" + s.ownerKey() + "@" + s.epochDay() + " " + e.getKey());
                }
                if (n > 0) {
                    counts.put(e.getKey(), n);
                }
            }
            Slot slot = new Slot(s.ownerKey(), s.epochDay());
            if (loaded.putIfAbsent(slot, counts) != null) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT,
                        "同一格出现两条断面，存档可能被重复写入：" + s.ownerKey() + "@" + s.epochDay());
            }
            newest = Math.max(newest, s.epochDay());
        }

        if (!loaded.isEmpty()) {
            long cutoff = newest - retentionDays;
            loaded.keySet().removeIf(slot -> slot.epochDay() <= cutoff);
        }
        return new TargetAllowance(perKindPerDay, retentionDays, newest,
                Collections.unmodifiableMap(loaded));
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
