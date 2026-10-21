package com.orbcraft.core.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.orbcraft.core.build.Composition;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.draft.TierData;
import com.orbcraft.core.hatch.HatchException;
import com.orbcraft.core.hatch.HatchSnapshot;
import com.orbcraft.core.hatch.HatchState;
import com.orbcraft.core.hatch.HatchSubmit;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.core.target.TargetAllowance;
import com.orbcraft.core.target.TargetException;

/**
 * 存档断面自测 —— 只测「状态能不能原样出得来、原样回得去」这条边界。
 *
 * <p>这一层此前是<b>断的</b>：{@code TargetAllowance.book} 与
 * {@code IncubationService.tickets/quotas/ticketSeq} 全是私有字段，没有任何导出或恢复入口。
 * 结果是平台层的存档路（S4）拿到契约也写不出来 —— 验收条件「退出重进，在途孵化不丢、
 * 配额账本不重置」在 core 这一侧根本没有接口可调。本测试就是钉住新补的两个断面。
 *
 * <p>四条断言特别值得留着的理由：
 * <ol>
 *   <li><b>配额以存盘值为准，而不是从票据重算。</b> {@link QuotaManager} 没有 release，
 *       落地后的产物仍占装备位，而它们不在票据列表里。改成"从票据重算"，
 *       玩家重启一次就能多造一件装备 —— 这是本测试最想防的退化。</li>
 *   <li><b>序号必须推进。</b> {@code nextTicketId()} 是进程内自增，重启后从 tk-1 重来。
 *       不按恢复的 id 顶上去，新票据会覆盖玩家在孵的那张球。</li>
 *   <li><b>离手的票不回来。</b> THROWN / DEPLOYED 恢复进孵化栏会让「同时孵化」
 *       计数虚高，玩家栏位空着却被告知已满。</li>
 *   <li><b>超额历史不夹取。</b> 存档记 2 次、配额改成 1 次，答案仍是 2 次事实；
 *       夹成 1 等于改配置时顺手替玩家退了额度。</li>
 * </ol>
 *
 * <p>不需要数据文件：Spec / Affix / Composition 全部手搓，跑起来只有毫秒级。
 * 运行：{@code java -cp <out> com.orbcraft.core.integration.PersistenceSelfTest}
 */
