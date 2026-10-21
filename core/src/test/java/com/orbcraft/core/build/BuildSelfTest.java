package com.orbcraft.core.build;

import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.draft.TierData;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        shapeRerollRules();
        shapeBeforeModifier();
        modifierLayerRules();
        modifierDoublePick();
        effectLayerAndFinish();
        elementUnionPool();
        noTradingSurface();
        seedReproducible();
        configValidation();
        terminalRules();
        settleBridge();
        simulate(300);
        rarityDrift();

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

    // ================================================================ 形态层换一批

    /**
     * 换批的三条硬约束：
     * <ol>
     *   <li>只减不增，用尽必报错 —— 否则等于把「无限重开」换了个名字搬回来；</li>
     *   <li>不出现本构筑已发过的载体 —— 否则玩家会认为按钮坏了；</li>
     *   <li>不改变任何数值口径 —— 换批只是重看待选，不是重掷命运。</li>
     * </ol>
     */
    private static void shapeRerollRules() {
        section("形态层换一批");

        check("默认换批次数为 1", config.shapeRerolls() == 1, "" + config.shapeRerolls());
        check("换批次数上限为 3", BuildConfig.MAX_SHAPE_REROLLS == 3, "" + BuildConfig.MAX_SHAPE_REROLLS);
        check("换批次数 4 被拒", throwsBadConfig(() -> BuildConfig.defaults().withShapeRerolls(4)), "");
        check("换批次数 -1 被拒", throwsBadConfig(() -> BuildConfig.defaults().withShapeRerolls(-1)), "");
        check("换批次数 0 合法（关掉换批）",
                BuildConfig.defaults().withShapeRerolls(0).shapeRerolls() == 0, "0");

        // ---- 次数递减与耗尽
        BuildSession s = startShape(BuildConfig.defaults().withShapeRerolls(2), BaseKind.EQUIPMENT, 2024L);
        check("初始余量等于配置值", s.shapeRerollsLeft() == 2, "" + s.shapeRerollsLeft());
        check("首批 5 张载体", s.cards().size() == 5, "" + s.cards().size());
        check("有次数时状态栏提示可换批",
                s.snapshot().statusLine().contains("可换批"), s.snapshot().statusLine());
        check("快照余量与状态机一致", s.snapshot().shapeRerollsLeft() == 2,
                "" + s.snapshot().shapeRerollsLeft());

        List<String> batch1 = idsOf(s.cards());
        s.rerollShape();
        check("换批一次后余量为 1", s.shapeRerollsLeft() == 1, "" + s.shapeRerollsLeft());
        List<String> batch2 = idsOf(s.cards());
        s.rerollShape();
        check("换批两次后余量为 0", s.shapeRerollsLeft() == 0, "" + s.shapeRerollsLeft());
        List<String> batch3 = idsOf(s.cards());
        check("次数用尽后换批抛 REROLL_EXHAUSTED",
                throwsCode(BuildException.Code.REROLL_EXHAUSTED, s::rerollShape), "");
        check("次数用尽后状态栏不再提示换批",
                !s.snapshot().statusLine().contains("可换批"), s.snapshot().statusLine());
        check("次数用尽后快照 canRerollShape 为 false", !s.snapshot().canRerollShape(), "");

        // ---- 排除已见载体（装备 12 个载体，前两批 10 个够用，必然不重叠）
        check("换批后仍是 5 张", batch2.size() == 5 && batch3.size() == 5,
                batch2.size() + "/" + batch3.size());
        check("第二批与第一批没有重叠载体",
                disjoint(batch1, batch2), batch1 + " vs " + batch2);
        check("新批不会等于刚作废的那批",
                !batch2.equals(batch3) && !batch1.equals(batch2), "");

        // 12 个载体 - 前两批 10 个 = 2 个新鲜，第三批必须至少带 1 个没见过的
        Set<String> seenBeforeThird = new LinkedHashSet<>(batch1);
        seenBeforeThird.addAll(batch2);
        boolean freshInThird = false;
        for (String id : batch3) {
            if (!seenBeforeThird.contains(id)) {
                freshInThird = true;
                break;
            }
        }
        check("候选不足时优先回收最久未出现的载体（第三批含新载体）", freshInThird,
                "已见 " + seenBeforeThird.size() + " 个，第三批 " + batch3);

        for (int i = 0; i < 3; i++) {
            List<String> batch = List.of(batch1, batch2, batch3).get(i);
            check("第 " + (i + 1) + " 批载体族互异", distinctStrings(batch) == 5, batch.toString());
        }

        // ---- 换批后形态层选择被清空、阶段不变
        check("换批后回到形态层选择态", s.phase() == BuildPhase.SHAPE_CHOOSING, "" + s.phase());
        check("换批后本层无已选", s.chosen().isEmpty(), s.chosen().toString());
        check("换批不产生已确认卡", s.confirmedCards().isEmpty(), "" + s.confirmedCards().size());
        check("换批不改变本层选取数", s.snapshot().requiredPickCount() == 1,
                "" + s.snapshot().requiredPickCount());

        // ---- 阶段与错误码
        BuildSession selecting = startShape(BaseKind.SUMMON, 31L);
        selecting.choose(1);
        check("已选形态（待确认）时换批被拒 WRONG_PHASE",
                throwsCode(BuildException.Code.WRONG_PHASE, selecting::rerollShape), "" + selecting.phase());
        check("被拒后已选仍在（不做隐式清空）", selecting.chosen().size() == 1, selecting.chosen().toString());
        selecting.confirm();
        check("修饰层换批被拒 WRONG_PHASE",
                throwsCode(BuildException.Code.WRONG_PHASE, selecting::rerollShape), "" + selecting.phase());

        BuildSession typeStage = new BuildSession(table, config, 32L);
        typeStage.choose(0);
        check("类型层换批被拒 WRONG_PHASE",
                throwsCode(BuildException.Code.WRONG_PHASE, typeStage::rerollShape), "" + typeStage.phase());

        BuildSession noReroll = startShape(BuildConfig.defaults().withShapeRerolls(0), BaseKind.SUMMON, 33L);
        check("配置 0 次时首次换批即耗尽",
                throwsCode(BuildException.Code.REROLL_EXHAUSTED, noReroll::rerollShape), "");
        check("耗尽后仍能正常选牌推进", proceedAfterRerollFailure(noReroll), "" + noReroll.phase());

        BuildSession finished = playFixedSession(BaseKind.CONSUMABLE, 34L);
        check("完成后换批被拒 WRONG_PHASE",
                throwsCode(BuildException.Code.WRONG_PHASE, finished::rerollShape), "" + finished.phase());

        // ---- 可复现：同 seed 同换批路径 → 同结果
        BuildSession r1 = startShape(BuildConfig.defaults().withShapeRerolls(1), BaseKind.SUMMON, 777L);
        BuildSession r2 = startShape(BuildConfig.defaults().withShapeRerolls(1), BaseKind.SUMMON, 777L);
        r1.rerollShape();
        r2.rerollShape();
        check("同 seed 换批结果一致", sameIds(r1.cards(), r2.cards()), "");
        BuildSession r3 = startShape(BuildConfig.defaults().withShapeRerolls(1), BaseKind.SUMMON, 778L);
        r3.rerollShape();
        check("不同 seed 换批结果不同", !sameIds(r1.cards(), r3.cards()), "");

        // ---- 品质重新掷：换批后这一批的档位由新一次 roll 决定，与旧批无继承关系
        BuildSession tiers = startShape(BuildConfig.defaults().withShapeRerolls(1), BaseKind.EQUIPMENT, 909L);
        List<Tier> before = qualityOf(tiers.cards());
        tiers.rerollShape();
        check("换批后每张牌都重新掷过品质且点数与档位自洽",
                tiers.cards().size() == 5
                        && tiers.cards().stream().allMatch(c -> c.points() == c.tier().tier().designPoints()),
                before + " → " + qualityOf(tiers.cards()));

        // ---- 换批不改变 Composition 的任何数值口径
        Composition withReroll = playWithReroll(BaseKind.EQUIPMENT, 515L);
        check("换批后产物仍为 3 张", withReroll.size() == 3, "" + withReroll.size());
        check("换批后平均点数 = 总点数 / 张数",
                Math.abs(withReroll.avgPoints() - (double) withReroll.totalPoints() / withReroll.size()) < 1e-9,
                withReroll.totalPoints() + "/" + withReroll.size());
        check("换批后稀有度仍按平均点数判定",
                withReroll.rarity() == config.rarityOf(withReroll.avgPoints()),
                withReroll.rarity() + " avg=" + withReroll.avgPoints());
        check("换批后孵化倍率口径不变",
                Math.abs(withReroll.hatchMultiplier() - expectedHatch(withReroll, config)) < 1e-9,
                withReroll.hatchMultiplier() + " vs " + expectedHatch(withReroll, config));
        report("换批样例", withReroll.brief());

        // 300 局中随机换批，全程不崩、产物张数稳定
        int crash = 0;
        int badDeal = 0;
        for (int i = 0; i < 300; i++) {
            try {
                Composition c = playOneRandomReroll(i);
                if (c.size() != 3) {
                    badDeal++;
                }
            } catch (RuntimeException e) {
                crash++;
            }
        }
        check("300 局随机换批无异常", crash == 0, crash + " 局失败");
        check("300 局随机换批产物张数稳定", badDeal == 0, badDeal + " 局张数异常");

        // 极端回收：消耗品只有 10 个载体，却要发 9 张、换 2 次（共 27 个位置）
        // 这是唯一会让「新批整批撞回旧批」真实发生的配置，专门压它。
        BuildConfig dense = BuildConfig.defaults().withDealCounts(9, 5, 5).withShapeRerolls(2);
        int identical = 0;
        int shortBatch = 0;
        int recycledCrash = 0;
        for (int i = 0; i < 40; i++) {
            try {
                BuildSession d = startShape(dense, BaseKind.CONSUMABLE, 5000L + i);
                List<String> prev = idsOf(d.cards());
                for (int k = 0; k < 2; k++) {
                    d.rerollShape();
                    List<String> now = idsOf(d.cards());
                    if (now.equals(prev)) {
                        identical++;
                    }
                    if (now.size() != 9) {
                        shortBatch++;
                    }
                    prev = now;
                }
            } catch (RuntimeException e) {
                recycledCrash++;
            }
        }
        check("40 局极端回收（10 载体发 9 张换 2 次）无异常", recycledCrash == 0,
                recycledCrash + " 局失败");
        check("极端回收下每批仍发满 9 张", shortBatch == 0, shortBatch + " 批不足 9 张");
        check("极端回收下新批从不与刚作废那批完全相同", identical == 0, identical + " 次重合");
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

    // ================================================================ 修饰层双选

    /**
     * {@code modifierPickCount = 2} 的全流程。
     *
     * <p>默认值必须是 1 —— 双选不是「新标准」，是一个能被配置打开的变体；
     * 改默认值就等于把每个已有存档的产物口径悄悄换掉。
     *
     * <p>双选下最容易错的三处：<b>同族两张</b>（会叠两次同一强化）、
     * <b>类目张数</b>（Composition 应变成 4 张）、<b>稀有度基准</b>
     * （4 张的均值与 3 张的均值不可比，必须仍然按<b>平均</b>点数算，而不是总和）。
     */
    private static void modifierDoublePick() {
        section("修饰层双选（modifierPickCount = 2）");

        BuildConfig pick1 = BuildConfig.defaults();
        BuildConfig pick2 = pick1.withModifierPickCount(2);
        check("默认修饰选取数仍为 1（向后兼容）", pick1.modifierPickCount() == 1, "" + pick1.modifierPickCount());
        check("withModifierPickCount(2) 只改选取数",
                pick2.modifierPickCount() == 2 && pick2.modifierDealCount() == 5
                        && pick2.effectPickCount() == 1 && pick2.shapeRerolls() == pick1.shapeRerolls(),
                pick2.toString());

        BuildSession s = startModifier(pick2, BaseKind.EQUIPMENT, 4242L);
        List<DraftCard> cands = s.cards();
        check("双选：候选仍发 5 张", cands.size() == 5, "" + cands.size());
        check("双选：候选 5 张族互异（同族无从被选中）", distinctFamilies(cands) == 5,
                "" + distinctFamilies(cands));

        s.choose(0);
        check("双选：只选 1 张不可确认", !s.canConfirm(), s.confirmHint());
        check("双选：只选 1 张确认被拒",
                throwsCode(BuildException.Code.NOT_ENOUGH_CHOICES, s::confirm), s.confirmHint());

        s.choose(1);
        check("双选：选满 2 张可确认", s.canConfirm(), s.confirmHint());
        check("双选：已选索引为 {0,1}", s.chosen().equals(List.of(0, 1)), s.chosen().toString());
        check("双选：超选第 3 张被拒",
                throwsCode(BuildException.Code.TOO_MANY_CHOICES, () -> s.choose(2)), "上限 2");
        check("双选：两张候选族不同",
                !cands.get(0).family().equals(cands.get(1).family()),
                cands.get(0).family() + " vs " + cands.get(1).family());

        // 取消再选：两张都取消后回到选择态
        s.choose(1);
        s.choose(0);
        check("双选：全部取消后回到选择态", s.phase() == BuildPhase.MODIFIER_CHOOSING
                && s.chosen().isEmpty(), "" + s.phase() + s.chosen());
        s.choose(0);
        s.choose(1);

        s.confirm();
        check("双选：确认后进入效果层", s.phase() == BuildPhase.EFFECT_CHOOSING, "" + s.phase());
        check("双选：已确认 3 张（形态 + 2 修饰）", s.confirmedCards().size() == 3,
                "" + s.confirmedCards().size());
        check("双选：效果层发 5 张", s.cards().size() == 5, "" + s.cards().size());
        List<String> modFamilies = List.of(cands.get(0).family(), cands.get(1).family());
        check("双选：效果牌族与两张修饰都不同（两族都已进 usedFamilies）",
                s.cards().stream().noneMatch(x -> modFamilies.contains(x.family())),
                layerOrder(s.cards()));
        s.choose(0);
        Composition c = s.confirm().orElseThrow();

        check("双选：组合共 4 张（1 载体 + 2 修饰 + 1 效果）", c.size() == 4, "" + c.size());
        check("双选：载体 1 张", c.carrierCard().isPresent(), "");
        check("双选：修饰 2 张", c.modifiers().size() == 2, "" + c.modifiers().size());
        check("双选：效果 1 张", c.effects().size() == 1, "" + c.effects().size());
        check("双选：两张修饰族不同（无同族叠加）",
                c.modifiers().stream().map(DraftCard::family).distinct().count() == 2,
                c.modifiers().get(0).family() + " vs " + c.modifiers().get(1).family());
        check("双选：卡牌顺序仍为 载体→修饰→效果",
                c.cards().get(0).affix().layer() == Layer.CARRIER
                        && c.cards().get(3).affix().layer() == Layer.EFFECT, layerOrder(c));
        check("双选：平均点数按 4 张算",
                Math.abs(c.avgPoints() - (double) c.totalPoints() / 4) < 1e-9,
                c.totalPoints() + "/4 = " + c.avgPoints());
        check("双选：稀有度按平均点数（不是总和）",
                c.rarity() == pick2.rarityOf(c.avgPoints()),
                c.rarity() + " avg=" + c.avgPoints());
        check("双选：孵化倍率按 4 张计",
                Math.abs(c.hatchMultiplier() - expectedHatch(c, pick2)) < 1e-9,
                c.hatchMultiplier() + " vs " + expectedHatch(c, pick2));
        check("双选：brief 含两张修饰",
                c.brief().split(" / ").length == 4, c.brief());
        check("双选：describe 不崩且列出 4 张卡",
                c.describe().lines().count() == 6, c.describe().replace("\n", " | "));
        check("双选：能正常结算成 Spec（4 张不破坏结算桥）",
                CompositionSettler.settle(c, SettleRequest.of("player-double", MaterialTier.MASTERWORK))
                        .type() == ProductType.EQUIPMENT, "");
        report("双选样例", c.brief());

        // ---- 同族拒绝的判据（直接打在 commit 用的那个校验函数上）
        List<DraftCard> sameFamily = twoCardsOfSameFamily();
        check("同族判据：同族两张 → distinctFamilyCount = 1",
                BuildSession.distinctFamilyCount(sameFamily) == 1, "" + sameFamily);
        check("同族两张被 requireDistinctFamilies 拒绝",
                throwsCode(BuildException.Code.FAMILY_CONFLICT,
                        () -> BuildSession.requireDistinctFamilies(sameFamily)),
                sameFamily.toString());
        check("异族两张不被拒绝", !throwsCode(BuildException.Code.FAMILY_CONFLICT,
                () -> BuildSession.requireDistinctFamilies(cands.subList(0, 2))), "");

        // ---- 配置化：发 3 选 2 也要能走完，且不得出现同族
        BuildConfig small = BuildConfig.defaults().withDealCounts(3, 3, 3).withModifierPickCount(2);
        Composition smallC = playFixed(small, BaseKind.SUMMON, 88L);
        check("发 3 选 2：组合共 4 张", smallC.size() == 4, "" + smallC.size());
        check("发 3 选 2：修饰 2 张且族不同",
                smallC.modifiers().size() == 2
                        && smallC.modifiers().stream().map(DraftCard::family).distinct().count() == 2,
                smallC.brief());
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

    // ================================================================ 双元素效果池

    /**
     * 双元素时效果池取<b>并集</b>（「任一匹配」）。
     *
     * <p>这里用迷你表而不是真实卡表，原因是真实卡表的元素卡只占修饰池的一小块
     * （9 条元素 vs 数百条属性），随机发 5 张几乎撞不到「同手两张元素卡」，
     * 这个分支在真实数据下无法稳定复现 —— 而它恰恰是最容易写错的分支。
     */
    private static void elementUnionPool() {
        section("双元素：效果池取并集（任一匹配）");

        Affix fireOnly = miniAffix("effect.t.burn", "effect.t.burn", Layer.EFFECT, Set.of("fire"));
        Affix anyElement = miniAffix("effect.t.plain", "effect.t.plain", Layer.EFFECT, Set.of());

        check("单元素命中", fireOnly.allowsAnyElement(Set.of("fire")), "");
        check("元素不在白名单内被排除", !fireOnly.allowsAnyElement(Set.of("ice")), "");
        check("集合里任一命中即可", fireOnly.allowsAnyElement(Set.of("ice", "fire")), "");
        check("集合全不命中才排除", !fireOnly.allowsAnyElement(Set.of("ice", "poison")), "");
        check("空集对受限词条也不过滤",
                fireOnly.allowsAnyElement(Set.of()) && anyElement.allowsAnyElement(Set.of()), "");

        // ---- 端到端：同 seed 下点击顺序不影响结果
        boolean orderFree = true;
        String firstMismatch = "";
        for (long seed = 1; seed <= 60; seed++) {
            List<DraftCard> fireFirst = effectCandidates(true, seed);
            List<DraftCard> iceFirst = effectCandidates(false, seed);
            if (!sameIds(fireFirst, iceFirst)) {
                orderFree = false;
                if (firstMismatch.isEmpty()) {
                    firstMismatch = "seed " + seed + "：" + idsOf(fireFirst) + " vs " + idsOf(iceFirst);
                }
            }
        }
        check("同 seed 下元素点击顺序不影响效果候选", orderFree, firstMismatch);

        // ---- 池子是并集，不是交集
        Set<String> union = new LinkedHashSet<>();
        boolean bothInOneHand = false;
        for (long seed = 1; seed <= 60; seed++) {
            List<DraftCard> cards = effectCandidates(true, seed);
            Set<String> ids = new LinkedHashSet<>(idsOf(cards));
            union.addAll(ids);
            if (ids.contains("effect.burn") && ids.contains("effect.freeze")) {
                bothInOneHand = true;
            }
        }
        check("双元素：火系专属效果在池中", union.contains("effect.burn"), union.toString());
        check("双元素：冰系专属效果也在池中（并集，不是交集）",
                union.contains("effect.freeze"), union.toString());
        check("双元素：同一手牌里可以同时出现火系与冰系效果", bothInOneHand, "");

        Set<String> fireAlone = new LinkedHashSet<>();
        for (long seed = 1; seed <= 60; seed++) {
            fireAlone.addAll(idsOf(effectCandidatesSingle("fire", seed)));
        }
        check("单元素火：仍能抽到火系专属效果", fireAlone.contains("effect.burn"), fireAlone.toString());
        check("单元素火：不会混入冰系专属效果", !fireAlone.contains("effect.freeze"), fireAlone.toString());

        // ---- 双元素不缩小池子：候选张数与单元素一致（都是 5 张）
        check("双元素：效果候选仍是 5 张", effectCandidates(true, 7L).size() == 5,
                "" + effectCandidates(true, 7L).size());

        // ---- 修饰选了两张属性卡（无元素）时，效果池不按元素过滤
        Set<String> noElement = new LinkedHashSet<>();
        for (long seed = 1; seed <= 60; seed++) {
            noElement.addAll(idsOf(effectCandidatesWithoutElement(seed)));
        }
        check("无元素时：火系与冰系效果都可出现",
                noElement.contains("effect.burn") && noElement.contains("effect.freeze"),
                noElement.toString());

        // ---- 端到端：点击顺序不同，产物必须完全一致（含 specId）
        boolean sameProduct = true;
        String productDiff = "";
        for (long seed = 1; seed <= 20; seed++) {
            Composition a = doubleElementProduct(true, seed);
            Composition b = doubleElementProduct(false, seed);
            if (!sameComposition(a, b)
                    || !a.describe().equals(b.describe())
                    || !a.brief().equals(b.brief())) {
                sameProduct = false;
                if (productDiff.isEmpty()) {
                    productDiff = "seed " + seed + "：" + a.brief() + " vs " + b.brief();
                }
            }
        }
        check("同 seed 下元素点击顺序不影响最终产物（含卡序与文案）", sameProduct, productDiff);
    }

    /** 迷你表上走到修饰层。{@code modifierPick} 为修饰层选取数。 */
    private static BuildSession miniSession(long seed, int modifierPick) {
        BuildConfig cfg = BuildConfig.defaults()
                .withDealCounts(5, 8, 5)
                .withModifierPickCount(modifierPick);
        BuildSession s = new BuildSession(miniTable(), cfg, seed);
        s.chooseType(BaseKind.EQUIPMENT);
        s.confirm();
        s.choose(0);
        s.confirm();
        return s;
    }

    /**
     * 迷你表上走到效果层，修饰固定选「火 + 冰」两张元素卡。
     *
     * @param fireFirst {@code true} 先点火再点冰；{@code false} 反过来
     */
    private static BuildSession doubleElementSession(boolean fireFirst, long seed) {
        BuildSession s = miniSession(seed, 2);
        List<DraftCard> mods = s.cards();
        int fire = indexOfElement(mods, "fire");
        int ice = indexOfElement(mods, "ice");
        if (fire < 0 || ice < 0) {
            throw new IllegalStateException("迷你表修饰候选里缺元素卡：" + layerOrder(mods));
        }
        if (fireFirst) {
            s.choose(fire);
            s.choose(ice);
        } else {
            s.choose(ice);
            s.choose(fire);
        }
        s.confirm();
        return s;
    }

    private static List<DraftCard> effectCandidates(boolean fireFirst, long seed) {
        return doubleElementSession(fireFirst, seed).cards();
    }

    /** 双元素走到终点：效果层选第 0 张，返回完整产物。 */
    private static Composition doubleElementProduct(boolean fireFirst, long seed) {
        BuildSession s = doubleElementSession(fireFirst, seed);
        s.choose(0);
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 效果层候选：修饰只选 {@code element} 这<b>一张</b>元素卡（单元素对照）。 */
    private static List<DraftCard> effectCandidatesSingle(String element, long seed) {
        BuildSession s = miniSession(seed, 1);
        List<DraftCard> mods = s.cards();
        int idx = indexOfElement(mods, element);
        if (idx < 0) {
            throw new IllegalStateException("迷你表修饰候选里缺 " + element + "：" + layerOrder(mods));
        }
        s.choose(idx);
        s.confirm();
        return s.cards();
    }

    /** 效果层候选：修饰只选两张<b>属性</b>卡，元素集合为空。 */
    private static List<DraftCard> effectCandidatesWithoutElement(long seed) {
        BuildSession s = miniSession(seed, 2);
        List<DraftCard> mods = s.cards();
        int picked = 0;
        for (int i = 0; i < mods.size() && picked < 2; i++) {
            if (mods.get(i).affix().layer() == Layer.ATTRIBUTE) {
                s.choose(i);
                picked++;
            }
        }
        s.confirm();
        return s.cards();
    }

    private static int indexOfElement(List<DraftCard> cards, String element) {
        for (int i = 0; i < cards.size(); i++) {
            if (element.equals(cards.get(i).affix().elementId())) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> idsOf(List<DraftCard> cards) {
        List<String> out = new ArrayList<>(cards.size());
        for (DraftCard c : cards) {
            out.add(c.affix().id());
        }
        return out;
    }

    /** 迷你词条：四档数值齐全，只保留本组测试要验证的过滤字段。 */
    private static Affix miniAffix(String id, String family, Layer layer, Set<String> elementAllow) {
        Map<Tier, TierData> tiers = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) {
            tiers.put(t, new TierData(t, t.designPoints(), 1.0, t.designPoints(), null, "测试词条"));
        }
        return new Affix(id, family, layer, id, "ANY",
                Set.of(), Set.of(), elementAllow, Set.of(), 10, "", tiers);
    }

    /**
     * 迷你卡表：5 载体 + 2 元素(fire/ice) + 6 属性 + 7 效果。
     *
     * <p>效果池刻意做成「火系专属 1 + 冰系专属 1 + 通用 5」：
     * 交集语义会只剩 5 张通用牌，并集语义才有 7 张 —— 两者在候选里能直接看出来。
     */
    private static AffixTable miniTable() {
        List<Affix> carriers = new ArrayList<>();
        for (String c : List.of("gun", "staff", "blade", "bow", "orb")) {
            carriers.add(miniAffix("carrier." + c, "carrier." + c, Layer.CARRIER, Set.of()));
        }
        List<Affix> elements = new ArrayList<>();
        for (String e : List.of("fire", "ice")) {
            elements.add(miniAffix("elem." + e, "elem." + e, Layer.ELEMENT, Set.of()));
        }
        List<Affix> attributes = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            attributes.add(miniAffix("attr.t" + i, "attr.t" + i, Layer.ATTRIBUTE, Set.of()));
        }
        List<Affix> effects = new ArrayList<>();
        effects.add(miniAffix("effect.burn", "effect.burn", Layer.EFFECT, Set.of("fire")));
        effects.add(miniAffix("effect.freeze", "effect.freeze", Layer.EFFECT, Set.of("ice")));
        for (int i = 1; i <= 5; i++) {
            effects.add(miniAffix("effect.plain" + i, "effect.plain" + i, Layer.EFFECT, Set.of()));
        }
        Map<Layer, List<Affix>> byLayer = new EnumMap<>(Layer.class);
        byLayer.put(Layer.CARRIER, carriers);
        byLayer.put(Layer.ELEMENT, elements);
        byLayer.put(Layer.ATTRIBUTE, attributes);
        byLayer.put(Layer.EFFECT, effects);
        return AffixTable.of(byLayer);
    }

    // ================================================================ 无交易面

    private static void noTradingSurface() {
        section("非交易型：没有购买/无限刷新/扣费入口");

        // rerollShape 是唯一一个带 reroll 字样的入口，它是<b>有限次</b>的（次数为 0 时抛
        // REROLL_EXHAUSTED），因此不在「免费刷新」之列；shapeRerollsLeft 只是它的只读余量。
        // 下面单独断言它们的有限性。
        List<String> allowed = List.of("rerollShape", "shapeRerollsLeft");
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
            if (allowed.contains(m.getName())) {
                continue;
            }
            String lower = m.getName().toLowerCase();
            for (String bad : forbidden) {
                if (lower.contains(bad)) {
                    exposed.add(m.getName());
                }
            }
        }
        check("BuildSession 无公开的 deal/购买/免费刷新类方法", exposed.isEmpty(), exposed.toString());
        // 上限从 22 提到 23，是集成阶段唯一一次放宽，且逐条对得上：
        //   + applyTargetPlan  定向卡接线（每天一次配额，必须在发牌前设置）
        //   + targetMissed     一度加过，但已折进 BuildSnapshot，不占接口面
        // 换句话说，「定向卡」这个功能在状态机上只增加了一个入口。
        // 以后再要放宽，必须同样写明加了什么、为什么不能折进快照。
        check("公开方法数量合理（≤ 23）", methods.size() <= 23, "" + methods.size() + " 个：" + methods);

        // rerollShape 必须是有上限的：次数减到 0 后必然拒绝，不会退化成无限刷新
        BuildSession limited = startShape(BuildConfig.defaults().withShapeRerolls(1), BaseKind.EQUIPMENT, 6L);
        limited.rerollShape();
        check("唯一的 reroll 入口是有限次的（用尽即拒）",
                throwsCode(BuildException.Code.REROLL_EXHAUSTED, limited::rerollShape), "");

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

        // 没有任何资源字段：换批次数是计数器而不是货币
        List<String> resourceFields = new ArrayList<>();
        for (var f : BuildSession.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            if (n.contains("point") || n.contains("budget") || n.contains("cost")
                    || n.contains("orb") || n.contains("material")) {
                resourceFields.add(f.getName());
            }
        }
        check("BuildSession 没有点数/预算/材料字段", resourceFields.isEmpty(), resourceFields.toString());

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
                2, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("形态候选 > 9 被拒", throwsBadConfig(() -> new BuildConfig(
                10, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("修饰候选 < 3 被拒", throwsBadConfig(() -> new BuildConfig(
                5, 2, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("修饰选取数 0 被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 0, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("修饰选取数 > 候选数被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 6, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("效果选取数 > 候选数被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 6, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");
        check("稀有度阈值不递增被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 1, null, Tier.WHITE, Tier.WHITE, Tier.WHITE, 5.0, 2.8, 1.6, 0.15, 1)), "");
        check("全零权重被拒", throwsBadConfig(() -> new BuildConfig(
                5, 5, 1, 5, 1, Map.of(Tier.WHITE, 0, Tier.BLUE, 0, Tier.GOLD, 0, Tier.RED, 0),
                Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1)), "");

        // 可配置性实证：三层候选都发 3，修饰选 2，效果选 2
        BuildConfig small = new BuildConfig(3, 3, 2, 3, 2, null,
                Tier.WHITE, Tier.WHITE, Tier.WHITE, 1.6, 2.8, 4.5, 0.15, 1);
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

        Stats stats = simulateConfig(config, roundsPerKind);

        int total = roundsPerKind * BaseKind.values().length;
        check("全部 " + total + " 局无异常", stats.failures == 0,
                stats.failures + " 局失败：" + String.join("; ", stats.errors));
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

    // ================================================================ 双选 vs 单选的分布漂移

    /**
     * 用同一套 seed（900 局）分别跑 {@code modifierPickCount=1} 与 {@code =2}，量出稀有度漂移。
     *
     * <p>为什么必须量而不是「大致合理」：双选让平均点数的分母从 3 变成 4，
     * 而阈值 1.6/2.8/4.5 是固定值。<b>分母变大 → 均值的方差变小 → 分布向中心聚集</b>，
     * 这不是 bug，但它决定了两个配置能不能共用同一套阈值。这里测的就是「能不能」。
     */
    private static void rarityDrift() {
        section("修饰单选 / 双选 各 900 局的稀有度漂移");

        Stats one = simulateConfig(BuildConfig.defaults(), 300);
        Stats two = simulateConfig(BuildConfig.defaults().withModifierPickCount(2), 300);

        check("单选 900 局无异常", one.failures == 0, one.failures + " 局失败：" + one.errors);
        check("双选 900 局无异常", two.failures == 0, two.failures + " 局失败：" + two.errors);
        check("单选 900 局卡数恒为 3", one.minCards == 3 && one.maxCards == 3,
                one.minCards + "~" + one.maxCards);
        check("双选 900 局卡数恒为 4", two.minCards == 4 && two.maxCards == 4,
                two.minCards + "~" + two.maxCards);
        check("双选 900 局四档稀有度都有产出", two.distinctRarities() == 4, two.rarityLine());
        check("双选 900 局没有出现同族两张", two.sameFamilyDupes == 0, two.sameFamilyDupes + " 局");
        check("单选 900 局没有出现同族两张", one.sameFamilyDupes == 0, one.sameFamilyDupes + " 局");
        check("双选 900 局孵化倍率下限正好高 0.15（多一张卡的额外百分比）",
                Math.abs(two.minHatch - (one.minHatch + 0.15)) < 1e-9,
                String.format("%.2f vs %.2f + 0.15", two.minHatch, one.minHatch));
        check("双选 900 局平均孵化倍率高于单选", two.avgHatch() > one.avgHatch(),
                String.format("%.2f vs %.2f", two.avgHatch(), one.avgHatch()));

        report("单选 pick=1 分布", one.rarityLine());
        report("双选 pick=2 分布", two.rarityLine());
        StringBuilder drift = new StringBuilder();
        for (Rarity r : Rarity.values()) {
            drift.append(r.zh()).append(' ').append(signed(one.pct(r))).append(" → ")
                    .append(signed(two.pct(r))).append(" 点  ");
        }
        report("漂移（pick=1 → pick=2，百分点）", drift.toString().trim());
        report("平均点数", String.format("%.2f → %.2f", one.avgPoints(), two.avgPoints()));
        report("孵化倍率均值", String.format("%.2f → %.2f", one.avgHatch(), two.avgHatch()));
    }

    private static String signed(double v) {
        return String.format("%+.1f", v);
    }

    // ================================================================ 玩法辅助

    /** 从类型层走到形态层。 */
    private static BuildSession startShape(BaseKind kind, long seed) {
        return startShape(config, kind, seed);
    }

    private static BuildSession startShape(BuildConfig cfg, BaseKind kind, long seed) {
        BuildSession s = new BuildSession(table, cfg, seed);
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

    private static BuildSession startModifier(BuildConfig cfg, BaseKind kind, long seed) {
        BuildSession s = startShape(cfg, kind, seed);
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

    /** 固定策略 + 可配置选取数：每层按配置的选取数依次点前 k 张。 */
    private static Composition playFixed(BuildConfig cfg, BaseKind kind, long seed) {
        BuildSession s = new BuildSession(table, cfg, seed);
        s.chooseType(kind);
        s.confirm();
        s.choose(0);
        s.confirm();
        for (int k = 0; k < cfg.modifierPickCount(); k++) {
            s.choose(k);
        }
        s.confirm();
        for (int k = 0; k < cfg.effectPickCount(); k++) {
            s.choose(k);
        }
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 形态层先换一批，再按固定策略走完全程。 */
    private static Composition playWithReroll(BaseKind kind, long seed) {
        BuildConfig cfg = BuildConfig.defaults().withShapeRerolls(1);
        BuildSession s = new BuildSession(table, cfg, seed);
        s.chooseType(kind);
        s.confirm();
        s.rerollShape();
        s.choose(0);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 按 seed 决定换 0~3 批，然后走完全程。 */
    private static Composition playOneRandomReroll(long seed) {
        BuildConfig cfg = BuildConfig.defaults().withShapeRerolls(3);
        BuildSession s = new BuildSession(table, cfg, seed);
        s.chooseType(BaseKind.values()[Math.floorMod((int) seed, BaseKind.values().length)]);
        s.confirm();
        int rerolls = (int) Math.floorMod(seed, 4L);
        for (int i = 0; i < rerolls; i++) {
            s.rerollShape();
        }
        s.choose(Math.floorMod(seed, s.cards().size()));
        s.confirm();
        s.choose(Math.floorMod(seed / 7L, s.cards().size()));
        s.confirm();
        s.choose(Math.floorMod(seed / 11L, s.cards().size()));
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 换批失败（次数用尽）后，玩家仍然能正常选牌走完全程。 */
    private static boolean proceedAfterRerollFailure(BuildSession s) {
        s.choose(0);
        s.confirm();
        s.choose(0);
        s.confirm();
        s.choose(0);
        return s.confirm().isPresent() && s.phase() == BuildPhase.FINISHED;
    }

    /** 按 seed 变化选择索引，模拟不同玩家的偏好。 */
    private static Composition playOne(BuildConfig cfg, BaseKind kind, long seed, Stats stats) {
        BuildSession s = new BuildSession(table, cfg, seed);
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
        for (int k = 0; k < cfg.modifierPickCount(); k++) {
            s.choose(Math.floorMod(seed / 7L + k, mods.size()));
        }
        s.confirm();

        List<DraftCard> effects = s.cards();
        stats.recordEffect(effects.size());
        if (effects.isEmpty()) {
            throw new IllegalStateException("效果候选为空");
        }
        for (int k = 0; k < cfg.effectPickCount(); k++) {
            s.choose(Math.floorMod(seed / 11L + k, effects.size()));
        }
        return s.confirm().orElseThrow(() -> new IllegalStateException("未产出组合"));
    }

    /** 用一套配置跑满 3 类 × roundsPerKind 局，返回统计。 */
    private static Stats simulateConfig(BuildConfig cfg, int roundsPerKind) {
        Stats stats = new Stats();
        for (BaseKind kind : BaseKind.values()) {
            for (int i = 0; i < roundsPerKind; i++) {
                long seed = 1000L * (kind.ordinal() + 1) + i;
                try {
                    stats.record(playOne(cfg, kind, seed, stats));
                } catch (RuntimeException e) {
                    stats.fail(kind, seed, e);
                }
            }
        }
        return stats;
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
        int minCards = Integer.MAX_VALUE;
        int maxCards = Integer.MIN_VALUE;
        long cardsSum;
        long pointsSum;
        double minHatch = Double.MAX_VALUE;
        double maxHatch = -Double.MAX_VALUE;
        double hatchSum;
        int sameFamilyDupes;
        int failures;
        final List<String> errors = new ArrayList<>();
        final Map<Rarity, Integer> rarity = new EnumMap<>(Rarity.class);

        void fail(BaseKind kind, long seed, RuntimeException e) {
            failures++;
            if (errors.size() < 5) {
                errors.add(kind.zh() + " seed=" + seed + " → " + e);
            }
        }

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
            minCards = Math.min(minCards, c.size());
            maxCards = Math.max(maxCards, c.size());
            cardsSum += c.size();
            pointsSum += c.totalPoints();
            hatchSum += c.hatchMultiplier();
            minHatch = Math.min(minHatch, c.hatchMultiplier());
            maxHatch = Math.max(maxHatch, c.hatchMultiplier());
            if (c.modifiers().stream().map(DraftCard::family).distinct().count() != c.modifiers().size()) {
                sameFamilyDupes++;
            }
            rarity.merge(c.rarity(), 1, Integer::sum);
        }

        double avgHatch() {
            return rounds == 0 ? 0 : hatchSum / rounds;
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

    /** 卡牌的词条 id 序列（顺序保留，用于验证「这一批」的身份）。 */
    private static boolean disjoint(List<String> a, List<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return false;
            }
        }
        return true;
    }

    private static int distinctStrings(List<String> ids) {
        return new LinkedHashSet<>(ids).size();
    }

    private static List<Tier> qualityOf(List<DraftCard> cards) {
        List<Tier> out = new ArrayList<>(cards.size());
        for (DraftCard c : cards) {
            out.add(c.tier().tier());
        }
        return out;
    }

    /**
     * 独立复算孵化倍率，不抄 {@code BuildSession} 的公式实现。
     *
     * <p>口径：1.0 + 所有「延时」类代价 + 每多一张卡的额外百分比。
     * 写成独立算式而不是调被测代码，才能真正验证「换批 / 双选没有改变口径」。
     */
    private static double expectedHatch(Composition c, BuildConfig cfg) {
        double m = 1.0;
        for (DraftCard card : c.cards()) {
            TierData.Drawback d = card.tier().drawback();
            if (d != null && d.isHatchTime()) {
                m += d.value();
            }
        }
        return m + cfg.extraCardHatchPct() * (c.size() - 1);
    }

    /** 从属性层里找两张同族的卡，用来验证「同族两张」的判据。 */
    private static List<DraftCard> twoCardsOfSameFamily() {
        Map<String, List<DraftCard>> byFamily = new java.util.LinkedHashMap<>();
        for (Layer layer : List.of(Layer.ELEMENT, Layer.ATTRIBUTE)) {
            for (var a : table.layer(layer)) {
                TierData data = a.tierData(Tier.WHITE);
                if (data == null) {
                    continue;
                }
                List<DraftCard> list = byFamily.computeIfAbsent(a.family(), k -> new ArrayList<>());
                if (list.size() < 2) {
                    list.add(new DraftCard(a, data));
                }
            }
        }
        for (List<DraftCard> list : byFamily.values()) {
            if (list.size() == 2) {
                return List.copyOf(list);
            }
        }
        throw new IllegalStateException("卡表里找不到同族的两张修饰卡");
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
