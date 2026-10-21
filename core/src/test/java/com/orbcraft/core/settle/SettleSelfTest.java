package com.orbcraft.core.settle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.DraftResult;
import com.orbcraft.core.draft.DraftSession;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.PickedSlot;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.draft.TierData;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;

/**
 * 结算器自测 —— 单元用例用最小假数据，集成用例跑真实 450 条卡表。
 *
 * <p>分工的原因：时长、倍率、行为校验这些是纯逻辑，用假数据能把边界卡死；
 * 但「450 条词条是否都能结算出合法 Spec」只有真数据能证明 ——
 * 比如某个载体的属性词条 id 段数不对，假数据永远发现不了。
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.settle.SettleSelfTest [dataDir]}
 */
public final class SettleSelfTest {

    private static int passed;
    private static int failed;
    private static Path dataDir;
    private static AffixTable table;

    public static void main(String[] args) throws Exception {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        loadData();
        hatchTable();
        hatchClamp();
        materialTiers();
        behaviorRules();
        attrSettlement();
        idempotency();
        realPipeline();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("结算自测失败 " + failed + " 项");
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
        check("总词条 450 条", table.size() == 450, "实际 " + table.size());
    }

    // ================================================================ 时长

    private static void hatchTable() {
        section("基础时长档位（文档 §20.1）");
        check("普通 8 秒", HatchTime.baseSeconds(Rarity.COMMON) == 8, "");
        check("稀有 30 秒", HatchTime.baseSeconds(Rarity.RARE) == 30, "");
        check("史诗 120 秒", HatchTime.baseSeconds(Rarity.EPIC) == 120, "");
        check("传说 600 秒", HatchTime.baseSeconds(Rarity.LEGENDARY) == 600, "");

        // 基础档位必须都不超过各自稀有度的上限，否则无倍率时就会越界
        for (Rarity r : Rarity.values()) {
            check(r.zh() + "基础时长 ≤ 上限",
                    HatchTime.baseSeconds(r) <= r.maxHatchSeconds(),
                    HatchTime.baseSeconds(r) + " vs " + r.maxHatchSeconds());
        }

        HatchTime.Plan p = HatchTime.plan(Rarity.EPIC, 1.0);
        check("无红卡时等于基础时长", p.seconds() == 120, "实际 " + p.seconds());
        check("无红卡不触发截断", !p.clamped(), "");
        check("单张红卡 +50%", HatchTime.plan(Rarity.EPIC, 1.5).seconds() == 180, "");
    }

    private static void hatchClamp() {
        section("倍率溢出与截断");

        // 史诗 120 × 2.0 = 240，超过上限 180 —— 这是「两张红卡就崩」的那个场景
        HatchTime.Plan two = HatchTime.plan(Rarity.EPIC, 2.0);
        check("史诗 ×2.0 原始 240 秒", two.raw() == 240, "实际 " + two.raw());
        check("史诗 ×2.0 截断到 180", two.seconds() == 180, "实际 " + two.seconds());
        check("史诗 ×2.0 标记为截断", two.clamped(), "");
        check("溢出比例约 33%", Math.abs(two.overflowPct() - (60.0 / 180)) < 1e-9,
                "实际 " + two.overflowPct());

        // 传说 600 已经是上限，任何倍率都不该让它越界
        HatchTime.Plan legend = HatchTime.plan(Rarity.LEGENDARY, 3.0);
        check("传说 ×3.0 原始 1800", legend.raw() == 1800, "实际 " + legend.raw());
        check("传说 ×3.0 仍为 600", legend.seconds() == 600, "实际 " + legend.seconds());

        // 截断后的值必须能通过 Spec 的构造校验，这是整个类存在的目的
        Spec spec = Spec.simple("t", "t", com.orbcraft.core.spec.ProductType.EQUIPMENT,
                Rarity.LEGENDARY, BehaviorMode.NONE, legend.seconds(), Map.of(), List.of(), "");
        check("截断结果可构造 Spec", spec.hatchSeconds() == 600, "");

        // 普通 8 × 3.0 = 24，上限 30，不该被截断
        HatchTime.Plan small = HatchTime.plan(Rarity.COMMON, 3.0);
        check("普通 ×3.0 = 24 秒且不截断", small.seconds() == 24 && !small.clamped(), "");

        // 异常倍率不能产生非法秒数
        check("NaN 倍率回退为 1.0", HatchTime.plan(Rarity.RARE, Double.NaN).seconds() == 30, "");
        check("负数倍率不低于 1 秒", HatchTime.plan(Rarity.RARE, -5).seconds() >= 1, "");
    }

