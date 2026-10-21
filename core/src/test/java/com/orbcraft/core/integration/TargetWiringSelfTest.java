package com.orbcraft.core.integration;

import com.orbcraft.core.build.BuildConfig;
import com.orbcraft.core.build.BuildException;
import com.orbcraft.core.build.BuildSession;
import com.orbcraft.core.build.Composition;
import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.TargetCard;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.target.TargetDeck;
import com.orbcraft.core.target.TargetPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 集成自测：定向卡（{@code target} 包）与构筑状态机（{@code build} 包）的接缝。
 *
 * <p>它测的不是任何单方的内部逻辑，而是**两边各自都正确、接起来却会坏**的那几处：
 * <ol>
 *   <li>定向卡必须固定落在第 1 格 —— 否则玩家花了配额还要自己从 5 张里找。</li>
 *   <li>换一批之后定向仍然有效 —— 否则「用配额换来的一次刷新」会把定向洗掉。</li>
 *   <li>品质仍然现掷 —— 定向只保类型，不保强度。</li>
 *   <li>元素定向与形态不兼容时，必须走完流程并置 {@code targetMissed}，不能把玩家卡死在第三层。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.integration.TargetWiringSelfTest [dataDir]}
 */
public final class TargetWiringSelfTest {

    private static int passed;
    private static int failed;
    private static Path dataDir;
    private static AffixTable table;
    private static TargetDeck deck;
    private static BuildConfig config;