public final class PersistenceSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        targetSnapshotShape();
        targetSnapshotRoundTrip();
        targetRestoreKeepsHistory();
        targetRestoreRetention();
        targetRestoreValidation();

        hatchRestoreOnlyInFlight();
        hatchQuotaFromSave();
        hatchQuotaRepaired();
        hatchTicketSeqAdvance();
        hatchRestoredProgressKept();
        hatchBarFullAfterRestore();
        hatchRestoreValidation();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("存档断面自测失败 " + failed + " 项");
        }
    }

    // ================================================================ 定向配额账本

    private static void targetSnapshotShape() {
        section("账本断面：顺序与内容");

        TargetAllowance a = TargetAllowance.daily(1);
        a = a.use("steve", 100L, BaseKind.EQUIPMENT).orElseThrow();
        a = a.use("alex", 100L, BaseKind.SUMMON).orElseThrow();
        a = a.use("steve", 99L, BaseKind.CONSUMABLE).orElseThrow();

        List<TargetAllowance.Snapshot> snap = a.snapshot();
        check("3 次使用导出 3 格", snap.size() == 3, "实际 " + snap.size());
        check("按 (天, 玩家) 升序",
                snap.get(0).epochDay() == 99L
                        && snap.get(1).epochDay() == 100L && snap.get(1).ownerKey().equals("alex")
                        && snap.get(2).epochDay() == 100L && snap.get(2).ownerKey().equals("steve"),
                describe(snap));

        // 导出两次必须逐元素相等：脏写判断依赖这个，否则只能靠时间戳猜
        check("同一账本导出两次完全相等", snap.equals(a.snapshot()), "两次导出不一致");
        check("断面的 used 不含 0 值格",
                snap.get(1).used().size() == 1 && snap.get(1).used().get(BaseKind.SUMMON) == 1,
                snap.get(1).used().toString());
    }

    private static void targetSnapshotRoundTrip() {
        section("账本断面：往返");

        TargetAllowance a = TargetAllowance.daily(1);
        a = a.use("steve", 500L, BaseKind.EQUIPMENT).orElseThrow();
        a = a.use("steve", 500L, BaseKind.SUMMON).orElseThrow();

        TargetAllowance b = TargetAllowance.restore(1, 7, a.snapshot());
        check("往返后装备次数不变", b.used("steve", 500L, BaseKind.EQUIPMENT) == 1,
                "实际 " + b.used("steve", 500L, BaseKind.EQUIPMENT));
        check("往返后召唤物次数不变", b.used("steve", 500L, BaseKind.SUMMON) == 1,
                "实际 " + b.used("steve", 500L, BaseKind.SUMMON));
        check("往返后剩余归零（配额 1）",
                b.remaining("steve", 500L, BaseKind.EQUIPMENT) == 0,
                "实际 " + b.remaining("steve", 500L, BaseKind.EQUIPMENT));
        check("未用过的类型仍是满额",
                b.remaining("steve", 500L, BaseKind.CONSUMABLE) == 1,
                "实际 " + b.remaining("steve", 500L, BaseKind.CONSUMABLE));

        TargetAllowance empty = TargetAllowance.restore(1, 7, List.of());
        check("空断面恢复出空账本", empty.trackedDays() == 0, "实际 " + empty.trackedDays());
        check("空账本仍可正常 use",
                empty.use("newbie", 1L, BaseKind.EQUIPMENT).isPresent(), "恢复后无法使用");
    }

    private static void targetRestoreKeepsHistory() {
        section("账本断面：超额历史不夹取");

        TargetAllowance old = TargetAllowance.daily(2);
        old = old.use("steve", 300L, BaseKind.EQUIPMENT).orElseThrow();
        old = old.use("steve", 300L, BaseKind.EQUIPMENT).orElseThrow();

        // 配额从每天 2 次调成 1 次，存档里那 2 次是既成事实
        TargetAllowance shrunk = TargetAllowance.restore(1, 7, old.snapshot());
        check("配额下调后历史次数保留为 2", shrunk.used("steve", 300L, BaseKind.EQUIPMENT) == 2,
                "实际 " + shrunk.used("steve", 300L, BaseKind.EQUIPMENT));
        check("剩余次数不会算成负数",
                shrunk.remaining("steve", 300L, BaseKind.EQUIPMENT) == 0,
                "实际 " + shrunk.remaining("steve", 300L, BaseKind.EQUIPMENT));

        TargetAllowance grown = TargetAllowance.restore(5, 7, old.snapshot());
        check("配额上调后剩余按新配额算", grown.remaining("steve", 300L, BaseKind.EQUIPMENT) == 3,
                "实际 " + grown.remaining("steve", 300L, BaseKind.EQUIPMENT));
    }

    private static void targetRestoreRetention() {
        section("账本断面：淘汰");

        List<TargetAllowance.Snapshot> raw = new ArrayList<>();
        raw.add(snap("steve", 100L, Map.of(BaseKind.EQUIPMENT, 1)));
        raw.add(snap("steve", 110L, Map.of(BaseKind.EQUIPMENT, 1)));

        TargetAllowance kept = TargetAllowance.restore(1, 7, raw);
        check("7 天内（差 10 天）的旧格被淘汰", kept.trackedDays() == 1,
                "实际 " + kept.trackedDays());
        check("淘汰后只留最新那天",
                kept.used("steve", 110L, BaseKind.EQUIPMENT) == 1
                        && kept.used("steve", 100L, BaseKind.EQUIPMENT) == 0,
                "旧天 " + kept.used("steve", 100L, BaseKind.EQUIPMENT));

        TargetAllowance loose = TargetAllowance.restore(1, 30, raw);
        check("保留 30 天时两格都在", loose.trackedDays() == 2, "实际 " + loose.trackedDays());
    }

    private static void targetRestoreValidation() {
        section("账本断面：非法输入");

        List<TargetAllowance.Snapshot> dup = List.of(
                snap("steve", 10L, Map.of(BaseKind.EQUIPMENT, 1)),
                snap("steve", 10L, Map.of(BaseKind.SUMMON, 1)));
        check("同一格出现两条 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetAllowance.restore(1, 7, dup)),
                "未拒绝重复格");

        List<TargetAllowance.Snapshot> neg = List.of(
                snap("steve", 10L, Map.of(BaseKind.EQUIPMENT, -1)));
        check("负次数 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetAllowance.restore(1, 7, neg)),
                "未拒绝负数");

        check("保留天数 0 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetAllowance.restore(1, 0, List.of())),
                "未拒绝非法保留天数");
    }

    // ================================================================ 孵化断面

    private static void hatchRestoreOnlyInFlight() {
        section("孵化断面：只接在途");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);
        List<HatchTicket> all = List.of(
                ticket("tk-1", "p", eq, HatchState.CHARGING, 0L, 10),
                ticket("tk-2", "p", eq, HatchState.READY, 0L, 10),
                ticket("tk-3", "p", eq, HatchState.THROWN, 0L, 10),
                ticket("tk-4", "p", eq, HatchState.DEPLOYED, 0L, 10));

        IncubationService.RestoreResult r = svc.restore(
                new HatchSnapshot("p", QuotaManager.State.empty(), all));
        check("CHARGING / READY 共恢复 2 张", r.restoredTickets() == 2,
                "实际 " + r.restoredTickets());
        check("THROWN 不回到孵化栏", svc.ticket("tk-3").isEmpty(), "被恢复了");
        check("DEPLOYED 不回到孵化栏", svc.ticket("tk-4").isEmpty(), "被恢复了");
        check("查得到恢复的票据", svc.ticket("tk-1").isPresent(), "查不到 tk-1");
        check("ticketsOf 只返回该玩家", svc.ticketsOf("p").size() == 2,
                "实际 " + svc.ticketsOf("p").size());
        check("其他玩家不受影响", svc.ticketsOf("q").isEmpty(),
                "实际 " + svc.ticketsOf("q").size());

        IncubationService emptySvc = new IncubationService();
        IncubationService.RestoreResult none = emptySvc.restore(HatchSnapshot.empty("p"));
        check("空断面恢复 0 张且配额为空",
                none.restoredTickets() == 0 && none.quota().usedPoints() == 0
                        && !none.quotaRepaired(),
                "实际 " + none.restoredTickets());
    }

    private static void hatchQuotaFromSave() {
        section("孵化断面：配额以存盘值为准");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);
        // 已落地的产物不在票据里，但它仍然占着装备位 —— 这是存盘值不可替代的地方
        QuotaManager.State saved = new QuotaManager.State(2, 0, 0, 2);
        IncubationService.RestoreResult r = svc.restore(new HatchSnapshot("p", saved,
                List.of(ticket("tk-1", "p", eq, HatchState.CHARGING, 0L, 10))));

        check("配额等于存盘值（不是票据重算的 1）",
                r.quota().equipment() == 2 && r.quota().usedPoints() == 2,
                "实际 " + r.quota());
        check("存盘值更高时不标记修复", !r.quotaRepaired(), "被误判为损坏");
        check("stateOf 反映存盘配额", svc.stateOf("p").equipment() == 2,
                "实际 " + svc.stateOf("p").equipment());
        check("装备位已满时再提交被拒", quotaRejected(svc, eq), "未被拒绝");
    }

    private static void hatchQuotaRepaired() {
        section("孵化断面：存档不自洽时按票据修");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);
        List<HatchTicket> tickets = List.of(
                ticket("tk-1", "p", eq, HatchState.CHARGING, 0L, 10),
                ticket("tk-2", "p", eq, HatchState.READY, 0L, 10));

        // 存盘配额是空的，但有两件装备在孵 —— 这份存档只存了票据没存配额
        IncubationService.RestoreResult r = svc.restore(
                new HatchSnapshot("p", QuotaManager.State.empty(), tickets));
        check("检测到不自洽并标记修复", r.quotaRepaired(), "未标记");
        check("按票据重算出装备 2 / 点数 2",
                r.quota().equipment() == 2 && r.quota().usedPoints() == 2,
                "实际 " + r.quota());
        check("修复后仍然拒绝第三件装备", quotaRejected(svc, eq), "未被拒绝");
    }

    private static void hatchTicketSeqAdvance() {
        section("孵化断面：票据序号推进");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);
        svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                List.of(ticket("tk-f", "p", eq, HatchState.READY, 0L, 10))));

        HatchTicket fresh = svc.submit(100L, 100L,
                HatchSubmit.of("p", composition(), BehaviorMode.NONE, MaterialTier.TRIAL));
        check("恢复 tk-f 之后新票据是 tk-10，不撞回 tk-1",
                fresh.ticketId().equals("tk-10"), "实际 " + fresh.ticketId());
        check("原票据没被覆盖", svc.ticket("tk-f").isPresent(), "tk-f 不见了");
        check("两张票据共存", svc.ticketsOf("p").size() == 2,
                "实际 " + svc.ticketsOf("p").size());
    }

    private static void hatchRestoredProgressKept() {
        section("孵化断面：进度不重置");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);
        svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                List.of(ticket("tk-1", "p", eq, HatchState.CHARGING, 0L, 10))));

        check("恢复后进度接着走（0 秒起 10 秒，200 tick 恰好就绪）",
                svc.ticket("tk-1").orElseThrow().progress(200L) >= 1.0,
                "进度 " + svc.ticket("tk-1").orElseThrow().progress(200L));
        check("恢复不重置起点（100 tick 时约半程）",
                Math.abs(svc.ticket("tk-1").orElseThrow().progress(100L) - 0.5) < 0.01,
                "进度 " + svc.ticket("tk-1").orElseThrow().progress(100L));
        List<HatchTicket> ready = svc.tick(200L);
        check("tick 把恢复的票据推进到就绪", ready.size() == 1
                && ready.get(0).state() == HatchState.READY, "实际 " + ready.size());
    }

    private static void hatchBarFullAfterRestore() {
        section("孵化断面：占满孵化栏");

        IncubationService svc = new IncubationService();
        // 消耗品不占配额，只占孵化栏，正好把栏位塞满
        Spec free = spec("spec.free", ProductType.CONSUMABLE, BehaviorMode.NONE, Rarity.COMMON);
        List<HatchTicket> full = new ArrayList<>();
        for (int i = 0; i < IncubationService.DEFAULT_MAX_CONCURRENT; i++) {
            full.add(ticket("tk-" + (i + 1), "p", free, HatchState.CHARGING, 0L, 10));
        }
        IncubationService.RestoreResult r = svc.restore(
                new HatchSnapshot("p", QuotaManager.State.empty(), full));
        check("恢复 5 张全部入栏", r.restoredTickets() == 5, "实际 " + r.restoredTickets());
        check("消耗品不触发配额修复", !r.quotaRepaired(), "被误判");

        boolean fullRejected = false;
        try {
            svc.submit(100L, 100L,
                    HatchSubmit.of("p", composition(), BehaviorMode.NONE, MaterialTier.TRIAL));
        } catch (HatchException e) {
            fullRejected = e.code() == HatchException.Code.BAR_FULL;
        }
        check("栏位已满时提交报 BAR_FULL", fullRejected, "未报 BAR_FULL");
    }

    private static void hatchRestoreValidation() {
        section("孵化断面：非法输入");

        IncubationService svc = new IncubationService();
        Spec eq = spec("spec.eq", ProductType.EQUIPMENT, BehaviorMode.NONE, Rarity.COMMON);

        check("断面归属与票据归属不一致 → 拒绝",
                throwsIllegal(() -> svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                        List.of(ticket("tk-1", "q", eq, HatchState.CHARGING, 0L, 10))))),
                "未拒绝");

        check("票据 id 重复 → 拒绝",
                throwsIllegal(() -> svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                        List.of(ticket("tk-1", "p", eq, HatchState.CHARGING, 0L, 10),
                                ticket("tk-1", "p", eq, HatchState.READY, 0L, 10))))),
                "未拒绝");

        check("null 断面 → 拒绝", throwsIllegal(() -> svc.restore(null)), "未拒绝");

        // 重复恢复同一张票也要挡住：说明平台层在重复登录时把存档吃了两遍
        svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                List.of(ticket("tk-9", "p", eq, HatchState.CHARGING, 0L, 10))));
        check("同一票据被恢复两次 → 拒绝",
                throwsIllegal(() -> svc.restore(new HatchSnapshot("p", QuotaManager.State.empty(),
                        List.of(ticket("tk-9", "p", eq, HatchState.CHARGING, 0L, 10))))),
                "未拒绝");
    }

    // ================================================================ 构造

    private static Spec spec(String id, ProductType type, BehaviorMode behavior, Rarity rarity) {
        int seconds = Math.min(30, rarity.maxHatchSeconds());
        return Spec.simple(id, id, type, rarity, behavior, seconds, Map.of(), List.of(), "");
    }

    private static HatchTicket ticket(String ticketId, String owner, Spec spec,
                                      HatchState state, long startTick, int seconds) {
        return new HatchTicket(ticketId, spec.id(), owner, startTick, seconds, state, spec);
    }

    private static TargetAllowance.Snapshot snap(String owner, long day, Map<BaseKind, Integer> used) {
        return new TargetAllowance.Snapshot(owner, day, used);
    }

    /** 手搓一张载体卡，用来走 submit（不依赖任何数据文件）。 */
    private static Composition composition() {
        Affix carrier = new Affix("carrier.gun", "carrier.gun", Layer.CARRIER, "枪", "ANY",
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                10, "gun", Map.of(Tier.WHITE, new TierData(Tier.WHITE, 1, 1.0, 4.0, null, "载体")));
        return new Composition(BaseKind.EQUIPMENT,
                List.of(new DraftCard(carrier, new TierData(Tier.WHITE, 1, 1.0, 4.0, null, "载体"))),
                1L, Rarity.COMMON, 1, 1.0, 1.0);
    }

    private static boolean quotaRejected(IncubationService svc, Spec spec) {
        try {
            svc.submit(100L, 100L, HatchSubmit.of("p",
                    compositionWith(spec), BehaviorMode.NONE, MaterialTier.TRIAL));
            return false;
        } catch (HatchException e) {
            return e.code() == HatchException.Code.QUOTA_REJECTED;
        }
    }

    /**
     * 造一个会结算成指定类别的 Composition。
     *
     * <p>只为了触发配额判定，所以直接复用同一张载体卡；结算出的 Spec 类别由
     * 载体卡的类型决定，因此这里按需换 kind。
     */
    private static Composition compositionWith(Spec spec) {
        BaseKind kind = switch (spec.type()) {
            case EQUIPMENT -> BaseKind.EQUIPMENT;
            case CONSUMABLE -> BaseKind.CONSUMABLE;
            default -> BaseKind.SUMMON;
        };
        Affix carrier = new Affix("carrier.gun", "carrier.gun", Layer.CARRIER, "枪", "ANY",
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                10, "gun", Map.of(Tier.WHITE, new TierData(Tier.WHITE, 1, 1.0, 4.0, null, "载体")));
        return new Composition(kind,
                List.of(new DraftCard(carrier, new TierData(Tier.WHITE, 1, 1.0, 4.0, null, "载体"))),
                1L, Rarity.COMMON, 1, 1.0, 1.0);
    }

    private static String describe(List<TargetAllowance.Snapshot> snap) {
        StringBuilder sb = new StringBuilder();
        for (TargetAllowance.Snapshot s : snap) {
            sb.append(s.ownerKey()).append('@').append(s.epochDay()).append(' ');
        }
        return sb.toString().trim();
    }

    private static boolean throwsTarget(TargetException.Code code, Runnable action) {
        try {
            action.run();
            return false;
        } catch (TargetException e) {
            return e.code() == code;
        }
    }

    private static boolean throwsIllegal(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void section(String title) {
        System.out.println("【" + title + "】");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }
}
