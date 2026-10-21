package com.orbcraft.core.hatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.orbcraft.core.build.BuildConfig;
import com.orbcraft.core.build.BuildSession;
import com.orbcraft.core.build.Composition;
import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.draft.TierData;
import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.core.settle.CompositionSettler;
import com.orbcraft.core.settle.HatchTime;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.settle.SettleRequest;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;

/**
 * 孵化链路自测 —— 只测 {@code IncubationService} 这一层，但把它钉在真实的
 * {@code AffixTable} / {@code BuildSession} / {@code CompositionSettler} 上。
 *
 * <p>分两类用例，理由和 {@code SettleSelfTest} 相同：
 * <ul>
 *   <li><b>假 Composition</b>（一张载体卡 + 指定稀有度）：能把时长、配额档位、
 *       祭坛倍率的边界卡死到具体数字。真数据出不了"传说 600 秒 + 祭坛"这种组合，
 *       只能靠手搓。</li>
 *   <li><b>真 Composition</b>（四层确认制跑完）：证明从抽卡到落地整条链路
 *       在真实卡表上不炸 —— 尤其是稀有度分配与时长上限的相互作用。</li>
 * </ul>
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.hatch.HatchPipelineSelfTest [dataDir]}
 */
public final class HatchPipelineSelfTest {

    private static final int TPS = 20;

    private static int passed;
    private static int failed;
    private static Path dataDir;
    private static AffixTable table;
    private static BuildConfig config;