    // ================================================================ 材料档

    private static void materialTiers() {
        section("材料投入档");
        check("四档预算 6/12/20/28",
                MaterialTier.TRIAL.budget() == 6 && MaterialTier.STANDARD.budget() == 12
                        && MaterialTier.REFINED.budget() == 20 && MaterialTier.MASTERWORK.budget() == 28, "");
        check("倍率 ×1/×2/×4/×8",
                MaterialTier.TRIAL.materialMultiplier() == 1 && MaterialTier.STANDARD.materialMultiplier() == 2
                        && MaterialTier.REFINED.materialMultiplier() == 4
                        && MaterialTier.MASTERWORK.materialMultiplier() == 8, "");

        for (MaterialTier t : MaterialTier.values()) {
            check(t.zh() + " configFor 预算一致", t.configFor().budget() == t.budget(),
                    "实际 " + t.configFor().budget());
            check(t.zh() + " 可由预算反查", MaterialTier.fromBudget(t.budget()) == t, "");
        }

        Map<String, Integer> base = Map.of("iron", 2, "crystal", 1);
        check("试作材料不放大", MaterialTier.TRIAL.costOf(base).equals(base), "");
        check("大师材料 ×8",
                MaterialTier.MASTERWORK.costOf(base).equals(Map.of("iron", 16, "crystal", 8)), "");
        check("空配方返回空表", MaterialTier.REFINED.costOf(null).isEmpty(), "");

        // 低预算档必须真能发出可买的牌，否则抽卡在最便宜的档位就卡死
        for (MaterialTier t : MaterialTier.values()) {
            DraftSession s = new DraftSession(table, t.configFor(), 777L);
            s.pickBase(BaseKind.EQUIPMENT);
            s.deal();
            check(t.zh() + "预算 " + t.budget() + " 仍有可买的牌",
                    s.snapshot().affordableCount() >= 1,
                    "可买 " + s.snapshot().affordableCount());
        }
    }

    // ================================================================ 行为模式

    private static void behaviorRules() {
        section("行为模式校验");
        DraftResult equip = sampleEquip();

        expectThrows("装备不能指定攻击行为",
                () -> SpecSettler.settle(equip,
                        new SettleRequest("p1", BehaviorMode.ATTACK, MaterialTier.TRIAL, Map.of())));

        DraftResult summon = sampleSummon();
        expectThrows("召唤物必须指定行为",
                () -> SpecSettler.settle(summon,
                        new SettleRequest("p1", BehaviorMode.NONE, MaterialTier.TRIAL, Map.of())));

        Spec atk = SpecSettler.settle(summon, SettleRequest.summon("p1", BehaviorMode.ATTACK, MaterialTier.TRIAL));
        check("召唤物攻击型通过", atk.behavior() == BehaviorMode.ATTACK, "实际 " + atk.behavior());

        Spec eq = SpecSettler.settle(equip, SettleRequest.of("p1", MaterialTier.TRIAL));
        check("装备行为被强制为 NONE", eq.behavior() == BehaviorMode.NONE, "");
        check("装备不是召唤物", !eq.isSummon(), "");
    }

    // ================================================================ 数值结算