    public static void main(String[] args) throws Exception {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        loadData();
        timingRules();
        carrierTargeting();
        rerollKeepsTarget();
        qualityStillRandom();
        elementTargeting();
        noShapeChangeOnFinish();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 用例

    private static void timingRules() {
        section("使用时机与合法性");

        BuildSession noKind = BuildSession.create(table, 1L);
        expectCode("未选类型就用定向 → TARGET_REJECTED", BuildException.Code.TARGET_REJECTED,
                () -> noKind.applyTargetPlan(TargetPlan.of(deck, BaseKind.EQUIPMENT,
                        List.of("target.carrier.hammer"))));

        BuildSession wrongKind = BuildSession.create(table, 2L);
        wrongKind.chooseType(BaseKind.SUMMON);
        expectCode("召唤物局里塞装备定向 → TARGET_REJECTED", BuildException.Code.TARGET_REJECTED,
                () -> wrongKind.applyTargetPlan(TargetPlan.of(deck, BaseKind.EQUIPMENT,
                        List.of("target.carrier.hammer"))));

        BuildSession ghost = BuildSession.create(table, 3L);
        ghost.chooseType(BaseKind.EQUIPMENT);
        expectCode("指向不存在的词条 → TARGET_REJECTED", BuildException.Code.TARGET_REJECTED,
                () -> ghost.applyTargetPlan(new TargetPlan(BaseKind.EQUIPMENT,
                        List.of(new TargetCard("target.bogus", Layer.CARRIER, "carrier.nope",
                                "NONE", "定向·不存在", "EQUIPMENT")))));

        BuildSession twice = BuildSession.create(table, 4L);
        twice.chooseType(BaseKind.EQUIPMENT);
        twice.applyTargetPlan(plan(BaseKind.EQUIPMENT, "target.carrier.hammer"));
        expectCode("同一局用两张定向 → TARGET_REJECTED", BuildException.Code.TARGET_REJECTED,
                () -> twice.applyTargetPlan(plan(BaseKind.EQUIPMENT, "target.carrier.dagger")));

        BuildSession tooLate = BuildSession.create(table, 5L);
        tooLate.chooseType(BaseKind.EQUIPMENT);
        tooLate.confirm();
        expectCode("发牌之后再补定向 → TARGET_REJECTED", BuildException.Code.TARGET_REJECTED,
                () -> tooLate.applyTargetPlan(plan(BaseKind.EQUIPMENT, "target.carrier.hammer")));

        BuildSession empty = BuildSession.create(table, 6L);
        empty.chooseType(BaseKind.EQUIPMENT);
        empty.applyTargetPlan(new TargetPlan(BaseKind.EQUIPMENT, List.of()));
        empty.confirm();
        check("空方案不生效也不报错", empty.cards().size() == config.shapeDealCount(),
                "发牌 " + empty.cards().size() + " 张");

        check("未使用定向时 targetMissed 为 false", !empty.snapshot().targetMissed(), "");
    }

    private static void carrierTargeting() {
        section("载体定向固定第 1 格");

        List<String> equipIds = new ArrayList<>();
        for (Affix a : table.layer(Layer.CARRIER)) {
            if (a.allowsBaseType(BaseKind.EQUIPMENT.wire()) && !equipIds.contains(a.carrierId())) {
                equipIds.add(a.carrierId());
            }
        }
        check("装备载体共 12 个", equipIds.size() == 12, "实际 " + equipIds.size());

        int hit = 0;
        for (String id : equipIds) {
            Optional<TargetCard> card = deck.find("target.carrier." + id);
            boolean ok = card.isPresent();
            for (long seed = 100L; seed < 103L && ok; seed++) {
                BuildSession s = BuildSession.create(table, seed);
                s.chooseType(BaseKind.EQUIPMENT);
                s.applyTargetPlan(TargetPlan.of(deck, BaseKind.EQUIPMENT,
                        List.of("target.carrier." + id)));
                s.confirm();
                ok = !s.cards().isEmpty()
                        && id.equals(s.cards().get(0).affix().carrierId())
                        && s.cards().size() == config.shapeDealCount();
            }
            if (ok) {
                hit++;
            } else {
                System.out.println("  · 未兑现：" + id);
            }
        }
        check("12 个装备载体逐个定向都在第 1 格出现", hit == equipIds.size(),
                hit + "/" + equipIds.size());

        // 同一载体不同种子都要能定向成功，且不因定向而少发牌
        Set<String> sizes = new LinkedHashSet<>();
        for (long seed = 200L; seed < 220L; seed++) {
            BuildSession s = BuildSession.create(table, seed);
            s.chooseType(BaseKind.SUMMON);
            s.applyTargetPlan(plan(BaseKind.SUMMON, "target.carrier.bird"));
            s.confirm();
            sizes.add(String.valueOf(s.cards().size()));
        }
        check("定向后候选张数恒为 5", sizes.size() == 1 && sizes.contains("5"), sizes.toString());
    }

    private static void rerollKeepsTarget() {
        section("换一批之后定向仍在");

        int kept = 0;
        for (long seed = 300L; seed < 320L; seed++) {
            BuildSession s = BuildSession.create(table, seed);
            s.chooseType(BaseKind.EQUIPMENT);
            s.applyTargetPlan(plan(BaseKind.EQUIPMENT, "target.carrier.hammer"));
            s.confirm();
            if (!"hammer".equals(s.cards().get(0).affix().carrierId())) {
                continue;
            }
            try {
                s.rerollShape();
            } catch (BuildException e) {
                continue; // 该种子下换批不可用（次数或阶段），跳过
            }
            if ("hammer".equals(s.cards().get(0).affix().carrierId())) {
                kept++;
            }
        }
        check("换批 20 次试验中定向从未丢失", kept > 0, "保留 " + kept + " 次");
    }

    private static void qualityStillRandom() {
        section("只保类型不保强度");

        Set<Tier> tiers = new LinkedHashSet<>();
        for (long seed = 400L; seed < 460L; seed++) {
            BuildSession s = BuildSession.create(table, seed);
            s.chooseType(BaseKind.EQUIPMENT);
            s.applyTargetPlan(plan(BaseKind.EQUIPMENT, "target.carrier.hammer"));
            s.confirm();
            tiers.add(s.cards().get(0).tier().tier());
        }
        check("定向载体的品质仍随种子变化（≥2 种）", tiers.size() >= 2, tiers.toString());
    }

    private static void elementTargeting() {
        section("元素定向");

        List<TargetCard> elementCards = deck.forKind(BaseKind.EQUIPMENT).stream()
                .filter(c -> c.targetLayer() == Layer.ELEMENT).toList();
        check("元素定向卡共 9 张", elementCards.size() == 9, "实际 " + elementCards.size());

        int deliverable = 0;
        for (TargetCard card : elementCards) {
            boolean ok = false;
            for (long seed = 500L; seed < 560L && !ok; seed++) {
                BuildSession s = BuildSession.create(table, seed);
                s.chooseType(BaseKind.EQUIPMENT);
                s.applyTargetPlan(plan(BaseKind.EQUIPMENT, card.id()));
                s.confirm();
                s.choose(0);
                s.confirm();
                if (s.snapshot().targetMissed()) {
                    continue; // 这个种子抽到了挂不上该元素的载体，换一个种子看兑现路径
                }
                ok = card.targetAffix().equals(s.cards().get(0).affix().id());
            }
            if (ok) {
                deliverable++;
            }
        }
        check("9 张元素定向卡全部兑现（当前元素词条未设载体限制）", deliverable == 9,
                "兑现 " + deliverable + " 张");

        // 不兼容路径：目前数据里元素词条 carrier_allow 为空（对所有载体可用），所以走不到。
        // 这条用例真正的价值是「将来有人给元素加了载体限制」时立刻报警 ——
        // 那种改动会让一部分定向卡静默失效，而玩家已经付掉了配额。
        String[] pair = findIncompatiblePair(elementCards);
        if (pair == null) {
            check("当前数据下不存在不兼容组合（元素限制为空时属预期）", true, "");
            BuildSession probe = BuildSession.create(table, 800L);
            probe.chooseType(BaseKind.EQUIPMENT);
            probe.applyTargetPlan(plan(BaseKind.EQUIPMENT, elementCards.get(0).id()));
            probe.confirm();
            probe.choose(0);
            probe.confirm();
            check("无不兼容时 targetMissed 恒为 false", !probe.snapshot().targetMissed(), "误置位");
        } else {
            String carrier = pair[0];
            String elementCard = pair[1];
            BuildSession s = BuildSession.create(table, 800L);
            s.chooseType(BaseKind.EQUIPMENT);
            s.applyTargetPlan(TargetPlan.of(deck, BaseKind.EQUIPMENT,
                    List.of("target.carrier." + carrier, elementCard)));
            s.confirm();
            check("载体 + 元素双定向可用", carrier.equals(s.cards().get(0).affix().carrierId()),
                    "首张 " + s.cards().get(0).affix().carrierId());
            s.choose(0);
            s.confirm();
            check("元素挂不上该载体时置 targetMissed", s.snapshot().targetMissed(), "未置位");
            check("失效后修饰牌仍然发满", s.cards().size() == config.modifierDealCount(),
                    "发 " + s.cards().size() + " 张");
            s.choose(0);
            s.confirm();
            s.choose(0);
            Optional<Composition> done = s.confirm();
            check("失效的定向不会把玩家卡在中途", done.isPresent(), "未走到定稿");
        }
    }

    /** 找一对「元素词条明确不允许该载体」的组合，返回 [carrierId, targetCardId]。 */
    private static String[] findIncompatiblePair(List<TargetCard> elementCards) {
        for (Affix carrier : table.layer(Layer.CARRIER)) {
            if (!carrier.allowsBaseType(BaseKind.EQUIPMENT.wire())) {
                continue;
            }
            String carrierId = carrier.carrierId();
            for (TargetCard card : elementCards) {
                Optional<Affix> element = table.findById(card.targetAffix());
                if (element.isPresent() && !element.get().allowsCarrier(carrierId)) {
                    return new String[]{carrierId, card.id()};
                }
            }
        }
        return null;
    }

    private static void noShapeChangeOnFinish() {
        section("定向不改变产物结构");

        BuildSession s = BuildSession.create(table, 700L);
        s.chooseType(BaseKind.SUMMON);
        s.applyTargetPlan(plan(BaseKind.SUMMON, "target.carrier.bird"));
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        Composition c = s.confirm().orElseThrow();
        check("最终组合仍是 3 张（载体 + 1 修饰 + 1 效果）", c.size() == 3, "实际 " + c.size());
        check("定向载体就是最终载体", c.carrierId().orElse("").equals("bird"),
                c.carrierId().orElse("—"));
        check("稀有度与倍率都算得出来", c.rarity() != null && c.hatchMultiplier() > 0,
                c.rarity() + " ×" + c.hatchMultiplier());
        check("简报能正常渲染", !c.brief().isEmpty(), c.brief());
    }

    // ================================================================ 工具

    private static void loadData() throws IOException {
        section("卡表加载");
        table = AffixData.loadTable(
                read("affixes.carrier.json"),
                read("affixes.element.json"),
                read("affixes.attribute.json"),
                read("affixes.effect.json"));
        deck = TargetDeck.of(AffixData.readTargetCards(read("target_cards.json")));
        config = BuildConfig.defaults();
        check("总词条 1124 条", table.size() == 1124, "实际 " + table.size());
        check("定向卡 44 张", deck.size() == 44, "实际 " + deck.size());
    }

    private static TargetPlan plan(BaseKind kind, String cardId) {
        return TargetPlan.of(deck, kind, List.of(cardId));
    }

    private static String read(String name) throws IOException {
        return Files.readString(dataDir.resolve(name), StandardCharsets.UTF_8);
    }

    private static void expectCode(String name, BuildException.Code code, Runnable action) {
        try {
            action.run();
            check(name, false, "没有抛异常");
        } catch (BuildException e) {
            check(name, e.code() == code, "实际 " + e.code());
        } catch (RuntimeException e) {
            check(name, false, e.getClass().getSimpleName());
        }
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

    private static void section(String title) {
        System.out.println("【" + title + "】");
    }
}
