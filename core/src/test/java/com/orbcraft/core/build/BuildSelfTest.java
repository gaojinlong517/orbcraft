package com.orbcraft.core.build;

import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.settle.CompositionSettler;
import com.orbcraft.core.settle.HatchTime;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.settle.SettleException;
import com.orbcraft.core.settle.SettleRequest;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 构筑流程自测 —— 用真实 1124 条卡表跑，不用假数据。
 *
 * <p>重点不是「能不能走完」，而是四件容易在重构里悄悄丢掉的事：
 * <ol>
 *   <li><b>未确认不可推进。</b> choose 之后阶段必须原地不动，只有 confirm 才跃迁。</li>
 *   <li><b>修饰晚于形态。</b> 形态未确认前，修饰牌不能存在；修饰池必须按已确认的载体过滤。</li>
 *   <li><b>没有交易通道。</b> 类里不能有 deal / reroll / points 这类入口，
 *       否则玩家能通过反复刷牌绕过随机性。</li>
 *   <li><b>形态只有一个。</b> 一件产物一张外观 key，多选在渲染层没有正确答案。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.build.BuildSelfTest [dataDir]}
 */
public final class BuildSelfTest {

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
        initialState();
        typeLayerRules();
        confirmIsTheOnlyDoor();
        shapeLayerRules();
        shapeBeforeModifier();
        modifierLayerRules();
        effectLayerAndFinish();
        noTradingSurface();
        seedReproducible();
        configValidation();
        terminalRules();
        settleBridge();
        simulate(300);

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("构筑自测失败 " + failed + " 项");
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
        check("默认配置：形态层发 5 选 1",
                config.shapeDealCount() == 5 && config.shapePickCount() == 1,
                config.shapeDealCount() + "/" + config.shapePickCount());
        check("默认配置：修饰层发 5 选 1",
                config.modifierDealCount() == 5 && config.modifierPickCount() == 1,
                config.modifierDealCount() + "/" + config.modifierPickCount());
        check("默认配置：效果层发 5 选 1",
                config.effectDealCount() == 5 && config.effectPickCount() == 1,
                config.effectDealCount() + "/" + config.effectPickCount());
        check("形态选取数恒为 1（不可配）", BuildConfig.SHAPE_PICK == 1, "" + BuildConfig.SHAPE_PICK);
    }

    // ================================================================ 初始态

    private static void initialState() {
        section("初始状态");
        BuildSession s = BuildSession.create(table, 1L);
        check("初始阶段为类型层选择", s.phase() == BuildPhase.TYPE_CHOOSING, "实际 " + s.phase());
        check("类型层无候选卡（还没发牌）", s.cards().isEmpty(), "实际 " + s.cards().size());
        check("未选类型时 confirm 被拒", throwsCode(BuildException.Code.NO_PENDING_CHOICE, s::confirm), "");
        check("未选时 canConfirm 为 false", !s.canConfirm(), "");
        check("类型层提示为「先选一个类型」", "先选一个类型".equals(s.confirmHint()), s.confirmHint());

        List<TypeOption> opts = s.typeOptions();
        check("类型层固定 3 个候选", opts.size() == 3, "实际 " + opts.size());
        check("候选顺序为 装备/召唤物/消耗品",
                opts.get(0).kind() == BaseKind.EQUIPMENT
                        && opts.get(1).kind() == BaseKind.SUMMON
                        && opts.get(2).kind() == BaseKind.CONSUMABLE,
                opts.toString());
        check("三类在真实卡表下都可用", opts.stream().allMatch(TypeOption::available), opts.toString());
        check("快照状态栏可读", s.snapshot().statusLine().contains("类型层"), s.snapshot().statusLine());
    }

    // ================================================================ 类型层

    private static void typeLayerRules() {
        section("类型层：选择与确认分离");

        BuildSession s = BuildSession.create(table, 2L);
        s.choose(0);
        check("选择后进入待确认", s.phase() == BuildPhase.TYPE_CONFIRMING, "实际 " + s.phase());
        check("选择不等于推进：仍在类型层", s.phase().layerIndex() == 0, "" + s.phase().layerIndex());
        check("待确认时已选 1 项", s.chosen().size() == 1 && s.chosen().get(0) == 0, s.chosen().toString());
        check("待确认时可确认", s.canConfirm(), "");
        check("待确认时仍无候选卡", s.cards().isEmpty(), "" + s.cards().size());

        // 改选：点另一个类型
        s.choose(1);
        check("改选后类型变为召唤物", s.kind().orElseThrow() == BaseKind.SUMMON, s.kind().toString());
        check("改选不推进阶段", s.phase() == BuildPhase.TYPE_CONFIRMING, "" + s.phase());

        // 再点同一个 = 取消
        s.choose(1);
        check("再点同一项触发取消", s.phase() == BuildPhase.TYPE_CHOOSING, "" + s.phase());
        check("取消后无已选类型", s.kind().isEmpty(), s.kind().toString());
        check("取消后 chosen 为空", s.chosen().isEmpty(), s.chosen().toString());

        // 越界
        check("类型索引越界被拒", throwsCode(BuildException.Code.INDEX_OUT_OF_RANGE, () -> s.choose(3)),
                "共 3 项");
        check("负索引被拒", throwsCode(BuildException.Code.INDEX_OUT_OF_RANGE, () -> s.choose(-1)), "");

        // 确认后推进到形态层并发牌
        s.choose(0);
        Optional<Composition> early = s.confirm();
        check("中间层确认不返回结果", early.isEmpty(), "");
        check("确认后进入形态层", s.phase() == BuildPhase.SHAPE_CHOOSING, "实际 " + s.phase());
        check("确认后立即发 5 张形态牌", s.cards().size() == 5, "实际 " + s.cards().size());
    }

    // ================================================================ 确认唯一入口

    private static void confirmIsTheOnlyDoor() {
        section("未确认不可推进");

        BuildSession s = startShape(BaseKind.EQUIPMENT, 3L);
        check("进入形态层时无任何选择", s.chosen().isEmpty(), s.chosen().toString());

        List<DraftCard> before = s.cards();
        s.choose(0);
        check("形态层选择后仍在形态层", s.phase() == BuildPhase.SHAPE_CONFIRMING, "" + s.phase());
        check("选择不换牌：候选保持不变", sameIds(before, s.cards()), "");
        check("选择不产生已确认卡", s.confirmedCards().isEmpty(), "" + s.confirmedCards().size());

        // 反复选/取消 20 次，阶段必须一直在形态层
        for (int k = 0; k < 20; k++) {
            s.choose(0);
        }
        check("反复改选 20 次仍未离开形态层",
                s.phase() == BuildPhase.SHAPE_CHOOSING || s.phase() == BuildPhase.SHAPE_CONFIRMING,
                "" + s.phase());
        check("反复改选后已确认卡仍为空", s.confirmedCards().isEmpty(), "");

        // 清空选择
        s.clearChoices();
        check("清空后回到选择态", s.phase() == BuildPhase.SHAPE_CHOOSING, "" + s.phase());
        check("清空后无已选", s.chosen().isEmpty(), s.chosen().toString());
    }

    // ================================================================ 形态层

    private static void shapeLayerRules() {
        section("形态层");

        BuildSession s = startShape(BaseKind.EQUIPMENT, 11L);
        List<DraftCard> cards = s.cards();
        check("候选 5 张", cards.size() == 5, "实际 " + cards.size());
        check("候选全部是载体卡",
                cards.stream().allMatch(c -> c.affix().layer() == Layer.CARRIER), layerOrder(cards));
        check("候选无重复族", distinctFamilies(cards) == cards.size(), "");
        check("候选全部适配装备类型",
                cards.stream().allMatch(c -> c.affix().allowsBaseType(BaseKind.EQUIPMENT.wire())), "");
        check("状态栏文案含「形态层」",
                s.snapshot().statusLine().contains("形态层"), s.snapshot().statusLine());

        // 单选约束
        s.choose(0);
        check("选 1 张即可确认", s.canConfirm(), s.confirmHint());
        check("同一层再选第 2 张被拒（形态只能 1 个）",
                throwsCode(BuildException.Code.TOO_MANY_CHOICES, () -> s.choose(1)), "");
        s.choose(0);
        check("再点同一张取消后回选择态", s.phase() == BuildPhase.SHAPE_CHOOSING, "" + s.phase());
        check("取消后未选满 confirm 被拒",
                throwsCode(BuildException.Code.NO_PENDING_CHOICE, s::confirm), s.confirmHint());

        check("索引越界被拒", throwsCode(BuildException.Code.INDEX_OUT_OF_RANGE, () -> s.choose(5)), "");
    }

    // ================================================================ 形态 → 修饰 的先后

    private static void shapeBeforeModifier() {
        section("先形态，再修饰（结构保证）");

        BuildSession s = startShape(BaseKind.SUMMON, 77L);
        check("形态阶段只有载体牌",
                s.cards().stream().allMatch(c -> c.affix().layer() == Layer.CARRIER), layerOrder(s.cards()));
        check("形态阶段已确认卡为空", s.confirmedCards().isEmpty(), "");
        check("形态阶段没有元素/属性牌混入",
                s.cards().stream().noneMatch(c -> c.affix().layer() == Layer.ELEMENT
                        || c.affix().layer() == Layer.ATTRIBUTE), layerOrder(s.cards()));

        int shapeIdx = 2;
        DraftCard shape = s.cards().get(shapeIdx);
        String carrier = shape.affix().carrierId();
        s.choose(shapeIdx);
        check("选中形态但未确认，仍只有形态牌",
                s.cards().size() == 5
                        && s.cards().stream().allMatch(c -> c.affix().layer() == Layer.CARRIER), "");

        Optional<Composition> none = s.confirm();
        check("形态确认不返回结果", none.isEmpty(), "");
        check("确认后进入修饰层", s.phase() == BuildPhase.MODIFIER_CHOOSING, "" + s.phase());
        check("形态已计入已确认（1 张）", s.confirmedCards().size() == 1, "" + s.confirmedCards().size());

        List<DraftCard> mods = s.cards();
        check("修饰层发 5 张", mods.size() == 5, "实际 " + mods.size());
        check("修饰牌不含载体",
                mods.stream().noneMatch(c -> c.affix().layer() == Layer.CARRIER), layerOrder(mods));
        check("修饰牌全部适配已确认的形态 " + carrier,
                mods.stream().allMatch(c -> c.affix().allowsCarrier(carrier)), layerOrder(mods));
        check("修饰牌与形态不同族（同族已进 usedFamilies）",
                mods.stream().noneMatch(c -> c.family().equals(shape.family())), "");
        check("修饰牌无重复族", distinctFamilies(mods) == mods.size(), "");
        check("修饰牌只含元素或属性",
                mods.stream().allMatch(c -> c.affix().layer() == Layer.ELEMENT
                        || c.affix().layer() == Layer.ATTRIBUTE), layerOrder(mods));

        // 换一个形态会换一批修饰牌 —— 证明修饰池确实是按形态过滤的
        BuildSession other = startShape(BaseKind.SUMMON, 77L);
        int alt = (shapeIdx + 1) % other.cards().size();
        String altCarrier = other.cards().get(alt).affix().carrierId();
        other.choose(alt);
        other.confirm();
        check("不同形态的修饰牌各自适配本形态",
                other.cards().stream().allMatch(c -> c.affix().allowsCarrier(altCarrier)),
                altCarrier + " vs " + carrier);
    }

    // ================================================================ 修饰层

    private static void modifierLayerRules() {
        section("修饰层");

        BuildSession s = startModifier(BaseKind.CONSUMABLE, 13L);
        List<DraftCard> cards = s.cards();
        check("候选 5 张", cards.size() == 5, "实际 " + cards.size());
        check("未选时 canConfirm 为 false", !s.canConfirm(), "");
        check("未选就确认被拒", throwsCode(BuildException.Code.NO_PENDING_CHOICE, s::confirm), "");

        s.choose(0);
        check("选 1 张后可确认", s.canConfirm(), s.confirmHint());
        check("超选第 2 张被拒", throwsCode(BuildException.Code.TOO_MANY_CHOICES, () -> s.choose(1)),
                "上限 1");
        s.choose(0);
        check("取消后回到选择态", s.phase() == BuildPhase.MODIFIER_CHOOSING, "" + s.phase());

        s.choose(3);
        Optional<Composition> none = s.confirm();
        check("修饰确认不返回结果", none.isEmpty(), "");
        check("确认后进入效果层", s.phase() == BuildPhase.EFFECT_CHOOSING, "" + s.phase());
        check("已确认累计 2 张（形态 + 修饰）", s.confirmedCards().size() == 2,
                "" + s.confirmedCards().size());
        check("效果层已发 5 张", s.cards().size() == 5, "实际 " + s.cards().size());
    }

    // ================================================================ 效果层与收尾

    private static void effectLayerAndFinish() {
        section("效果层与最终组合");

        BuildSession s = startEffect(BaseKind.SUMMON, 21L);
        check("效果层阶段正确", s.phase() == BuildPhase.EFFECT_CHOOSING, "" + s.phase());
        List<DraftCard> cards = s.cards();
        check("效果候选 5 张", cards.size() == 5, "实际 " + cards.size());
        check("效果候选全为效果层",
                cards.stream().allMatch(c -> c.affix().layer() == Layer.EFFECT), "");
        check("效果候选无重复族", distinctFamilies(cards) == cards.size(), "");

        check("未选就确认被拒", throwsCode(BuildException.Code.NO_PENDING_CHOICE, s::confirm), "");
        s.choose(0);
        check("选 1 张后 effect 层可确认", s.canConfirm(), s.confirmHint());
        check("效果层超选被拒", throwsCode(BuildException.Code.TOO_MANY_CHOICES, () -> s.choose(1)), "");

        Optional<Composition> result = s.confirm();
        check("最后一层确认返回结果", result.isPresent(), "");
        check("阶段为已完成", s.phase() == BuildPhase.FINISHED, "" + s.phase());

        Composition c = result.orElseThrow();
        check("组合共 3 张（1 形态 + 1 修饰 + 1 效果）", c.size() == 3, "实际 " + c.size());
        check("组合含载体", c.carrierCard().isPresent(), "");
        check("组合含 1 张修饰", c.modifiers().size() == 1, "" + c.modifiers().size());
        check("组合含 1 张效果", c.effects().size() == 1, "" + c.effects().size());
        check("卡牌顺序固定为 载体→修饰→效果",
                c.cards().get(0).affix().layer() == Layer.CARRIER
                        && c.cards().get(c.size() - 1).affix().layer() == Layer.EFFECT,
                layerOrder(c));
        check("类型为召唤物", c.kind() == BaseKind.SUMMON, "" + c.kind());
        check("种子被记录", c.seed() == 21L, "" + c.seed());
        check("平均点数与总点数自洽",
                Math.abs(c.avgPoints() - (double) c.totalPoints() / c.size()) < 1e-9,
                c.totalPoints() + "/" + c.size());
        check("稀有度与平均点数一致", c.rarity() == config.rarityOf(c.avgPoints()),
                c.rarity() + " avg=" + c.avgPoints());
        check("孵化倍率 ≥ 1.0", c.hatchMultiplier() >= 1.0, "" + c.hatchMultiplier());
        check("播报文案非空", !c.brief().isBlank(), c.brief());
        check("slots 与卡数一致", c.slots().size() == c.size(), "" + c.slots().size());
        check("tags 含载体 id", c.carrierId().isPresent() && c.tags().contains(c.carrierId().orElse("")),
                c.tags().toString());
        check("完成后 composition() 可取", s.composition().isPresent(), "");
        check("完成后快照标记完成", s.snapshot().finished(), "");

        report("样例", c.brief());
        report("详情", c.describe().replace("\n", " | "));
    }

    // ================================================================ 无交易面

    private static void noTradingSurface() {
        section("非交易型：没有购买/刷新/扣费入口");

        List<String> forbidden = List.of("deal", "reroll", "buy", "pay", "spend", "points",
                "budget", "cost", "orb");
        List<String> exposed = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        for (Method m : BuildSession.class.getMethods()) {
            // 只检查本类声明的方法，Object 的 toString 之类不算
            if (m.getDeclaringClass() != BuildSession.class) {
                continue;
            }
            methods.add(m.getName());
            String lower = m.getName().toLowerCase();
            for (String bad : forbidden) {
                if (lower.contains(bad)) {
                    exposed.add(m.getName());
                }
            }
        }
        check("BuildSession 无公开的 deal/reroll/购买类方法", exposed.isEmpty(), exposed.toString());
        check("公开方法数量合理（≤ 20）", methods.size() <= 20, "" + methods.size() + " 个：" + methods);

        // 全流程任何一步都不该出现「余额不足」这类错误码
        List<String> resourceCodes = new ArrayList<>();
        for (BuildException.Code c : BuildException.Code.values()) {
            String n = c.name();
            if (n.contains("BUDGET") || n.contains("INSUFFICIENT") || n.contains("PAY")
                    || n.contains("COST") || n.contains("ORB")) {
                resourceCodes.add(n);
            }
        }
        check("错误码表里没有余额/交易类错误", resourceCodes.isEmpty(), resourceCodes.toString());

        // 重复确认不会产出第二件
        BuildSession s = startEffect(BaseKind.EQUIPMENT, 5L);
        s.choose(0);
        Optional<Composition> first = s.confirm();
        check("再次 confirm 被拒（不能刷第二次定稿）",
                first.isPresent() && throwsCode(BuildException.Code.WRONG_PHASE, s::confirm), "");
    }

    // ================================================================ 可复现

    private static void seedReproducible() {
        section("随机可复现");

        Composition a = playFixed(BaseKind.EQUIPMENT, 99L);
        Composition b = playFixed(BaseKind.EQUIPMENT, 99L);
        check("同 seed 同策略得到同一组合", sameComposition(a, b), a.brief() + " vs " + b.brief());

        Composition c = playFixed(BaseKind.EQUIPMENT, 100L);
        check("不同 seed 得到不同组合", !sameComposition(a, c), a.brief() + " vs " + c.brief());

        // 不同类型走不同池子
        Composition equip = playFixed(BaseKind.EQUIPMENT, 7L);
        Composition summon = playFixed(BaseKind.SUMMON, 7L);
        check("不同种子类型不同产物", equip.kind() != summon.kind(), "");
        check("装备产物不含召唤专属载体",
                equip.carrierCard().map(x -> x.affix().allowsBaseType(BaseKind.EQUIPMENT.wire())).orElse(false),
                equip.brief());

        BuildSession s1 = BuildSession.create(table, 42L);
        BuildSession s2 = BuildSession.create(table, 42L);
        s1.choose(0);
        s2.choose(0);
        s1.confirm();
        s2.confirm();
        check("同 seed 形态发牌序列一致", sameIds(s1.cards(), s2.cards()), "");
        s1.choose(1);
        s2.choose(1);
        s1.confirm();
        s2.confirm();
        check("同 seed 修饰发牌序列一致", sameIds(s1.cards(), s2.cards()), "");
    }

    // ================================================================ 配置校验

    private static void configValidation() {
        section("配置校验");

        check("形态候选 < 3 被拒", throwsBadConfig(() -> new BuildConfig(
                2, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("形态候选 > 9 被拒", throwsBadConfig(() -> new BuildConfig(
                10, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("修饰候选 < 3 被拒", throwsBadConfig(() -> new BuildConfig(
                5, 2, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("修饰选取数 0 被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 0, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("修饰选取数 > 候选数被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 6, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("效果选取数 > 候选数被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 6, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");
        check("稀有度阈值不递增被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 5.0, 2.8, 1.6, 0.15)), "");
        check("全零权重被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 1, Map.of(Tier.WHITE, 0, Tier.BLUE, 0, Tier.GOLD, 0, Tier.RED, 0),
                Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15)), "");

        // 可配置性实证：三层候选都发 3，修饰选 2，效果选 2
        BuildConfig small = new BuildConfig(3, 3, 2, 3, 2, null,
                Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15);
        BuildSession s = new BuildSession(table, small, 13L);
        s.choose(0);
        s.confirm();
        check("形态候选数可配为 3", s.cards().size() == 3, "实际 " + s.cards().size());
        s.choose(0);
        s.confirm();
        check("修饰候选数可配为 3", s.cards().size() == 3, "实际 " + s.cards().size());
        s.choose(0);
        check("修饰选 1 张时不可确认（要求 2 张）", !s.canConfirm(), s.confirmHint());
        s.choose(1);
        check("修饰选 2 张后可确认", s.canConfirm(), s.confirmHint());
        s.confirm();
        check("效果候选数可配为 3", s.cards().size() == 3, "实际 " + s.cards().size());
        s.choose(0);
        s.choose(1);
        Composition c = s.confirm().orElseThrow();
        check("修饰选取数可配为 2", c.modifiers().size() == 2, "" + c.modifiers().size());
        check("效果选取数可配为 2", c.effects().size() == 2, "" + c.effects().size());
        check("组合共 5 张（1+2+2）", c.size() == 5, "" + c.size());

        // 保底配置
        BuildConfig floored = BuildConfig.defaults().withFloors(Tier.GOLD, Tier.GOLD, Tier.GOLD);
        Composition f = playFixed(floored, BaseKind.EQUIPMENT, 31L);
        check("保底金档：所有卡至少金",
                f.cards().stream().allMatch(x -> x.tier().tier().rank() >= Tier.GOLD.rank()),
                f.brief());
    }

    // ================================================================ 终止态

    private static void terminalRules() {
        section("终止态");

        BuildSession s = startShape(BaseKind.EQUIPMENT, 8L);
        s.cancel();
        check("取消后阶段为已取消", s.phase() == BuildPhase.CANCELLED, "" + s.phase());
        check("取消后选择被拒", throwsCode(BuildException.Code.WRONG_PHASE, () -> s.choose(0)), "");
        check("取消后确认被拒", throwsCode(BuildException.Code.WRONG_PHASE, s::confirm), "");
        check("取消后不能再次取消", throwsCode(BuildException.Code.WRONG_PHASE, s::cancel), "");
        check("取消后无产物", s.composition().isEmpty(), "");

        BuildSession done = playFixedSession(BaseKind.CONSUMABLE, 17L);
        check("完成后选择被拒", throwsCode(BuildException.Code.WRONG_PHASE, () -> done.choose(0)), "");
        check("完成后取消被拒", throwsCode(BuildException.Code.WRONG_PHASE, done::cancel), "");
        check("完成后清空被拒", throwsCode(BuildException.Code.WRONG_PHASE, done::clearChoices), "");
    }

    // ================================================================ 结算桥

    private static void settleBridge() {
        section("结算桥：Composition → Spec");

        Composition c = playFixed(BaseKind.EQUIPMENT, 55L);
        SettleRequest req = SettleRequest.of("player-a", MaterialTier.MASTERWORK);
        Spec spec = CompositionSettler.settle(c, req);

        check("Spec 类型与组合一致", spec.type() == ProductType.EQUIPMENT, "" + spec.type());
        check("Spec 稀有度与组合一致", spec.rarity() == c.rarity(), "" + spec.rarity());
        check("装备行为模式为 NONE", spec.behavior() == BehaviorMode.NONE, "" + spec.behavior());
        check("孵化秒数在上限内", spec.hatchSeconds() <= c.rarity().maxHatchSeconds(),
                spec.hatchSeconds() + " <= " + c.rarity().maxHatchSeconds());
        check("attrs 非空（属性/效果被结算进去）", !spec.attrs().isEmpty(), spec.attrs().toString());
        check("tags 含载体 id", spec.tags().contains(c.carrierId().orElse("")), spec.tags().toString());
        check("flavor 非空", !spec.flavor().isBlank(), spec.flavor());

        Spec again = CompositionSettler.settle(c, req);
        check("同一玩家重复结算 id 相同", spec.id().equals(again.id()), spec.id() + " vs " + again.id());

        Spec other = CompositionSettler.settle(c, SettleRequest.of("player-b", MaterialTier.MASTERWORK));
        check("不同玩家相同配方 id 不同", !spec.id().equals(other.id()), spec.id() + " vs " + other.id());

        check("装备传攻击行为模式被拒",
                throwsSettle(() -> CompositionSettler.settle(c,
                        SettleRequest.summon("player-a", BehaviorMode.ATTACK, MaterialTier.MASTERWORK))), "");

        // 召唤物必须带行为模式
        Composition summon = playFixed(BaseKind.SUMMON, 56L);
        check("召唤物不指定行为模式被拒",
                throwsSettle(() -> CompositionSettler.settle(summon,
                        SettleRequest.of("player-a", MaterialTier.MASTERWORK))), "");
        Spec summonSpec = CompositionSettler.settle(summon,
                SettleRequest.summon("player-a", BehaviorMode.ATTACK, MaterialTier.MASTERWORK));
        check("召唤物指定攻击后可结算", summonSpec.behavior() == BehaviorMode.ATTACK, "");

        HatchTime.Plan plan = CompositionSettler.previewHatch(c);
        check("时长预览与 Spec 一致", plan.seconds() == spec.hatchSeconds(),
                plan.seconds() + " vs " + spec.hatchSeconds());
        report("结算样例", spec.displayName() + " " + spec.rarity().zh()
                + " " + spec.hatchSeconds() + "s " + spec.attrs());
    }

    // ================================================================ 压力模拟

    private static void simulate(int roundsPerKind) {
        section("压力模拟 " + (roundsPerKind * BaseKind.values().length) + " 局");

        Stats stats = new Stats();
        int failures = 0;
        List<String> errors = new ArrayList<>();

        for (BaseKind kind : BaseKind.values()) {
            for (int i = 0; i < roundsPerKind; i++) {
                long seed = 1000L * (kind.ordinal() + 1) + i;
                try {
                    Composition c = playOne(kind, seed, stats);
                    stats.record(c);
                } catch (RuntimeException e) {
                    failures++;
                    if (errors.size() < 5) {
                        errors.add(kind.zh() + " seed=" + seed + " → " + e);
                    }
                }
            }
        }

        int total = roundsPerKind * BaseKind.values().length;
        check("全部 " + total + " 局无异常", failures == 0,
                failures + " 局失败：" + String.join("; ", errors));
        check("形态层每局都发满 5 张", stats.minShape == 5 && stats.maxShape == 5,
                "范围 " + stats.minShape + "~" + stats.maxShape);
        check("修饰层每局都发满 5 张", stats.minModifier == 5 && stats.maxModifier == 5,
                "范围 " + stats.minModifier + "~" + stats.maxModifier);
        check("效果层每局都发满 5 张", stats.minEffect == 5 && stats.maxEffect == 5,
                "范围 " + stats.minEffect + "~" + stats.maxEffect);

        report("稀有度分布", stats.rarityLine());
        report("形态层发牌", stats.minShape + "~" + stats.maxShape + " 张（均值 "
                + String.format("%.2f", stats.avg(stats.shapeSum)) + "）");
        report("修饰层发牌", stats.minModifier + "~" + stats.maxModifier + " 张（均值 "
                + String.format("%.2f", stats.avg(stats.modifierSum)) + "）");
        report("效果层发牌", stats.minEffect + "~" + stats.maxEffect + " 张（均值 "
                + String.format("%.2f", stats.avg(stats.effectSum)) + "）");
        report("平均卡数", String.format("%.2f", stats.avgCards()));
        report("平均点数", String.format("%.2f", stats.avgPoints()));
        report("孵化倍率范围", String.format("%.2f", stats.minHatch) + "~"
                + String.format("%.2f", stats.maxHatch));

        // 分布健康度：任何一档都不能是 0% 或 100%
        double legendPct = stats.pct(Rarity.LEGENDARY);
        double commonPct = stats.pct(Rarity.COMMON);
        check("传说占比不是极端值（0% < p < 60%）", legendPct > 0 && legendPct < 60,
                String.format("%.1f%%", legendPct));
        check("普通占比不是极端值（0% < p < 90%）", commonPct > 0 && commonPct < 90,
                String.format("%.1f%%", commonPct));
        check("四档稀有度都有产出", stats.distinctRarities() == 4, stats.rarityLine());
    }

    // ================================================================ 玩法辅助

    /** 从类型层走到形态层。 */
    private static BuildSession startShape(BaseKind kind, long seed) {
        BuildSession s = new BuildSession(table, config, seed);
        s.chooseType(kind);
        s.confirm();
        return s;
    }

    /** 从类型层走到修饰层（形态选第 0 张）。 */
    private static BuildSession startModifier(BaseKind kind, long seed) {
        BuildSession s = startShape(kind, seed);
        s.choose(0);
        s.confirm();
        return s;
    }

    /** 从类型层走到效果层（形态第 0 张、修饰第 0 张）。 */
    private static BuildSession startEffect(BaseKind kind, long seed) {
        BuildSession s = startModifier(kind, seed);
        s.choose(0);
        s.confirm();
        return s;
    }

    /** 固定策略走完全程：三层都选第 0 张。 */
    private static BuildSession playFixedSession(BaseKind kind, long seed) {
        BuildSession s = startEffect(kind, seed);
        s.choose(0);
        s.confirm();
        return s;
    }

    private static Composition playFixed(BaseKind kind, long seed) {
        return playFixedSession(kind, seed).composition()
                .orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    private static Composition playFixed(BuildConfig cfg, BaseKind kind, long seed) {
        BuildSession s = new BuildSession(table, cfg, seed);
        s.chooseType(kind);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 按 seed 变化选择索引，模拟不同玩家的偏好。 */
    private static Composition playOne(BaseKind kind, long seed, Stats stats) {
        BuildSession s = new BuildSession(table, config, seed);
        TypeOption opt = s.typeOptions().get(BuildSession.indexOfType(kind));
        if (!opt.available()) {
            throw new IllegalStateException("类型不可用：" + kind.zh());
        }
        s.chooseType(kind);
        s.confirm();

        List<DraftCard> shape = s.cards();
        stats.recordShape(shape.size());
        if (shape.isEmpty()) {
            throw new IllegalStateException("形态候选为空");
        }
        s.choose(Math.floorMod(seed, shape.size()));
        s.confirm();

        List<DraftCard> mods = s.cards();
        stats.recordModifier(mods.size());
        if (mods.isEmpty()) {
            throw new IllegalStateException("修饰候选为空");
        }
        s.choose(Math.floorMod(seed / 7L, mods.size()));
        s.confirm();

        List<DraftCard> effects = s.cards();
        stats.recordEffect(effects.size());
        if (effects.isEmpty()) {
            throw new IllegalStateException("效果候选为空");
        }
        s.choose(Math.floorMod(seed / 11L, effects.size()));
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 统计容器。 */
    private static final class Stats {
        int minShape = Integer.MAX_VALUE;
        int maxShape = Integer.MIN_VALUE;
        int minModifier = Integer.MAX_VALUE;
        int maxModifier = Integer.MIN_VALUE;
        int minEffect = Integer.MAX_VALUE;
        int maxEffect = Integer.MIN_VALUE;
        long shapeSum;
        long modifierSum;
        long effectSum;
        int rounds;
        long cardsSum;
        long pointsSum;
        double minHatch = Double.MAX_VALUE;
        double maxHatch = -Double.MAX_VALUE;
        final Map<Rarity, Integer> rarity = new EnumMap<>(Rarity.class);

        void recordShape(int n) {
            minShape = Math.min(minShape, n);
            maxShape = Math.max(maxShape, n);
            shapeSum += n;
        }

        void recordModifier(int n) {
            minModifier = Math.min(minModifier, n);
            maxModifier = Math.max(maxModifier, n);
            modifierSum += n;
        }

        void recordEffect(int n) {
            minEffect = Math.min(minEffect, n);
            maxEffect = Math.max(maxEffect, n);
            effectSum += n;
        }

        void record(Composition c) {
            rounds++;
            cardsSum += c.size();
            pointsSum += c.totalPoints();
            minHatch = Math.min(minHatch, c.hatchMultiplier());
            maxHatch = Math.max(maxHatch, c.hatchMultiplier());
            rarity.merge(c.rarity(), 1, Integer::sum);
        }

        double avg(long sum) {
            return rounds == 0 ? 0 : (double) sum / rounds;
        }

        double avgCards() {
            return avg(cardsSum);
        }

        double avgPoints() {
            return avg(pointsSum);
        }

        double pct(Rarity r) {
            return rounds == 0 ? 0 : 100.0 * rarity.getOrDefault(r, 0) / rounds;
        }

        int distinctRarities() {
            int n = 0;
            for (Rarity r : Rarity.values()) {
                if (rarity.getOrDefault(r, 0) > 0) {
                    n++;
                }
            }
            return n;
        }

        String rarityLine() {
            StringBuilder sb = new StringBuilder();
            for (Rarity r : Rarity.values()) {
                sb.append(r.zh()).append(' ').append(rarity.getOrDefault(r, 0))
                        .append(" (").append(String.format("%.1f%%", pct(r))).append(")  ");
            }
            return sb.toString().trim();
        }
    }

    // ================================================================ 工具

    private static int distinctFamilies(List<DraftCard> cards) {
        List<String> fams = new ArrayList<>();
        for (DraftCard c : cards) {
            if (!fams.contains(c.family())) {
                fams.add(c.family());
            }
        }
        return fams.size();
    }

    private static boolean sameIds(List<DraftCard> a, List<DraftCard> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).affix().id().equals(b.get(i).affix().id())
                    || a.get(i).tier().tier() != b.get(i).tier().tier()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameComposition(Composition a, Composition b) {
        if (a.kind() != b.kind() || a.rarity() != b.rarity() || a.seed() != b.seed()) {
            return false;
        }
        return sameIds(a.cards(), b.cards());
    }

    private static String layerOrder(List<DraftCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (DraftCard card : cards) {
            sb.append(card.affix().layer().zh()).append(' ');
        }
        return sb.toString().trim();
    }

    private static String layerOrder(Composition c) {
        return layerOrder(c.cards());
    }

    private static boolean throwsCode(BuildException.Code code, Runnable action) {
        try {
            action.run();
            return false;
        } catch (BuildException e) {
            return e.code() == code;
        }
    }

    private static boolean throwsBadConfig(Runnable action) {
        return throwsCode(BuildException.Code.BAD_CONFIG, action);
    }

    private static boolean throwsSettle(Runnable action) {
        try {
            action.run();
            return false;
        } catch (SettleException e) {
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

    private static void report(String name, String value) {
        System.out.println("  · " + name + "：" + value);
    }

    private static String read(String file) throws IOException {
        return Files.readString(dataDir.resolve(file), StandardCharsets.UTF_8);
    }
}