    private static void attrSettlement() {
        section("属性与标签结算");
        Spec spec = SpecSettler.settle(sampleEquip(), SettleRequest.of("p1", MaterialTier.TRIAL));

        check("伤害被结算进 attrs", spec.attrs().getOrDefault("damage", 0.0) == 8.0,
                "实际 " + spec.attrs().get("damage"));
        check("效果带 effect. 前缀", spec.attrs().containsKey("effect.ignite"),
                "实际 " + spec.attrs().keySet());
        check("标签含载体 gun", spec.tags().contains("gun"), "实际 " + spec.tags());
        check("标签含元素 fire", spec.tags().contains("fire"), "");
        check("标签含属性 damage", spec.tags().contains("damage"), "");
        check("hatchSeconds 合法",
                spec.hatchSeconds() >= 1 && spec.hatchSeconds() <= spec.rarity().maxHatchSeconds(),
                "实际 " + spec.hatchSeconds());
        check("flavor 非空", !spec.flavor().isBlank(), "");
        check("slotId 交由落地器挑选", spec.slotId() == 0, "");
    }

    private static void idempotency() {
        section("id 幂等与唯一性");
        DraftResult r = sampleEquip();
        String a = SpecSettler.settle(r, SettleRequest.of("alice", MaterialTier.TRIAL)).id();
        String b = SpecSettler.settle(r, SettleRequest.of("alice", MaterialTier.TRIAL)).id();
        String c = SpecSettler.settle(r, SettleRequest.of("bob", MaterialTier.TRIAL)).id();

        check("同一玩家重复结算 id 相同", a.equals(b), a + " vs " + b);
        check("不同玩家 id 不同", !a.equals(c), a + " vs " + c);
        check("id 非空", !a.isBlank(), "");
        check("id 带前缀", a.startsWith("spec-"), a);

        String d = SpecSettler.settle(sampleEquipVariant(), SettleRequest.of("alice", MaterialTier.TRIAL)).id();
        check("不同配方 id 不同", !a.equals(d), a + " vs " + d);

        expectThrows("缺少 ownerKey 直接拒绝", () -> new SettleRequest("  ", BehaviorMode.NONE,
                MaterialTier.TRIAL, Map.of()));
    }

    // ================================================================ 真实数据集成