    public static void main(String[] args) throws Exception {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        loadData();

        submitValidation();
        ticketValidation();
        quotaConsume();
        quotaReject();
        previewPurity();
        submitSecondCheck();
        tickFlow();
        throwFlow();
        deployFlow();
        offlineCap();
        altarBoost();
        concurrentLimit();
        ticketIdentity();
        realPipeline();
        threadSafety();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("孵化链路自测失败 " + failed + " 项");
        }
    }

    // ================================================================ 数据

    private static void loadData() throws IOException {
        section("卡表加载");
        table = AffixData.loadTable(
                read("affixes.carrier.json"),
                read("affixes.element.json"),
                read("affixes.attribute.json"),
                read("affixes.effect.json"));
        config = BuildConfig.defaults();
        check("总词条 1124 条", table.size() == 1124, "实际 " + table.size());
        check("默认离线封顶 = 8 小时",
                IncubationService.DEFAULT_OFFLINE_CAP_TICKS == 20L * 3600 * 8,
                "" + IncubationService.DEFAULT_OFFLINE_CAP_TICKS);
        check("默认同时孵化上限 = 5", IncubationService.DEFAULT_MAX_CONCURRENT == 5, "");
    }

    // ================================================================ 输入契约

    private static void submitValidation() {
        section("HatchSubmit 构造期校验");
        Composition ok = fake(BaseKind.EQUIPMENT, Rarity.COMMON, 1.0, 1L);

        expectIae("ownerKey 为空 → 拒绝",
                () -> new HatchSubmit(" ", ok, BehaviorMode.NONE, MaterialTier.TRIAL, Map.of(), false));
        expectIae("composition 为空 → 拒绝",
                () -> new HatchSubmit("p", null, BehaviorMode.NONE, MaterialTier.TRIAL, Map.of(), false));
        expectIae("behavior 为空 → 拒绝",
                () -> new HatchSubmit("p", ok, null, MaterialTier.TRIAL, Map.of(), false));
        expectIae("material 为空 → 拒绝",
                () -> new HatchSubmit("p", ok, BehaviorMode.NONE, null, Map.of(), false));

        HatchSubmit h = new HatchSubmit("p", ok, BehaviorMode.NONE, MaterialTier.TRIAL, null, false);
        check("extraMaterials=null → 空表", h.extraMaterials().isEmpty(), "" + h.extraMaterials());

        HatchSubmit altar = HatchSubmit.of("p", ok, BehaviorMode.NONE, MaterialTier.STANDARD).withAltar(true);
        check("withAltar 只改祭坛位", altar.onAltar()
                && altar.material() == MaterialTier.STANDARD
                && altar.ownerKey().equals("p"), "");
    }

    private static void ticketValidation() {
        section("HatchTicket 构造期校验");
        Spec spec = fakeSpec(BaseKind.EQUIPMENT, Rarity.LEGENDARY, 1.0, 7L);

        expectIae("specId 与 spec.id() 不一致 → 拒绝",
                () -> new HatchTicket("tk-1", "spec-other", "p", 0, 600, HatchState.CHARGING, spec));
        expectIae("startTick 为负 → 拒绝",
                () -> new HatchTicket("tk-1", spec.id(), "p", -1, 600, HatchState.CHARGING, spec));
        expectIae("totalSeconds = 0 → 拒绝",
                () -> new HatchTicket("tk-1", spec.id(), "p", 0, 0, HatchState.CHARGING, spec));
        expectIae("totalSeconds 超过该稀有度上限 → 拒绝",
                () -> new HatchTicket("tk-1", spec.id(), "p", 0, 601, HatchState.CHARGING, spec));

        HatchTicket t = new HatchTicket("tk-1", spec.id(), "p", 0, 600, HatchState.CHARGING, spec);
        HatchTicket ready = t.withState(HatchState.READY);
        check("withState 返回新实例", ready != t && t.state() == HatchState.CHARGING, "");
        check("500 秒时进度约 0.833", Math.abs(t.progress(500 * TPS) - 500.0 / 600.0) < 1e-9,
                "" + t.progress(500 * TPS));
        check("600 秒时就绪", t.isReady(600 * TPS), "");
        check("599.95 秒时未就绪", !t.isReady(600 * TPS - 1), "");
        check("剩余秒数向上取整", t.remainingSeconds(0) == 600, "" + t.remainingSeconds(0));
        check("loreBar 带百分比", t.loreBar(300 * TPS).contains("50%"), t.loreBar(300 * TPS));
        check("进度单调不越界", t.progress(9999 * TPS) == 1.0, "" + t.progress(9999 * TPS));
        check("占坑判定：CHARGING/READY 占，THROWN/DEPLOYED 不占",
                t.occupiesSlot() && ready.occupiesSlot()
                        && !ready.withState(HatchState.THROWN).occupiesSlot()
                        && !ready.withState(HatchState.DEPLOYED).occupiesSlot(), "");

        HatchException e = new HatchException(HatchException.Code.ALREADY_FINISHED, "已结束");
        check("HatchException 带 code 与可读描述",
                e.code() == HatchException.Code.ALREADY_FINISHED && e.describe().contains("已结束"), e.describe());
    }

    // ================================================================ 配额

    private static void quotaConsume() {
        section("三类产物的配额扣减");
        IncubationService svc = new IncubationService();

        HatchTicket equip = svc.submit(1000, 1000, equipSubmit("p", 11L));
        QuotaManager.State s1 = svc.stateOf("p");
        check("装备：equipment=1 / 1 点", s1.equipment() == 1 && s1.usedPoints() == 1, s1.toString());
        check("装备：类别 EQUIPMENT、cost 1",
                svc.preview(equipSubmit("p", 12L)).category() == QuotaManager.Category.EQUIPMENT
                        && svc.preview(equipSubmit("p", 12L)).cost() == 1, "");

        HatchTicket attack = svc.submit(1000, 1000, summonSubmit("p", 21L, BehaviorMode.ATTACK));
        QuotaManager.State s2 = svc.stateOf("p");
        check("攻击型主宠：primary=1 / +3 点 → 共 4 点",
                s2.primary() == 1 && s2.usedPoints() == 4, s2.toString());

        HatchTicket consumable = svc.submit(1000, 1000, consumableSubmit("p", 31L));
        QuotaManager.State s3 = svc.stateOf("p");
        check("消耗品：不占配额（equipment/primary/点数全不变）",
                s3.equipment() == 1 && s3.primary() == 1 && s3.usedPoints() == 4, s3.toString());
        check("消耗品：类别 FREE、cost 0",
                svc.preview(consumableSubmit("p", 32L)).category() == QuotaManager.Category.FREE
                        && svc.preview(consumableSubmit("p", 32L)).cost() == 0, "");
        check("消耗品也拿到票据", consumable.state() == HatchState.CHARGING, consumable.state().name());

        check("三张票据都在", svc.ticketsOf("p").size() == 3, "" + svc.ticketsOf("p").size());
        check("守护型单独算 2 点（用独立玩家验证）",
                guardCost() == 2, "" + guardCost());
        check("装备与消耗品的 spec 类型正确",
                svc.ticket(equip.ticketId()).orElseThrow().spec().type() == ProductType.EQUIPMENT
                        && svc.ticket(consumable.ticketId()).orElseThrow().spec().type()
                        == ProductType.CONSUMABLE, "");
        check("召唤物 spec 带行为模式",
                svc.ticket(attack.ticketId()).orElseThrow().spec().behavior() == BehaviorMode.ATTACK, "");
    }

    private static int guardCost() {
        IncubationService svc = new IncubationService();
        svc.submit(1000, 1000, summonSubmit("g", 41L, BehaviorMode.GUARD));
        return svc.stateOf("g").usedPoints();
    }

    private static void quotaReject() {
        section("配额拒绝且状态不变");
        IncubationService svc = new IncubationService();
        svc.submit(1000, 1000, equipSubmit("p", 51L));
        svc.submit(1000, 1000, equipSubmit("p", 52L));
        QuotaManager.State before = svc.stateOf("p");
        int ticketsBefore = svc.ticketsOf("p").size();

        expectCode("装备第 3 件 → QUOTA_REJECTED", HatchException.Code.QUOTA_REJECTED,
                () -> svc.submit(1000, 1000, equipSubmit("p", 53L)));
        check("装备第 3 件被拒后配额仍是 2 件 2 点",
                svc.stateOf("p").equals(before) && before.equipment() == 2 && before.usedPoints() == 2,
                svc.stateOf("p").toString());
        check("装备第 3 件被拒后没有残留票据",
                svc.ticketsOf("p").size() == ticketsBefore, "" + svc.ticketsOf("p").size());
        check("preview 也如实说不能造",
                !svc.preview(equipSubmit("p", 53L)).allowed(), svc.preview(equipSubmit("p", 53L)).reason());

        // 主宠唯一性
        IncubationService s2 = new IncubationService();
        s2.submit(1000, 1000, summonSubmit("q", 61L, BehaviorMode.ATTACK));
        QuotaManager.State qBefore = s2.stateOf("q");
        expectCode("主宠第 2 只（守护型）→ QUOTA_REJECTED", HatchException.Code.QUOTA_REJECTED,
                () -> s2.submit(1000, 1000, summonSubmit("q", 62L, BehaviorMode.GUARD)));
        check("主宠第 2 只被拒后 primary 仍为 1、点数 3",
                s2.stateOf("q").equals(qBefore) && qBefore.primary() == 1 && qBefore.usedPoints() == 3,
                s2.stateOf("q").toString());

        // 混乱体：数量上限 5，池也正好 5 点
        IncubationService s3 = new IncubationService(IncubationService.DEFAULT_OFFLINE_CAP_TICKS, 20);
        for (int i = 0; i < 5; i++) {
            s3.submit(1000, 1000, summonSubmit("r", 70L + i, BehaviorMode.CHAOS));
        }
        check("混乱体 5 只全部允许、点数打满", s3.stateOf("r").usedPoints() == 5,
                s3.stateOf("r").toString());
        expectCode("混乱体第 6 只 → QUOTA_REJECTED", HatchException.Code.QUOTA_REJECTED,
                () -> s3.submit(1000, 1000, summonSubmit("r", 79L, BehaviorMode.CHAOS)));
        check("池满时消耗品仍可造（FREE 不受影响）",
                s3.preview(consumableSubmit("r", 81L)).allowed(), s3.preview(consumableSubmit("r", 81L)).reason());
        check("负数剩余点不会出现", s3.stateOf("r").remainingPoints() >= 0, s3.stateOf("r").toString());
    }

    private static void previewPurity() {
        section("preview 完全不改变状态");
        IncubationService svc = new IncubationService();
        HatchSubmit submit = equipSubmit("p", 91L);

        IncubationService.Preview a = svc.preview(submit);
        IncubationService.Preview b = svc.preview(submit);
        check("两次 preview 结果相同", a.equals(b), a.toString() + " vs " + b.toString());
        check("preview 后配额仍为空", svc.stateOf("p").equals(QuotaManager.State.empty()),
                svc.stateOf("p").toString());
        check("preview 后没有票据", svc.ticketsOf("p").isEmpty(), "" + svc.ticketsOf("p").size());
        check("preview 说可以造", a.allowed() && a.reason().equals("ok"), a.reason());

        IncubationService.Preview altar = svc.preview(submit.withAltar(true));
        check("preview 对祭坛生效（8s → 4s）",
                a.spec().hatchSeconds() == 8 && altar.spec().hatchSeconds() == 4,
                a.spec().hatchSeconds() + " / " + altar.spec().hatchSeconds());
        check("preview 的 spec id 与祭坛无关（同一件产物）",
                a.spec().id().equals(altar.spec().id()), a.spec().id() + " vs " + altar.spec().id());
        check("preview 的 materialCost 按档位放大（4 铁 × 4 倍 = 16）",
                svc.preview(equipSubmitWithMaterials("p", 92L, Map.of("iron", 4))).spec()
                        .materialCost().getOrDefault("iron", -1) == 16, "");
    }

    private static void submitSecondCheck() {
        section("submit 二次校验（preview 之后配额被占）");
        IncubationService svc = new IncubationService();
        HatchSubmit target = equipSubmit("p", 101L);
        check("先 preview，判定可造", svc.preview(target).allowed(), "");

        svc.submit(1000, 1000, equipSubmit("p", 102L));
        svc.submit(1000, 1000, equipSubmit("p", 103L));
        check("另外两件装备把配额占满", svc.stateOf("p").equipment() == 2, svc.stateOf("p").toString());

        expectCode("再 submit 原产物 → QUOTA_REJECTED", HatchException.Code.QUOTA_REJECTED,
                () -> svc.submit(1000, 1000, target));
        check("拒绝后配额未变（仍 2 件 2 点）",
                svc.stateOf("p").equipment() == 2 && svc.stateOf("p").usedPoints() == 2,
                svc.stateOf("p").toString());
        check("拒绝后票据数未变（无半成品）", svc.ticketsOf("p").size() == 2,
                "" + svc.ticketsOf("p").size());
        check("拒绝后目标产物从未出现在票据里",
                svc.ticketsOf("p").stream().noneMatch(t -> t.spec().id().equals(
                        svc.preview(target).spec().id())), "");
    }

    // ================================================================ 生命周期

    private static void tickFlow() {
        section("tick 推进 CHARGING → READY");
        IncubationService svc = new IncubationService();
        HatchTicket common = svc.submit(1000, 1000, equipSubmit("p", 111L)); // 8s = 160 ticks
        HatchTicket rare = svc.submit(1000, 1000,
                new HatchSubmit("p", fake(BaseKind.EQUIPMENT, Rarity.RARE, 1.0, 112L),
                        BehaviorMode.NONE, MaterialTier.STANDARD, Map.of(), false)); // 30s = 600 ticks
        check("两张票都是 CHARGING",
                common.state() == HatchState.CHARGING && rare.state() == HatchState.CHARGING, "");
        check("8 秒票 totalSeconds=8", common.totalSeconds() == 8, "" + common.totalSeconds());
        check("30 秒票 totalSeconds=30", rare.totalSeconds() == 30, "" + rare.totalSeconds());

        check("159 tick 时还没有就绪的", svc.tick(1000 + 159).isEmpty(), "");
        List<HatchTicket> first = svc.tick(1000 + 160);
        check("160 tick 时只有 8 秒票就绪", first.size() == 1
                && first.get(0).ticketId().equals(common.ticketId()), "" + first.size());
        check("返回值里是 READY 状态", first.get(0).state() == HatchState.READY, first.get(0).state().name());
        check("票据本身已迁移为 READY",
                svc.ticket(common.ticketId()).orElseThrow().state() == HatchState.READY, "");
        check("重复 tick 不再返回同一张（只报新增）", svc.tick(1000 + 200).isEmpty(), "");
        check("tick 不影响配额", svc.stateOf("p").equipment() == 2, svc.stateOf("p").toString());

        List<HatchTicket> second = svc.tick(1000 + 600);
        check("600 tick 时 30 秒票就绪", second.size() == 1
                && second.get(0).ticketId().equals(rare.ticketId()), "" + second.size());
        check("两张票都 READY", svc.ticketsOf("p").stream()
                .allMatch(t -> t.state() == HatchState.READY), "");
    }

    private static void throwFlow() {
        section("投掷状态机");
        IncubationService svc = new IncubationService();
        HatchTicket t = svc.submit(1000, 1000, consumableSubmit("p", 121L));

        expectCode("没充满就投 → NOT_READY", HatchException.Code.NOT_READY,
                () -> svc.throwBall(t.ticketId(), 1000 + 10));
        check("被拒后仍是 CHARGING",
                svc.ticket(t.ticketId()).orElseThrow().state() == HatchState.CHARGING, "");

        svc.tick(1000 + 160);
        HatchTicket thrown = svc.throwBall(t.ticketId(), 1000 + 160);
        check("READY → THROWN", thrown.state() == HatchState.THROWN, thrown.state().name());
        check("反查票据也是 THROWN",
                svc.ticket(t.ticketId()).orElseThrow().state() == HatchState.THROWN, "");
        expectCode("再投一次 → NOT_READY", HatchException.Code.NOT_READY,
                () -> svc.throwBall(t.ticketId(), 1000 + 200));

        // tick 没跑到但时间已满：就地补迁移，避免"条满了投不出去"
        HatchTicket t2 = svc.submit(2000, 2000, consumableSubmit("p", 122L));
        HatchTicket healed = svc.throwBall(t2.ticketId(), 2000 + 160);
        check("时间已满但未 tick 也能投出（自愈）", healed.state() == HatchState.THROWN, healed.state().name());

        expectCode("未知 ticketId 投掷 → UNKNOWN_TICKET", HatchException.Code.UNKNOWN_TICKET,
                () -> svc.throwBall("tk-nope", 1000));
        check("ticket(未知) 返回空 Optional", svc.ticket("tk-nope").isEmpty(), "");
    }

    private static void deployFlow() {
        section("落地幂等");
        IncubationService svc = new IncubationService();
        HatchTicket t = svc.submit(1000, 1000, equipSubmit("p", 131L));

        expectCode("未投出就落地 → NOT_READY", HatchException.Code.NOT_READY,
                () -> svc.deploy(t.ticketId(), 1000));
        svc.tick(1000 + 160);
        expectCode("就绪但未投出 → NOT_READY", HatchException.Code.NOT_READY,
                () -> svc.deploy(t.ticketId(), 1000 + 160));

        svc.throwBall(t.ticketId(), 1000 + 160);
        check("THROWN → DEPLOYED 返回 true", svc.deploy(t.ticketId(), 1000 + 200), "");
        check("票据终态为 DEPLOYED",
                svc.ticket(t.ticketId()).orElseThrow().state() == HatchState.DEPLOYED, "");
        check("再次落地返回 false 且不抛异常", !svc.deploy(t.ticketId(), 1000 + 300), "");
        check("落地后配额不释放（装备仍在身上）", svc.stateOf("p").equipment() == 1,
                svc.stateOf("p").toString());

        expectCode("未知 ticketId 落地 → UNKNOWN_TICKET", HatchException.Code.UNKNOWN_TICKET,
                () -> svc.deploy("tk-nope", 1000));
    }

    // ================================================================ 离线封顶

    private static void offlineCap() {
        section("离线封顶 8 小时");
        IncubationService svc = new IncubationService();
        long now = 10_000_000L;
        long dayTicks = 20L * 3600 * 24;
        HatchTicket t = svc.submit(now, now - dayTicks, equipSubmit("p", 141L));
        check("离线 24 小时：startTick 被推到 8 小时前",
                t.startTick() == now - IncubationService.DEFAULT_OFFLINE_CAP_TICKS,
                "startTick=" + t.startTick() + " 期望 " + (now - IncubationService.DEFAULT_OFFLINE_CAP_TICKS));
        check("离线 24 小时：最多只算 8 小时（576000 tick）",
                now - t.startTick() == 576_000L, "" + (now - t.startTick()));
        check("封顶后仍然就绪（8h 远大于 8s 的球）", t.isReady(now), "");

        IncubationService online = new IncubationService();
        HatchTicket live = online.submit(now, now, equipSubmit("p", 142L));
        check("在线（lastActive=now）：startTick = now", live.startTick() == now, "" + live.startTick());

        // 用小封顶把"封顶真的生效"变成可观测的：传说 600s = 12000 tick，封顶 200 tick
        IncubationService small = new IncubationService(200, 5);
        HatchTicket legend = small.submit(now, now - dayTicks,
                new HatchSubmit("p", fake(BaseKind.EQUIPMENT, Rarity.LEGENDARY, 1.0, 143L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false));
        check("封顶 200 tick：startTick = now - 200", legend.startTick() == now - 200, "" + legend.startTick());
        check("封顶 200 tick：进度只有 200/12000",
                Math.abs(legend.progress(now) - 200.0 / 12_000.0) < 1e-9, "" + legend.progress(now));
        check("封顶后仍未就绪（离线 24 小时也没白送 600 秒）", !legend.isReady(now), "");

        IncubationService strict = new IncubationService(0, 5);
        HatchTicket fresh = strict.submit(now, now - dayTicks, equipSubmit("p", 144L));
        check("封顶 0：离线一分钟也不给进度", fresh.startTick() == now, "" + fresh.startTick());

        IncubationService future = new IncubationService();
        HatchTicket skew = future.submit(now, now + 5000, equipSubmit("p", 145L));
        check("lastActiveTick 跑到未来也不会卡死（夹到 now）", skew.startTick() == now, "" + skew.startTick());
    }

    // ================================================================ 祭坛

    private static void altarBoost() {
        section("祭坛加速 ×0.5 且不越上限");
        for (Rarity r : Rarity.values()) {
            IncubationService svc = new IncubationService();
            HatchSubmit plain = new HatchSubmit("a", fake(BaseKind.EQUIPMENT, r, 1.0, 200L + r.ordinal()),
                    BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false);
            HatchTicket base = svc.submit(1000, 1000, plain);
            HatchTicket altar = svc.submit(1000, 1000, plain.withAltar(true));

            int baseSeconds = HatchTime.baseSeconds(r);
            check(r.zh() + "：未上祭坛 = " + baseSeconds + "s", base.totalSeconds() == baseSeconds,
                    "" + base.totalSeconds());
            check(r.zh() + "：祭坛减半 = " + (baseSeconds / 2) + "s", altar.totalSeconds() == baseSeconds / 2,
                    "" + altar.totalSeconds());
            check(r.zh() + "：祭坛后不超上限 " + r.maxHatchSeconds() + "s",
                    altar.totalSeconds() <= r.maxHatchSeconds() && altar.totalSeconds() >= 1,
                    "" + altar.totalSeconds());
            check(r.zh() + "：票面 spec 时长与 totalSeconds 一致",
                    altar.spec().hatchSeconds() == altar.totalSeconds(), "");
        }

        // 最危险的一组：倍率把时长顶到上限，再减半
        IncubationService svc = new IncubationService();
        HatchTicket epicMax = svc.submit(1000, 1000,
                new HatchSubmit("a1", fake(BaseKind.EQUIPMENT, Rarity.EPIC, 3.0, 210L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false));
        check("史诗 ×3.0 被截到上限 180s", epicMax.totalSeconds() == 180, "" + epicMax.totalSeconds());
        HatchTicket epicAltar = svc.submit(1000, 1000,
                new HatchSubmit("a2", fake(BaseKind.EQUIPMENT, Rarity.EPIC, 3.0, 211L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), true));
        check("史诗 ×3.0 + 祭坛 = 90s（从截断后的值再减半）", epicAltar.totalSeconds() == 90,
                "" + epicAltar.totalSeconds());

        HatchTicket legendAltar = svc.submit(1000, 1000,
                new HatchSubmit("a3", fake(BaseKind.EQUIPMENT, Rarity.LEGENDARY, 2.0, 212L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), true));
        check("传说 ×2.0 + 祭坛 = 300s，未越 600s 上限", legendAltar.totalSeconds() == 300,
                "" + legendAltar.totalSeconds());
        check("altarSeconds 对 1 秒票不会击穿下限",
                IncubationService.altarSeconds(fakeSpecFromSeconds(1)) == 1, "");

        // 边界记录：传说档基础时长就等于上限，任何倍率都被截回 600 —— 倍率在最高档是空转的。
        // 这不是本服务的 bug（HatchTime 的截断策略如此），但平台层配"孵化耗时"展示时要知道。
        HatchTicket legend1 = svc.submit(1000, 1000,
                new HatchSubmit("a4", fake(BaseKind.EQUIPMENT, Rarity.LEGENDARY, 1.0, 213L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false));
        HatchTicket legend3 = svc.submit(1000, 1000,
                new HatchSubmit("a5", fake(BaseKind.EQUIPMENT, Rarity.LEGENDARY, 3.0, 214L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false));
        check("已知边界：传说档 ×1.0 与 ×3.0 都是 600s（倍率被上限吃掉）",
                legend1.totalSeconds() == 600 && legend3.totalSeconds() == 600,
                legend1.totalSeconds() + " / " + legend3.totalSeconds());
        check("史诗档 ×1.0 = 120s（对比上面的 ×3.0 = 180s）",
                svc.submit(1000, 1000, new HatchSubmit("a6",
                        fake(BaseKind.EQUIPMENT, Rarity.EPIC, 1.0, 215L),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), false)).totalSeconds() == 120,
                "");

        // 真 Composition 跑多组，确认没有一组越界
        int checked = 0;
        boolean allSafe = true;
        StringBuilder bad = new StringBuilder();
        for (long seed = 300; seed < 308; seed++) {
            for (Rarity forced : List.of(Rarity.COMMON, Rarity.EPIC, Rarity.LEGENDARY)) {
                IncubationService one = new IncubationService();
                HatchSubmit h = new HatchSubmit("b",
                        fake(BaseKind.EQUIPMENT, forced, 1.0 + (seed % 3) * 0.5, seed * 10 + forced.ordinal()),
                        BehaviorMode.NONE, MaterialTier.MASTERWORK, Map.of(), true);
                HatchTicket tk = one.submit(1000, 1000, h);
                checked++;
                if (tk.totalSeconds() > forced.maxHatchSeconds() || tk.totalSeconds() < 1) {
                    allSafe = false;
                    bad.append(tk.totalSeconds()).append(' ');
                }
            }
        }
        check("假 Composition × 祭坛：" + checked + " 组全部落在 1..上限 之间", allSafe, bad.toString());

        Composition real = build(BaseKind.EQUIPMENT, 401L);
        IncubationService realSvc = new IncubationService();
        HatchTicket realAltar = realSvc.submit(1000, 1000,
                new HatchSubmit("b", real, BehaviorMode.NONE, MaterialTier.REFINED, Map.of(), true));
        int plainSeconds = CompositionSettler.previewHatch(real).seconds();
        check("真 Composition 祭坛 = 结算时长的一半或下限",
                realAltar.totalSeconds() == Math.max(1, plainSeconds / 2),
                realAltar.totalSeconds() + " vs " + plainSeconds);
    }

    // ================================================================ 同时孵化上限

    private static void concurrentLimit() {
        section("同时孵化上限");
        IncubationService svc = new IncubationService(IncubationService.DEFAULT_OFFLINE_CAP_TICKS, 2);
        check("上限可配（2）", svc.maxConcurrent() == 2, "" + svc.maxConcurrent());

        svc.submit(1000, 1000, consumableSubmit("p", 501L));
        svc.submit(1000, 1000, consumableSubmit("p", 502L));
        int before = svc.ticketsOf("p").size();
        expectCode("第 3 个球 → BAR_FULL", HatchException.Code.BAR_FULL,
                () -> svc.submit(1000, 1000, consumableSubmit("p", 503L)));
        check("BAR_FULL 不产生票据", svc.ticketsOf("p").size() == before, "" + svc.ticketsOf("p").size());
        check("preview 也报孵化栏已满",
                !svc.preview(consumableSubmit("p", 503L)).allowed()
                        && svc.preview(consumableSubmit("p", 503L)).reason().contains("上限"),
                svc.preview(consumableSubmit("p", 503L)).reason());

        svc.tick(1000 + 160);
        HatchTicket firstTicket = svc.ticketsOf("p").get(0);
        svc.throwBall(firstTicket.ticketId(), 1000 + 160);
        check("投出后腾出名额", svc.preview(consumableSubmit("p", 503L)).allowed(), "");
        svc.submit(1000, 1000, consumableSubmit("p", 503L));
        check("投出后可再提交一个", svc.ticketsOf("p").size() == before + 1,
                "" + svc.ticketsOf("p").size());
        check("未投出的满球仍占名额（第 4 个被拒）",
                !svc.preview(consumableSubmit("p", 504L)).allowed(), "");
    }

    // ================================================================ 票据

    private static void ticketIdentity() {
        section("ticketId 唯一与反查");
        IncubationService svc = new IncubationService(IncubationService.DEFAULT_OFFLINE_CAP_TICKS, 50);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            ids.add(svc.submit(1000, 1000, consumableSubmit("p", 600L + i)).ticketId());
        }
        check("12 次提交得到 12 个不同 id", ids.size() == 12, "" + ids.size());
        boolean allFound = true;
        for (String id : ids) {
            Optional<HatchTicket> t = svc.ticket(id);
            if (t.isEmpty() || !t.get().ticketId().equals(id)) {
                allFound = false;
            }
        }
        check("每个 id 都能反查到票据", allFound, "");
        check("ticketsOf 只返回该玩家的票据",
                svc.ticketsOf("p").size() == 12 && svc.ticketsOf("other").isEmpty(),
                svc.ticketsOf("p").size() + " / " + svc.ticketsOf("other").size());
        check("票据 owner 正确", svc.ticketsOf("p").stream().allMatch(t -> t.ownerKey().equals("p")), "");
        expectIae("stateOf(空 owner) → 拒绝", () -> svc.stateOf(" "));
    }

    // ================================================================ 真链路

    private static void realPipeline() {
        section("真 Composition 全链路（构筑 → 定稿 → 孵化 → 投掷 → 落地）");
        IncubationService svc = new IncubationService();

        Composition equip = build(BaseKind.EQUIPMENT, 701L);
        Composition summon = build(BaseKind.SUMMON, 702L);
        Composition consumable = build(BaseKind.CONSUMABLE, 703L);
        check("三组真 Composition 都产出载体",
                equip.carrierId().isPresent() && summon.carrierId().isPresent()
                        && consumable.carrierId().isPresent(), "");

        HatchSubmit hEquip = new HatchSubmit("p", equip, BehaviorMode.NONE, MaterialTier.REFINED,
                Map.of("iron", 3, "crystal", 1), false);
        HatchSubmit hSummon = new HatchSubmit("p", summon, BehaviorMode.CHAOS, MaterialTier.REFINED,
                Map.of("bone", 2), false);
        HatchSubmit hConsumable = new HatchSubmit("p", consumable, BehaviorMode.NONE, MaterialTier.TRIAL,
                Map.of("herb", 5), false);

        HatchTicket t1 = svc.submit(1000, 1000, hEquip);
        HatchTicket t2 = svc.submit(1000, 1000, hSummon);
        HatchTicket t3 = svc.submit(1000, 1000, hConsumable);
        check("装备 → EQUIPMENT 档、1 点",
                svc.preview(hEquip).category() == QuotaManager.Category.EQUIPMENT
                        && svc.preview(hEquip).cost() == 1, "");
        check("混乱召唤 → CHAOS 档、1 点",
                svc.preview(hSummon).category() == QuotaManager.Category.CHAOS
                        && svc.preview(hSummon).cost() == 1, "");
        check("消耗品 → FREE 档、0 点",
                svc.preview(hConsumable).category() == QuotaManager.Category.FREE
                        && svc.preview(hConsumable).cost() == 0, "");
        check("真链路配额合计 2 点 1 装备 1 混乱",
                svc.stateOf("p").equipment() == 1 && svc.stateOf("p").chaos() == 1
                        && svc.stateOf("p").usedPoints() == 2, svc.stateOf("p").toString());
        check("材料按档位放大：铁 3 ×4 = 12",
                t1.spec().materialCost().getOrDefault("iron", -1) == 12, "" + t1.spec().materialCost());
        check("材料按档位放大：草药 5 ×1 = 5",
                t3.spec().materialCost().getOrDefault("herb", -1) == 5, "" + t3.spec().materialCost());
        check("真链路 spec 带标签与属性",
                !t1.spec().tags().isEmpty() && t1.spec().type() == ProductType.EQUIPMENT, "" + t1.spec().tags());
        check("三张票 id 互不相同",
                !t1.ticketId().equals(t2.ticketId()) && !t2.ticketId().equals(t3.ticketId()), "");

        long total = HatchSession.totalTicks(t1.totalSeconds());
        check("进度从 0 开始", t1.progress(1000) == 0.0, "" + t1.progress(1000));
        check("中途进度在 (0,1)", t1.progress(1000 + total / 2) > 0.0
                && t1.progress(1000 + total / 2) < 1.0, "" + t1.progress(1000 + total / 2));

        List<HatchTicket> ready = svc.tick(1000 + total);
        check("装备球就绪（其余按需）", ready.stream().anyMatch(t -> t.ticketId().equals(t1.ticketId())),
                "" + ready.size());
        svc.throwBall(t1.ticketId(), 1000 + total);
        check("装备球落地成功", svc.deploy(t1.ticketId(), 1000 + total), "");
        check("落地后全链路终态正确",
                svc.ticket(t1.ticketId()).orElseThrow().state() == HatchState.DEPLOYED, "");

        // 同一 Composition 重复定稿：配额必须继续被扣（不是去重），id 相同
        IncubationService dup = new IncubationService();
        HatchTicket d1 = dup.submit(1000, 1000, hEquip);
        HatchTicket d2 = dup.submit(1000, 1000, hEquip);
        check("同一 Composition 重复定稿 → spec id 相同（落地幂等据此）",
                d1.specId().equals(d2.specId()), d1.specId() + " vs " + d2.specId());
        check("同一 Composition 重复定稿 → 票 id 不同",
                !d1.ticketId().equals(d2.ticketId()), "");
        check("同一 Composition 重复定稿 → 配额扣两次",
                dup.stateOf("p").equipment() == 2, dup.stateOf("p").toString());
    }

    // ================================================================ 并发

    private static void threadSafety() throws InterruptedException {
        section("并发提交不超卖");
        IncubationService svc = new IncubationService();
        List<HatchSubmit> batch = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            batch.add(equipSubmit("p", 800L + i));
        }
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            HatchSubmit h = batch.get(i);
            pool.execute(() -> {
                try {
                    start.await();
                    svc.submit(1000, 1000, h);
                    ok.incrementAndGet();
                } catch (HatchException ex) {
                    if (ex.code() == HatchException.Code.QUOTA_REJECTED) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        check("8 线程在 1 秒内跑完", done.await(5, TimeUnit.SECONDS), "");
        pool.shutdownNow();

        check("装备上限 2：恰好 2 次成功", ok.get() == 2, "成功 " + ok.get());
        check("其余 6 次全部 QUOTA_REJECTED", rejected.get() == 6, "拒绝 " + rejected.get());
        check("最终配额正好 2 件（没有超卖）", svc.stateOf("p").equipment() == 2,
                svc.stateOf("p").toString());
        check("最终票据数 = 成功次数", svc.ticketsOf("p").size() == 2, "" + svc.ticketsOf("p").size());
    }

    // ================================================================ 工厂

    private static HatchSubmit equipSubmit(String owner, long seed) {
        return HatchSubmit.of(owner, fake(BaseKind.EQUIPMENT, Rarity.COMMON, 1.0, seed),
                BehaviorMode.NONE, MaterialTier.STANDARD);
    }

    private static HatchSubmit equipSubmitWithMaterials(String owner, long seed, Map<String, Integer> mats) {
        return new HatchSubmit(owner, fake(BaseKind.EQUIPMENT, Rarity.COMMON, 1.0, seed),
                BehaviorMode.NONE, MaterialTier.REFINED, mats, false);
    }

    private static HatchSubmit summonSubmit(String owner, long seed, BehaviorMode behavior) {
        return HatchSubmit.of(owner, fake(BaseKind.SUMMON, Rarity.COMMON, 1.0, seed),
                behavior, MaterialTier.STANDARD);
    }

    private static HatchSubmit consumableSubmit(String owner, long seed) {
        return HatchSubmit.of(owner, fake(BaseKind.CONSUMABLE, Rarity.COMMON, 1.0, seed),
                BehaviorMode.NONE, MaterialTier.TRIAL);
    }

    /** 只含一张载体卡的最小 Composition —— 稀有度与倍率直接指定，便于卡边界。 */
    private static Composition fake(BaseKind kind, Rarity rarity, double multiplier, long seed) {
        DraftCard carrier = new DraftCard(carrierAffix(), whiteTier("载体"));
        return new Composition(kind, List.of(carrier), seed, rarity, 1, 1.0, multiplier);
    }

    private static Spec fakeSpec(BaseKind kind, Rarity rarity, double multiplier, long seed) {
        Composition c = fake(kind, rarity, multiplier, seed);
        return CompositionSettler.settle(c, SettleRequest.of("p", MaterialTier.MASTERWORK));
    }

    /** 直接构造一个 1 秒的 Spec，用来测祭坛下限（真数据造不出 1 秒）。 */
    private static Spec fakeSpecFromSeconds(int seconds) {
        return CompositionSettler
                .settle(fake(BaseKind.EQUIPMENT, Rarity.COMMON, 1.0, 901L),
                        SettleRequest.of("p", MaterialTier.TRIAL))
                .withHatchSeconds(seconds);
    }

    private static Affix carrierAffix() {
        return new Affix("carrier.gun", "carrier.gun", Layer.CARRIER, "枪", "ANY",
                Set.of(), Set.of(), Set.of(), Set.of(), 10, "gun",
                Map.of(Tier.WHITE, whiteTier("载体")));
    }

    private static TierData whiteTier(String desc) {
        return new TierData(Tier.WHITE, 1, 1.0, 4.0, null, desc);
    }

    /** 真实四层确认制跑一遍，取定稿结果。 */
    private static Composition build(BaseKind kind, long seed) {
        BuildSession s = new BuildSession(table, config, seed);
        s.chooseType(kind);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合：" + kind.zh()));
    }

    // ================================================================ 断言

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + label);
        } else {
            failed++;
            System.out.println("  ✗ " + label + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    private static void expectIae(String label, Runnable action) {
        try {
            action.run();
            check(label, false, "没有抛出异常");
        } catch (IllegalArgumentException e) {
            check(label, true, "");
        } catch (RuntimeException e) {
            check(label, false, "抛了别的异常：" + e);
        }
    }

    private static void expectCode(String label, HatchException.Code expected, Runnable action) {
        try {
            action.run();
            check(label, false, "没有抛出异常");
        } catch (HatchException e) {
            check(label, e.code() == expected, "实际 code=" + e.code() + " / " + e.getMessage());
        } catch (RuntimeException e) {
            check(label, false, "抛了别的异常：" + e);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("【" + title + "】");
    }

    private static String read(String file) throws IOException {
        return Files.readString(dataDir.resolve(file), StandardCharsets.UTF_8);
    }
}
