package com.orbcraft.core.target;

import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.TargetCard;

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

/**
 * 定向卡分层配额自测 —— 用真实 44 张定向卡跑，不用假数据。
 *
 * <p>重点盯四件在重构里最容易悄悄丢掉的事：
 * <ol>
 *   <li><b>分类是真分类。</b> 装备 21 / 消耗品 19 / 召唤物 22，元素层 9 张三类共享。</li>
 *   <li><b>三类互不挤占。</b> 装备用完后召唤物照样能用 —— 这正是本次改动的目的，
 *       退回笼统计数时这一项会立刻失败。</li>
 *   <li><b>账本不可变。</b> {@code use} 之后原实例的数字必须原地不动，否则「扣了没保存」
 *       与「扣了两次」在 UI 上看起来一样。</li>
 *   <li><b>跨天与淘汰。</b> 换一天自动满额，7 天外的记录不再参与计算也不占内存。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.target.TargetSelfTest [dataDir]}
 */
public final class TargetSelfTest {

    private static int passed;
    private static int failed;
    private static Path dataDir;
    private static TargetDeck deck;

    public static void main(String[] args) throws Exception {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        loadData();
        deckShape();
        poolsByKind();
        deckLookup();
        deckValidation();
        allowanceBasics();
        allowanceIsolation();
        allowanceDaysAndOwners();
        allowanceRetention();
        allowanceValidation();
        planValidation();
        planDescribes();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("定向卡自测失败 " + failed + " 项");
        }
    }

    // ================================================================ 数据

    private static void loadData() throws IOException {
        section("定向卡数据");
        List<TargetCard> raw = AffixData.readTargetCards(read("target_cards.json"));
        deck = TargetDeck.of(raw);

        check("真实卡表共 44 张", raw.size() == 44, "实际 " + raw.size());
        check("CARRIER 层 35 张", deck.sizeByLayer().get(Layer.CARRIER) == 35,
                "实际 " + deck.sizeByLayer().get(Layer.CARRIER));
        check("ELEMENT 层 9 张", deck.sizeByLayer().get(Layer.ELEMENT) == 9,
                "实际 " + deck.sizeByLayer().get(Layer.ELEMENT));
        check("没有卡落在 ATTRIBUTE / EFFECT 层",
                deck.sizeByLayer().get(Layer.ATTRIBUTE) == 0 && deck.sizeByLayer().get(Layer.EFFECT) == 0,
                deck.sizeByLayer().toString());

        int violations = 0;
        for (TargetCard card : raw) {
            if (card.guaranteesQuality()) {
                violations++;
            }
        }
        check("44 张全部不保强度（guaranteesQuality 全 false）", violations == 0,
                "违规 " + violations + " 张");
    }

    private static void deckShape() {
        section("卡池结构");
        check("size() 等于卡表张数", deck.size() == 44, "实际 " + deck.size());
        check("cards() 返回 44 张", deck.cards().size() == 44, "实际 " + deck.cards().size());
        check("sizeByLayer 覆盖四个层（空层给 0）", deck.sizeByLayer().size() == Layer.values().length,
                deck.sizeByLayer().toString());
        check("cards() 不可变", immutableList(deck.cards()), "");
        check("sizeByLayer() 不可变", immutableMap(deck.sizeByLayer()), "");
    }

    private static void poolsByKind() {
        section("按类型切分卡池");
        List<TargetCard> equipment = deck.forKind(BaseKind.EQUIPMENT);
        List<TargetCard> consumable = deck.forKind(BaseKind.CONSUMABLE);
        List<TargetCard> summon = deck.forKind(BaseKind.SUMMON);

        check("装备可用 21 张（12 + 9）", equipment.size() == 21, "实际 " + equipment.size());
        check("消耗品可用 19 张（10 + 9）", consumable.size() == 19, "实际 " + consumable.size());
        check("召唤物可用 22 张（13 + 9）", summon.size() == 22, "实际 " + summon.size());

        check("装备池前 12 张是 CARRIER 层", allLayer(equipment.subList(0, 12), Layer.CARRIER), "");
        check("装备池后 9 张是 ELEMENT 层", allLayer(equipment.subList(12, 21), Layer.ELEMENT), "");
        check("消耗品池前 10 张是 CARRIER 层", allLayer(consumable.subList(0, 10), Layer.CARRIER), "");
        check("召唤物池前 13 张是 CARRIER 层", allLayer(summon.subList(0, 13), Layer.CARRIER), "");

        Set<String> elemInEquipment = ids(equipment);
        elemInEquipment.retainAll(ids(summon));
        elemInEquipment.retainAll(ids(consumable));
        check("9 张元素卡同时出现在三个池里", elemInEquipment.size() == 9,
                "实际 " + elemInEquipment.size());

        Set<String> union = new HashSet<>(ids(equipment));
        union.addAll(ids(consumable));
        union.addAll(ids(summon));
        check("三池并集去重后覆盖全部 44 张", union.size() == 44, "实际 " + union.size());

        check("CARRIER 池按 base_type 配对（无跨类型混入）",
                carrierPoolMatches(equipment, BaseKind.EQUIPMENT)
                        && carrierPoolMatches(consumable, BaseKind.CONSUMABLE)
                        && carrierPoolMatches(summon, BaseKind.SUMMON), "");

        check("同一类型两次取池顺序一致",
                ids(deck.forKind(BaseKind.SUMMON)).equals(ids(deck.forKind(BaseKind.SUMMON))), "");
        check("卡池顺序即卡表顺序（装备池首张为定向·枪）",
                "target.carrier.gun".equals(equipment.get(0).id()), equipment.get(0).id());
        check("元素卡排在 CARRIER 之后（召唤池第 14 张起为元素层）",
                summon.get(13).targetLayer() == Layer.ELEMENT, summon.get(13).targetLayer().name());
        check("forKind 返回只读列表", immutableList(equipment), "");
    }

    private static void deckLookup() {
        section("按 id 查找");
        Optional<TargetCard> gun = deck.find("target.carrier.gun");
        check("命中真实卡 id", gun.isPresent(), "");
        check("取到的是同一张卡", gun.map(c -> "定向·枪".equals(c.name())).orElse(false),
                gun.map(TargetCard::name).orElse("<空>"));
        check("未知 id 返回空 Optional", deck.find("target.nope").isEmpty(), "");
        check("null id 返回空 Optional（不抛异常）", deck.find(null).isEmpty(), "");
        check("offers：装备卡进装备池", deck.offers(BaseKind.EQUIPMENT, gun.orElseThrow()), "");
        check("offers：装备卡不进召唤池", !deck.offers(BaseKind.SUMMON, gun.orElseThrow()), "");
        check("offers：元素卡三类通用",
                deck.offers(BaseKind.EQUIPMENT, deck.find("target.elem.fire").orElseThrow())
                        && deck.offers(BaseKind.CONSUMABLE, deck.find("target.elem.fire").orElseThrow())
                        && deck.offers(BaseKind.SUMMON, deck.find("target.elem.fire").orElseThrow()), "");
    }

    private static void deckValidation() {
        section("卡池构造校验");
        List<TargetCard> duplicated = new ArrayList<>(deck.cards());
        duplicated.add(deck.cards().get(0));
        check("id 重复 → CARD_DUPLICATED",
                throwsTarget(TargetException.Code.CARD_DUPLICATED, () -> TargetDeck.of(duplicated)), "");
        check("null 卡表 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> TargetDeck.of(null)), "");
        check("null 元素 → BAD_ARGUMENT", throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> {
            List<TargetCard> withNull = new ArrayList<>(deck.cards());
            withNull.add(null);
            TargetDeck.of(withNull);
        }), "");
        check("forKind(null) → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> deck.forKind(null)), "");
    }

    // ================================================================ 配额

    private static void allowanceBasics() {
        section("每日配额：基本行为");
        TargetAllowance day0 = TargetAllowance.daily(1);
        check("默认每类型 1 次/天", day0.perKindPerDay() == 1, "" + day0.perKindPerDay());
        check("初始剩余 1 次", day0.remaining("p1", 100, BaseKind.EQUIPMENT) == 1,
                "" + day0.remaining("p1", 100, BaseKind.EQUIPMENT));
        check("初始合计已用 0", day0.totalUsed("p1", 100) == 0, "" + day0.totalUsed("p1", 100));

        Optional<TargetAllowance> once = day0.use("p1", 100, BaseKind.EQUIPMENT);
        check("第一次 use 成功", once.isPresent(), "");
        TargetAllowance day1 = once.orElseThrow();
        check("use 后新实例已用 1", day1.used("p1", 100, BaseKind.EQUIPMENT) == 1, "");
        check("use 后新实例剩余 0", day1.remaining("p1", 100, BaseKind.EQUIPMENT) == 0, "");
        check("use 后合计已用 1", day1.totalUsed("p1", 100) == 1, "" + day1.totalUsed("p1", 100));

        check("原实例不受影响（不可变）",
                day0.used("p1", 100, BaseKind.EQUIPMENT) == 0
                        && day0.remaining("p1", 100, BaseKind.EQUIPMENT) == 1
                        && day0.totalUsed("p1", 100) == 0, "");

        check("同天同类型第二次 use 返回 empty",
                day1.use("p1", 100, BaseKind.EQUIPMENT).isEmpty(), "");
        boolean noThrow = true;
        try {
            day1.use("p1", 100, BaseKind.EQUIPMENT);
        } catch (RuntimeException e) {
            noThrow = false;
        }
        check("额度不足不抛异常", noThrow, "");
        check("剩余次数不会为负", day1.remaining("p1", 100, BaseKind.EQUIPMENT) == 0,
                "" + day1.remaining("p1", 100, BaseKind.EQUIPMENT));

        TargetAllowance twoPerDay = TargetAllowance.daily(2);
        TargetAllowance t1 = twoPerDay.use("p1", 100, BaseKind.SUMMON).orElseThrow();
        TargetAllowance t2 = t1.use("p1", 100, BaseKind.SUMMON).orElseThrow();
        check("perKindPerDay=2 时第二次仍可用", t2.used("p1", 100, BaseKind.SUMMON) == 2, "");
        check("perKindPerDay=2 时第三次被拒", t2.use("p1", 100, BaseKind.SUMMON).isEmpty(), "");
        check("perKindPerDay=2 边界：剩余为 0",
                t2.remaining("p1", 100, BaseKind.SUMMON) == 0, "");
    }

    private static void allowanceIsolation() {
        section("三类互不挤占");
        TargetAllowance start = TargetAllowance.daily(1);

        TargetAllowance afterEquipment = start.use("p1", 100, BaseKind.EQUIPMENT).orElseThrow();
        check("装备用完后召唤物仍可 use",
                afterEquipment.use("p1", 100, BaseKind.SUMMON).isPresent(), "");
        TargetAllowance afterSummon = afterEquipment.use("p1", 100, BaseKind.SUMMON).orElseThrow();
        check("装备 + 召唤各用一次后消耗品仍可 use",
                afterSummon.use("p1", 100, BaseKind.CONSUMABLE).isPresent(), "");

        TargetAllowance full = afterSummon.use("p1", 100, BaseKind.CONSUMABLE).orElseThrow();
        check("三类用完合计 3 次", full.totalUsed("p1", 100) == 3, "" + full.totalUsed("p1", 100));
        check("三类用完后再 use 任一类都被拒",
                full.use("p1", 100, BaseKind.EQUIPMENT).isEmpty()
                        && full.use("p1", 100, BaseKind.SUMMON).isEmpty()
                        && full.use("p1", 100, BaseKind.CONSUMABLE).isEmpty(), "");
        check("总量 3 次/天：三类各 1", full.remaining("p1", 100, BaseKind.EQUIPMENT) == 0
                && full.remaining("p1", 100, BaseKind.SUMMON) == 0
                && full.remaining("p1", 100, BaseKind.CONSUMABLE) == 0, "");
        check("笼统计数已被替换：装备用完不影响召唤的 1 次",
                afterEquipment.remaining("p1", 100, BaseKind.SUMMON) == 1, "");
    }

    private static void allowanceDaysAndOwners() {
        section("跨天与玩家隔离");
        TargetAllowance start = TargetAllowance.daily(1);
        TargetAllowance today = start.use("p1", 100, BaseKind.EQUIPMENT).orElseThrow();

        check("epochDay+1 恢复满额", today.remaining("p1", 101, BaseKind.EQUIPMENT) == 1,
                "" + today.remaining("p1", 101, BaseKind.EQUIPMENT));
        check("epochDay+1 可以再 use",
                today.use("p1", 101, BaseKind.EQUIPMENT).isPresent(), "");
        check("昨天的用度保留在账上", today.used("p1", 100, BaseKind.EQUIPMENT) == 1, "");
        check("昨天与今天的合计互不干扰", today.totalUsed("p1", 101) == 0, "");

        TargetAllowance tomorrow = today.use("p1", 101, BaseKind.EQUIPMENT).orElseThrow();
        check("同一天用满后第三天再开一轮",
                tomorrow.use("p1", 102, BaseKind.EQUIPMENT).isPresent(), "");

        check("同天不同玩家互不影响",
                today.remaining("p2", 100, BaseKind.EQUIPMENT) == 1,
                "" + today.remaining("p2", 100, BaseKind.EQUIPMENT));
        check("p2 在 p1 已用的那天独立计次",
                today.use("p2", 100, BaseKind.EQUIPMENT).isPresent(), "");
        check("未触碰过的玩家读数为 0",
                today.used("ghost", 100, BaseKind.EQUIPMENT) == 0, "");
        check("同玩家跨天共存：100 与 101 都能读到",
                tomorrow.used("p1", 100, BaseKind.EQUIPMENT) == 1
                        && tomorrow.used("p1", 101, BaseKind.EQUIPMENT) == 1, "");
    }

    private static void allowanceRetention() {
        section("淘汰策略");
        TargetAllowance account = TargetAllowance.daily(1, 2);
        check("可配置保留天数", account.retentionDays() == 2, "" + account.retentionDays());
        check("空账本 0 天", account.trackedDays() == 0, "" + account.trackedDays());

        TargetAllowance d10 = account.use("p1", 10, BaseKind.EQUIPMENT).orElseThrow();
        TargetAllowance d11 = d10.use("p1", 11, BaseKind.EQUIPMENT).orElseThrow();
        TargetAllowance d12 = d11.use("p1", 12, BaseKind.EQUIPMENT).orElseThrow();

        check("保留窗口内只留 2 天", d12.trackedDays() == 2, "实际 " + d12.trackedDays());
        check("第 10 天已被淘汰（再查为 0）", d12.used("p1", 10, BaseKind.EQUIPMENT) == 0,
                "" + d12.used("p1", 10, BaseKind.EQUIPMENT));
        check("第 11 天仍在账上", d12.used("p1", 11, BaseKind.EQUIPMENT) == 1, "");
        check("第 12 天正常记账", d12.used("p1", 12, BaseKind.EQUIPMENT) == 1, "");
        check("淘汰只发生在新实例，d11 仍看得到第 10 天",
                d11.used("p1", 10, BaseKind.EQUIPMENT) == 1 && d11.trackedDays() == 2,
                "tracked=" + d11.trackedDays());
        check("默认保留 7 天", TargetAllowance.daily(1).retentionDays() == 7,
                "" + TargetAllowance.daily(1).retentionDays());
    }

    private static void allowanceValidation() {
        section("配额参数校验");
        check("负配额 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> TargetAllowance.daily(-1)), "");
        check("保留天数 0 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> TargetAllowance.daily(1, 0)), "");
        TargetAllowance zero = TargetAllowance.daily(0);
        check("配额 0 时永远 use 不到",
                zero.use("p1", 100, BaseKind.EQUIPMENT).isEmpty(), "");
        check("配额 0 时剩余为 0", zero.remaining("p1", 100, BaseKind.EQUIPMENT) == 0, "");

        TargetAllowance normal = TargetAllowance.daily(1);
        check("空玩家 id → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> normal.use("  ", 100, BaseKind.EQUIPMENT)), "");
        check("null 玩家 id 查询 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> normal.used(null, 100, BaseKind.EQUIPMENT)), "");
        check("null 类型 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT, () -> normal.use("p1", 100, null)), "");
    }

    // ================================================================ 方案

    private static void planValidation() {
        section("定向方案校验");
        TargetPlan empty = TargetPlan.of(deck, BaseKind.EQUIPMENT, List.of());
        check("空 id 列表 = 不启用定向", empty.isEmpty() && empty.cards().isEmpty(), "");
        check("空方案仍记住类型", empty.kind() == BaseKind.EQUIPMENT, "" + empty.kind());

        TargetPlan single = TargetPlan.of(deck, BaseKind.EQUIPMENT, List.of("target.carrier.gun"));
        check("单张装备卡方案成立",
                single.cards().size() == 1 && !single.isEmpty(), "");
        check("方案保留卡 id", "target.carrier.gun".equals(single.cards().get(0).id()), "");

        TargetPlan mixed = TargetPlan.of(deck, BaseKind.EQUIPMENT,
                List.of("target.carrier.gun", "target.elem.fire"));
        check("CARRIER + ELEMENT 各一张合法", mixed.cards().size() == 2, "");
        check("方案保持传入顺序", "target.carrier.gun".equals(mixed.cards().get(0).id())
                && "target.elem.fire".equals(mixed.cards().get(1).id()), "");

        check("同层两张 → LAYER_DUPLICATE",
                throwsTarget(TargetException.Code.LAYER_DUPLICATE,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT,
                                List.of("target.carrier.gun", "target.carrier.sword"))), "");
        check("元素层两张也 → LAYER_DUPLICATE",
                throwsTarget(TargetException.Code.LAYER_DUPLICATE,
                        () -> TargetPlan.of(deck, BaseKind.SUMMON,
                                List.of("target.elem.fire", "target.elem.ice"))), "");
        check("错类型 → WRONG_KIND",
                throwsTarget(TargetException.Code.WRONG_KIND,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT,
                                List.of("target.carrier.dragon"))), "");
        check("召唤物方案里塞装备卡 → WRONG_KIND",
                throwsTarget(TargetException.Code.WRONG_KIND,
                        () -> TargetPlan.of(deck, BaseKind.SUMMON,
                                List.of("target.carrier.gun"))), "");
        check("不存在的 id → UNKNOWN_CARD",
                throwsTarget(TargetException.Code.UNKNOWN_CARD,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT,
                                List.of("target.ghost.card"))), "");
        check("同一张卡两次 → CARD_DUPLICATED",
                throwsTarget(TargetException.Code.CARD_DUPLICATED,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT,
                                List.of("target.carrier.gun", "target.carrier.gun"))), "");
        check("null id 列表 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT, null)), "");
        check("空白 id → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetPlan.of(deck, BaseKind.EQUIPMENT, List.of(" "))), "");
        check("null 卡池 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetPlan.of(null, BaseKind.EQUIPMENT, List.of())), "");
        check("null 类型 → BAD_ARGUMENT",
                throwsTarget(TargetException.Code.BAD_ARGUMENT,
                        () -> TargetPlan.of(deck, null, List.of())), "");
        check("方案卡片列表不可变", immutableList(single.cards()), "");
        check("元素卡可用于任意类型方案",
                TargetPlan.of(deck, BaseKind.CONSUMABLE, List.of("target.elem.fire")).cards().size() == 1, "");
        check("消耗品方案只收消耗品卡",
                TargetPlan.of(deck, BaseKind.CONSUMABLE, List.of("target.carrier.apple")).cards().size() == 1
                        && throwsTarget(TargetException.Code.WRONG_KIND,
                        () -> TargetPlan.of(deck, BaseKind.CONSUMABLE,
                                List.of("target.carrier.bird"))), "");
    }

    private static void planDescribes() {
        section("方案描述");
        TargetPlan empty = TargetPlan.of(deck, BaseKind.SUMMON, List.of());
        check("空方案描述含「未启用」", empty.describe().contains("未启用"), empty.describe());
        check("空方案描述带类型名", empty.describe().contains("召唤物"), empty.describe());

        TargetPlan plan = TargetPlan.of(deck, BaseKind.EQUIPMENT,
                List.of("target.carrier.gun", "target.elem.fire"));
        check("描述含类型", plan.describe().contains("装备"), plan.describe());
        check("描述含两张卡名",
                plan.describe().contains("定向·枪") && plan.describe().contains("定向·火"), plan.describe());
        report("方案描述样例", plan.describe());

        TargetAllowance ledger = TargetAllowance.daily(1);
        Optional<TargetAllowance> firstUse = ledger.use("p1", 100, plan.kind());
        check("方案首轮扣减成功", firstUse.isPresent(), "");
        TargetAllowance afterPlan = firstUse.orElseThrow();
        check("方案扣完一次后同类不可再扣",
                afterPlan.use("p1", 100, plan.kind()).isEmpty(), "");
    }

    // ================================================================ 辅助

    private static boolean allLayer(List<TargetCard> cards, Layer layer) {
        for (TargetCard card : cards) {
            if (card.targetLayer() != layer) {
                return false;
            }
        }
        return true;
    }

    private static boolean carrierPoolMatches(List<TargetCard> pool, BaseKind kind) {
        int carriers = 0;
        for (TargetCard card : pool) {
            if (card.targetLayer() != Layer.CARRIER) {
                continue;
            }
            carriers++;
            if (card.baseType() == null || !kind.wire().equalsIgnoreCase(card.baseType())) {
                return false;
            }
        }
        return carriers > 0;
    }

    private static Set<String> ids(List<TargetCard> cards) {
        Set<String> out = new HashSet<>();
        for (TargetCard card : cards) {
            out.add(card.id());
        }
        return out;
    }

    private static boolean immutableList(List<?> list) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) list;
            raw.add(new Object());
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    private static boolean immutableMap(Map<?, ?> map) {
        try {
            @SuppressWarnings("unchecked")
            Map<Object, Object> raw = (Map<Object, Object>) map;
            raw.put(new Object(), new Object());
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    private static boolean throwsTarget(TargetException.Code code, Runnable action) {
        try {
            action.run();
            return false;
        } catch (TargetException e) {
            return e.code() == code;
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

    private static void report(String name, String value) {
        System.out.println("  · " + name + "：" + value);
    }

    private static String read(String file) throws IOException {
        return Files.readString(dataDir.resolve(file), StandardCharsets.UTF_8);
    }
}