    private static void realPipeline() {
        section("真实卡表全链路（四档预算 × 多局）");
        int runsPerTier = 60;
        int total = 0;
        int settled = 0;
        List<String> failures = new ArrayList<>();

        for (MaterialTier tier : MaterialTier.values()) {
            int ok = 0;
            for (int i = 0; i < runsPerTier; i++) {
                long seed = 900_000L + (long) tier.ordinal() * 10_000 + i;
                BaseKind kind = BaseKind.values()[i % BaseKind.values().length];
                total++;
                try {
                    DraftResult r = playOne(tier, seed, kind);
                    BehaviorMode behavior = kind == BaseKind.SUMMON ? BehaviorMode.ATTACK : BehaviorMode.NONE;
                    Spec spec = SpecSettler.settle(r,
                            new SettleRequest("p" + i, behavior, tier, Map.of("iron", 2, "crystal", 1)));
                    if (spec.hatchSeconds() < 1
                            || spec.hatchSeconds() > spec.rarity().maxHatchSeconds()) {
                        failures.add(seed + " 时长越界 " + spec.hatchSeconds());
                        continue;
                    }
                    ok++;
                    settled++;
                } catch (RuntimeException e) {
                    failures.add(seed + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            check(tier.zh() + "档 " + runsPerTier + " 局全部结算成功", ok == runsPerTier,
                    "成功 " + ok + " 局");
        }

        check("累计结算 " + total + " 件无异常", failures.isEmpty(),
                failures.isEmpty() ? "" : failures.subList(0, Math.min(3, failures.size())).toString());
        check("成功件数与总局数一致", settled == total, settled + " / " + total);
    }

    // ================================================================ 驱动

    /** 走完整四层，每层挑**最便宜**的牌 —— 模拟知道自己在省点数的玩家。 */
    private static DraftResult playOne(MaterialTier tier, long seed, BaseKind kind) {
        DraftSession s = new DraftSession(table, tier.configFor(), seed);
        s.pickBase(kind);
        for (int i = 0; i < Layer.values().length; i++) {
            List<DraftCard> cards = s.deal();
            int idx = cheapestAffordable(cards, s.remainingPoints());
            if (idx < 0) {
                throw new IllegalStateException("种子 " + seed + " 第 " + (i + 1) + " 层无牌可买"
                        + "（剩余 " + s.remainingPoints() + " 点，最低牌 "
                        + cards.stream().mapToInt(DraftCard::points).min().orElse(-1) + " 点）");
            }
            s.pick(idx);
        }
        return s.finish("试作·" + seed);
    }

    private static int cheapestAffordable(List<DraftCard> cards, int remaining) {
        int idx = -1;
        int best = Integer.MAX_VALUE;
        for (int j = 0; j < cards.size(); j++) {
            DraftCard c = cards.get(j);
            if (c.affordableWith(remaining) && c.points() < best) {
                best = c.points();
                idx = j;
            }
        }
        return idx;
    }

    // ================================================================ 样例构造

    private static DraftResult sampleEquip() {
        return new DraftResult("测试枪", BaseKind.EQUIPMENT, Rarity.EPIC, 17,
                List.of(
                        slot("carrier.gun", Layer.CARRIER, Tier.GOLD, 4, 0, null),
                        slot("elem.fire", Layer.ELEMENT, Tier.BLUE, 2, 0, null),
                        slot("attr.damage.gun", Layer.ATTRIBUTE, Tier.RED, 7, 8, hatchDrawback()),
                        slot("effect.ignite.fire.gun", Layer.EFFECT, Tier.GOLD, 4, 3, null)),
                20261021L, 0, 0);
    }

    private static DraftResult sampleEquipVariant() {
        return new DraftResult("测试枪二", BaseKind.EQUIPMENT, Rarity.EPIC, 17,
                List.of(
                        slot("carrier.sword", Layer.CARRIER, Tier.GOLD, 4, 0, null),
                        slot("elem.fire", Layer.ELEMENT, Tier.BLUE, 2, 0, null),
                        slot("attr.damage.gun", Layer.ATTRIBUTE, Tier.RED, 7, 8, hatchDrawback()),
                        slot("effect.ignite.fire.gun", Layer.EFFECT, Tier.GOLD, 4, 3, null)),
                20261021L, 0, 0);
    }

    private static DraftResult sampleSummon() {
        return new DraftResult("测试龙", BaseKind.SUMMON, Rarity.LEGENDARY, 20,
                List.of(
                        slot("carrier.dragon", Layer.CARRIER, Tier.GOLD, 4, 0, null),
                        slot("elem.fire", Layer.ELEMENT, Tier.BLUE, 2, 0, null),
                        slot("attr.damage.gun", Layer.ATTRIBUTE, Tier.RED, 7, 8, hatchDrawback())),
                20261022L, 0, 0);
    }

    private static PickedSlot slot(String id, Layer layer, Tier tier, int points,
                                   double value, TierData.Drawback drawback) {
        Affix affix = new Affix(id, id, layer, name(id), "ANY",
                Set.of(), Set.of(), Set.of(), Set.of(), 100, "",
                Map.of(tier, new TierData(tier, points, 1.0, value, drawback, "")));
        TierData data = affix.tierData(tier);
        return new PickedSlot(layer, affix, data);
    }

    private static TierData.Drawback hatchDrawback() {
        return new TierData.Drawback("HATCH_TIME_PCT", 0.5);
    }

    private static String name(String id) {
        return id.substring(id.lastIndexOf('.') + 1);
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

    private static void expectThrows(String label, Runnable action) {
        try {
            action.run();
            check(label, false, "没有抛出异常");
        } catch (RuntimeException e) {
            check(label, true, "");
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
