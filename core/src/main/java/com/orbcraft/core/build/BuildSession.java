package com.orbcraft.core.build;

import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.SeedRandom;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.draft.TierData;
import com.orbcraft.core.spec.Rarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 随机构筑状态机 —— 四层确认制，无交易、无点数、无扣费。
 *
 * <h2>一次完整的构筑</h2>
 * <pre>
 * new BuildSession(table, config, seed)   → TYPE_CHOOSING
 * typeOptions()                           → [装备, 召唤物, 消耗品]（带可用性）
 * choose(0)                               → TYPE_CONFIRMING（只是登记，未推进）
 * confirm()                               → 发 5 张形态牌 → SHAPE_CHOOSING
 * choose(2)                               → SHAPE_CONFIRMING
 * confirm()                               → 用已确认形态过滤修饰池 → 发 5 张 → MODIFIER_CHOOSING
 * choose(4)                               → MODIFIER_CONFIRMING
 * confirm()                               → 发 5 张效果牌 → EFFECT_CHOOSING
 * choose(1)                               → EFFECT_CONFIRMING
 * confirm()                               → FINISHED，返回 Composition
 * </pre>
 *
 * <h2>五条被刻意设计成「绕不过去」的约束</h2>
 *
 * <p><b>① 没有公开的 deal()。</b> 发牌只发生在 {@link #confirm()} 内部。
 * 旧流程里 {@code DraftSession.deal()} 是 public 的，调用方可以连发五次牌再挑最好的 ——
 * 那就是把「随机」变成了「刷新到满意为止」。这里拿掉的不是一个方法，
 * 是一条免费的保底通道。
 *
 * <p><b>② choose() 只登记，confirm() 才推进。</b>
 * 需求里「任一层级玩家未确认前不可推进」在代码层面的落点就是这句：
 * 唯一的阶段跃迁语句全部写在 {@code confirm()} 里，别处没有 {@code phase =} 赋新层。
 *
 * <p><b>③ 形态只有一个，且在结构上锁定。</b> {@code shapePickCount} 恒为 1。
 * 一件产物只有一张外观 key、一套动画、一个碰撞箱，选 2 张形态在渲染层没有正确答案。
 *
 * <p><b>④ 修饰牌一定晚于形态。</b> 修饰词的 {@code carrier_allow} 相对载体判定，
 * 所以 {@code dealModifier()} 只在 {@code commitShape()} 之后调用，
 * 过滤用的是<b>已确认</b>的载体 id。混层发牌会让玩家拿到一半不适用的修饰牌。
 *
 * <p><b>⑤ 没有任何资源字段。</b> 没有 points、没有 budget、没有 reroll 消耗。
 * 玩家能选什么只取决于抽到了什么。想变强只能多抽，不能多花。
 *
 * <p>本类不是线程安全的，一个 session 只属于一个玩家的一次构筑。
 */
public final class BuildSession {

    /** 类型层的展示顺序：装备 → 召唤物 → 消耗品。 */
    private static final BaseKind[] TYPE_ORDER = {
            BaseKind.EQUIPMENT, BaseKind.SUMMON, BaseKind.CONSUMABLE
    };

    private final AffixTable table;
    private final BuildConfig config;
    private final SeedRandom rng;
    private final int[] weights;
    /**
     * 三类各自的池子容量，构造期算一次。
     *
     * <p>修饰池是<b>按载体</b>过滤的，所以「这个类型能不能造」不能只看词条总数，
     * 要看「所有形态里最窄的那个」够不够。这一步放在构造期，是因为
     * {@code typeOptions()} 会被 UI 每帧调用，不能每次都遍历 1124 条。
     */
    private final EnumMap<BaseKind, Pool> pools = new EnumMap<>(BaseKind.class);

    private BuildPhase phase = BuildPhase.TYPE_CHOOSING;
    private BaseKind kind;
    /** 类型层选中的索引，-1 表示未选。 */
    private int typeChosen = -1;

    private List<DraftCard> shapeCards = List.of();
    private List<DraftCard> modifierCards = List.of();
    private List<DraftCard> effectCards = List.of();

    private final Set<Integer> shapeChosen = new LinkedHashSet<>();
    private final Set<Integer> modifierChosen = new LinkedHashSet<>();
    private final Set<Integer> effectChosen = new LinkedHashSet<>();

    private final List<DraftCard> shapePicked = new ArrayList<>();
    private final List<DraftCard> modifierPicked = new ArrayList<>();
    private final List<DraftCard> effectPicked = new ArrayList<>();
    private final Set<String> usedFamilies = new LinkedHashSet<>();

    private Composition composition;

    public BuildSession(AffixTable table, BuildConfig config, long seed) {
        this.table = Objects.requireNonNull(table, "table");
        this.config = config == null ? BuildConfig.defaults() : config;
        this.rng = new SeedRandom(seed);
        this.weights = new int[Tier.values().length];
        for (Tier t : Tier.values()) {
            weights[t.rank()] = this.config.weightOf(t);
        }
        for (BaseKind k : BaseKind.values()) {
            pools.put(k, scanPool(k));
        }
    }

    public static BuildSession create(AffixTable table, long seed) {
        return new BuildSession(table, BuildConfig.defaults(), seed);
    }

    public long seed() {
        return rng.seed();
    }

    public BuildConfig config() {
        return config;
    }

    public BuildPhase phase() {
        return phase;
    }

    public Optional<BaseKind> kind() {
        return Optional.ofNullable(kind);
    }

    // ---------------------------------------------------------------- 类型层

    /**
     * 类型层的三个候选项及其可用性。
     *
     * <p>顺序固定为<b>装备 → 召唤物 → 消耗品</b>，与 {@link BaseKind} 的声明顺序
     * （装备 / 消耗品 / 召唤物）不同 —— 不跟 enum 走，是因为枚举顺序是历史产物，
     * 而这一屏是玩家每天都会点的位置，顺序跟着玩法需求走：
     * 装备是使用频率最高的一类，放第一格能减少误点。
     *
     * <p>可用性是<b>三层联合判定</b>：形态够、每个形态下的修饰够、效果够。
     * 只判一层会造出「选了装备，走完形态才发现修饰池是空的」这种死路。
     */
    public List<TypeOption> typeOptions() {
        List<TypeOption> out = new ArrayList<>(TYPE_ORDER.length);
        for (BaseKind k : TYPE_ORDER) {
            out.add(optionOf(k, pools.get(k)));
        }
        return List.copyOf(out);
    }

    /** 类型在候选列表里的下标。UI 与网络包都应该用它，而不是 {@code ordinal()}。 */
    public static int indexOfType(BaseKind kind) {
        for (int i = 0; i < TYPE_ORDER.length; i++) {
            if (TYPE_ORDER[i] == kind) {
                return i;
            }
        }
        throw new IllegalArgumentException("未知类型：" + kind);
    }

    /**
     * 按类型选择，等价于 {@code choose(indexOfType(kind))}。
     *
     * <p>提供这个重载是因为：客户端如果按下标发请求，一旦将来调整展示顺序，
     * 玩家点「装备」就会开出召唤物。按类型名发，顺序怎么排都不影响正确性。
     */
    public void chooseType(BaseKind kind) {
        choose(indexOfType(kind));
    }

    private TypeOption optionOf(BaseKind k, Pool pool) {
        if (pool.shape() < config.shapePickCount()) {
            return new TypeOption(k, false, 0,
                    "「" + k.zh() + "」在词库里没有可用形态（载体）");
        }
        if (pool.minModifier() < config.modifierPickCount()) {
            return new TypeOption(k, false, 0,
                    "「" + k.zh() + "」最窄的形态下只有 " + pool.minModifier()
                            + " 条可用修饰，本层至少需要 " + config.modifierPickCount() + " 条");
        }
        if (pool.effect() < config.effectPickCount()) {
            return new TypeOption(k, false, 0,
                    "「" + k.zh() + "」可用效果只有 " + pool.effect()
                            + " 条，本层至少需要 " + config.effectPickCount() + " 条");
        }
        return new TypeOption(k, true, pool.shape() + pool.minModifier() + pool.effect(), "");
    }

    /** 某一类在真实卡表下的三层池子容量。 */
    private Pool scanPool(BaseKind k) {
        int shape = 0;
        int minModifier = Integer.MAX_VALUE;
        for (Affix a : table.layer(Layer.CARRIER)) {
            if (!a.allowsBaseType(k.wire())) {
                continue;
            }
            shape++;
            String carrier = a.carrierId();
            Set<String> families = new HashSet<>();
            for (Layer l : List.of(Layer.ELEMENT, Layer.ATTRIBUTE)) {
                for (Affix m : table.layer(l)) {
                    if (m.allowsBaseType(k.wire()) && m.allowsCarrier(carrier)) {
                        families.add(m.family());
                    }
                }
            }
            minModifier = Math.min(minModifier, families.size());
        }
        if (shape == 0) {
            minModifier = 0;
        }
        Set<String> effectFamilies = new HashSet<>();
        for (Affix a : table.layer(Layer.EFFECT)) {
            if (a.allowsBaseType(k.wire())) {
                effectFamilies.add(a.family());
            }
        }
        return new Pool(shape, minModifier == Integer.MAX_VALUE ? 0 : minModifier, effectFamilies.size());
    }

    private record Pool(int shape, int minModifier, int effect) {
    }

    // ---------------------------------------------------------------- 统一选择

    /**
     * 在当前层点选第 {@code index} 张候选。
     *
     * <p>只登记，<b>不推进</b>。再次点同一张会取消选择（回到「未选」）。
     * 数量达到本层上限后再点新的，会抛 {@code TOO_MANY_CHOICES} 而不是悄悄替换 ——
     * 悄悄替换会让玩家以为自己选的还在。
     */
    public void choose(int index) {
        requireBuilding("选择");
        switch (phase) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> chooseType(index);
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> phase = toggle(
                    shapeChosen, shapeCards, index, config.shapePickCount(),
                    "形态层", BuildPhase.SHAPE_CHOOSING, BuildPhase.SHAPE_CONFIRMING);
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> phase = toggle(
                    modifierChosen, modifierCards, index, config.modifierPickCount(),
                    "修饰层", BuildPhase.MODIFIER_CHOOSING, BuildPhase.MODIFIER_CONFIRMING);
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> phase = toggle(
                    effectChosen, effectCards, index, config.effectPickCount(),
                    "效果层", BuildPhase.EFFECT_CHOOSING, BuildPhase.EFFECT_CONFIRMING);
            default -> throw new BuildException(BuildException.Code.WRONG_PHASE,
                    "当前阶段不能选择：" + phase);
        }
    }

    /**
     * 单层选择/取消的公共逻辑。
     *
     * @return 选择后应处于的阶段（选空回到 choosing，否则 confirming）
     */
    private BuildPhase toggle(Set<Integer> chosen, List<DraftCard> cards, int index, int maxPick,
                              String layerZh, BuildPhase choosing, BuildPhase confirming) {
        if (index < 0 || index >= cards.size()) {
            throw new BuildException(BuildException.Code.INDEX_OUT_OF_RANGE,
                    layerZh + "索引 " + index + " 越界（本层共 " + cards.size() + " 张）");
        }
        if (chosen.remove(index)) {
            return chosen.isEmpty() ? choosing : confirming;
        }
        if (chosen.size() >= maxPick) {
            throw new BuildException(BuildException.Code.TOO_MANY_CHOICES,
                    layerZh + "最多选 " + maxPick + " 张，已选满；先取消一张再选");
        }
        chosen.add(index);
        return confirming;
    }

    private void chooseType(int index) {
        List<TypeOption> options = typeOptions();
        if (index < 0 || index >= options.size()) {
            throw new BuildException(BuildException.Code.INDEX_OUT_OF_RANGE,
                    "类型索引 " + index + " 越界（共 " + options.size() + " 项）");
        }
        TypeOption opt = options.get(index);
        if (!opt.available()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "「" + opt.title() + "」当前不可选：" + opt.reason());
        }
        if (typeChosen == index) {
            // 再点一次 = 取消，回到未选
            typeChosen = -1;
            kind = null;
            phase = BuildPhase.TYPE_CHOOSING;
            return;
        }
        typeChosen = index;
        kind = opt.kind();
        phase = BuildPhase.TYPE_CONFIRMING;
    }

    /** 清空本层已选，回到「未选」。已确认的层不受影响。 */
    public void clearChoices() {
        requireBuilding("清空选择");
        switch (phase) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> {
                typeChosen = -1;
                kind = null;
                phase = BuildPhase.TYPE_CHOOSING;
            }
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> {
                shapeChosen.clear();
                phase = BuildPhase.SHAPE_CHOOSING;
            }
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> {
                modifierChosen.clear();
                phase = BuildPhase.MODIFIER_CHOOSING;
            }
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> {
                effectChosen.clear();
                phase = BuildPhase.EFFECT_CHOOSING;
            }
            default -> throw new BuildException(BuildException.Code.WRONG_PHASE,
                    "当前阶段不能清空选择：" + phase);
        }
    }

    // ---------------------------------------------------------------- 确认推进

    /**
     * 确认本层并推进。这是<b>唯一的</b>阶段跃迁入口。
     *
     * <p>没选就点确认会抛 {@code NO_PENDING_CHOICE} —— 不是静默忽略。
     * 静默忽略会让 UI 表现成「点了没反应」，而玩家会继续点，最后点出两三次请求。
     *
     * @return 最后一层确认后返回最终组合；中间层返回 {@link Optional#empty()}
     */
    public Optional<Composition> confirm() {
        requireBuilding("确认");
        switch (phase) {
            case TYPE_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择类型，无法确认");
            case SHAPE_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择形态，无法确认");
            case MODIFIER_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择修饰，无法确认");
            case EFFECT_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择效果，无法确认");
            case TYPE_CONFIRMING -> {
                dealShape();
                phase = BuildPhase.SHAPE_CHOOSING;
                return Optional.empty();
            }
            case SHAPE_CONFIRMING -> {
                commitShape();
                // 修饰池依赖已确认的形态 —— 这一句的顺序就是「先形态，再修饰」的全部实现
                dealModifier();
                phase = BuildPhase.MODIFIER_CHOOSING;
                return Optional.empty();
            }
            case MODIFIER_CONFIRMING -> {
                commitModifier();
                dealEffect();
                phase = BuildPhase.EFFECT_CHOOSING;
                return Optional.empty();
            }
            case EFFECT_CONFIRMING -> {
                commitEffect();
                composition = buildComposition();
                phase = BuildPhase.FINISHED;
                shapeCards = List.of();
                modifierCards = List.of();
                effectCards = List.of();
                return Optional.of(composition);
            }
            default -> throw new BuildException(BuildException.Code.WRONG_PHASE,
                    "流程已结束：" + phase);
        }
    }

    /** 确认前检查本层是否已满足条件。UI 用它决定确认按钮是否可点。 */
    public boolean canConfirm() {
        return switch (phase) {
            case TYPE_CONFIRMING -> true;
            case SHAPE_CONFIRMING -> shapeChosen.size() == config.shapePickCount();
            case MODIFIER_CONFIRMING -> modifierChosen.size() == config.modifierPickCount();
            case EFFECT_CONFIRMING -> effectChosen.size() == config.effectPickCount();
            default -> false;
        };
    }

    /** 还差什么才能确认；已满足返回空串。 */
    public String confirmHint() {
        return switch (phase) {
            case TYPE_CHOOSING -> "先选一个类型";
            case SHAPE_CHOOSING -> "请选择 1 个形态";
            case MODIFIER_CHOOSING -> "请选择 " + config.modifierPickCount() + " 张修饰";
            case EFFECT_CHOOSING -> "请选择 " + config.effectPickCount() + " 张效果";
            case TYPE_CONFIRMING -> canConfirm() ? "" : "类型未选定";
            case SHAPE_CONFIRMING -> shapeChosen.isEmpty() ? "形态未选定" : "";
            case MODIFIER_CONFIRMING -> {
                int need = config.modifierPickCount() - modifierChosen.size();
                yield need > 0 ? "还差 " + need + " 张修饰" : "";
            }
            case EFFECT_CONFIRMING -> {
                int need = config.effectPickCount() - effectChosen.size();
                yield need > 0 ? "还差 " + need + " 张效果" : "";
            }
            default -> "";
        };
    }

    private void commitShape() {
        if (shapeChosen.size() != config.shapePickCount()) {
            throw new BuildException(BuildException.Code.NOT_ENOUGH_CHOICES,
                    "形态层需要选 " + config.shapePickCount() + " 张，当前选了 " + shapeChosen.size() + " 张");
        }
        int index = shapeChosen.iterator().next();
        DraftCard card = shapeCards.get(index);
        if (card.affix().layer() != Layer.CARRIER) {
            // 形态层的池子只放载体，走到这里说明卡表或过滤逻辑被改坏了
            throw new BuildException(BuildException.Code.CARRIER_REQUIRED,
                    "形态层选中的不是载体卡：" + card.affix().id());
        }
        shapePicked.add(card);
        usedFamilies.add(card.family());
        shapeChosen.clear();
    }

    private void commitModifier() {
        if (modifierChosen.size() != config.modifierPickCount()) {
            throw new BuildException(BuildException.Code.NOT_ENOUGH_CHOICES,
                    "修饰层需要选 " + config.modifierPickCount() + " 张，当前选了 "
                            + modifierChosen.size() + " 张");
        }
        for (int i : modifierChosen) {
            DraftCard card = modifierCards.get(i);
            modifierPicked.add(card);
            usedFamilies.add(card.family());
        }
        modifierChosen.clear();
    }

    private void commitEffect() {
        if (effectChosen.size() != config.effectPickCount()) {
            throw new BuildException(BuildException.Code.NOT_ENOUGH_CHOICES,
                    "效果层需要选 " + config.effectPickCount() + " 张，当前选了 " + effectChosen.size() + " 张");
        }
        for (int i : effectChosen) {
            effectPicked.add(effectCards.get(i));
        }
        effectChosen.clear();
    }

    /** 放弃本次构筑。不消耗任何资源 —— 因为本来就没有资源可消耗。 */
    public void cancel() {
        if (phase.isTerminal()) {
            throw new BuildException(BuildException.Code.WRONG_PHASE, "流程已经结束：" + phase);
        }
        phase = BuildPhase.CANCELLED;
        shapeCards = List.of();
        modifierCards = List.of();
        effectCards = List.of();
    }

    // ---------------------------------------------------------------- 发牌

    /**
     * 形态层发牌：只发载体，不带任何修饰。
     *
     * <p>与旧流程的关键差别：以前载体和修饰混在 5 张里一起发。那样做的代价是，
     * 修饰只能按「候选里那张载体」过滤 —— 玩家若最终选了另一张（旧流程允许 1 张载体，
     * 所以不会发生，但一旦载体位放开到 2 就会），手里一半修饰牌是不适用的。
     * 拆层后这个问题在结构上不存在：修饰池只在形态确认后才被过滤。
     */
    private void dealShape() {
        List<Affix> pool = pool(Layer.CARRIER, null, null);
        if (pool.size() < config.shapePickCount()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "「" + kind.zh() + "」可用形态只有 " + pool.size()
                            + " 个，本层需要 " + config.shapePickCount() + " 个");
        }
        shuffle(pool);
        shapeCards = List.copyOf(toCards(takeDistinct(pool, config.shapeDealCount()), config.shapeFloor()));
    }

    /**
     * 修饰层发牌：用<b>已确认</b>的形态过滤元素 + 属性池。
     *
     * <p>元素卡与属性卡合在一层，是因为两者在玩法上是同一件事 ——
     * 「这件东西多了点什么」。分开就得多一层确认，而玩家在 5 张里挑 1 张时
     * 并不会因为「这张是元素、那张是属性」而做出不同的决策。
     */
    private void dealModifier() {
        String carrier = confirmedCarrier();
        if (carrier == null) {
            throw new BuildException(BuildException.Code.CARRIER_REQUIRED,
                    "形态尚未确认，无法生成修饰牌");
        }
        List<Affix> pool = new ArrayList<>(pool(Layer.ELEMENT, carrier, null));
        pool.addAll(pool(Layer.ATTRIBUTE, carrier, null));

        if (pool.size() < config.modifierPickCount()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "载体 " + carrier + " 下只有 " + pool.size() + " 条可用修饰，本层至少需要 "
                            + config.modifierPickCount() + " 张");
        }
        shuffle(pool);
        modifierCards = List.copyOf(toCards(
                takeDistinct(pool, config.modifierDealCount()), config.modifierFloor()));
    }

    /** 效果层发牌：按已确定的类型 / 载体 / 元素过滤效果池。 */
    private void dealEffect() {
        String carrier = confirmedCarrier();
        String element = confirmedElement();

        List<Affix> pool = pool(Layer.EFFECT, carrier, element);
        if (pool.size() < config.effectPickCount()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "载体 " + carrier + " / 元素 " + element + " 下只有 " + pool.size()
                            + " 条可用效果，本层需要至少 " + config.effectPickCount() + " 张");
        }
        shuffle(pool);
        effectCards = List.copyOf(toCards(
                takeDistinct(pool, config.effectDealCount()), config.effectFloor()));
    }

    /**
     * 按「族」去重取前 {@code want} 条。
     *
     * <p>为什么需要它：元素特化族（{@code attr.elemResist} 之类）一个族在本载体上
     * 会展开成 9 条（9 个元素）。它们的 {@code family} 相同，如果直接取前 5 条，
     * 玩家可能看到「火焰抗性 / 冰霜抗性 / 雷抗性 / 毒抗性 / 光抗性」——
     * 五张牌其实是同一个东西换了元素。族去重保证 5 张是 5 种<b>不同</b>的强化方向。
     *
     * <p>实测最窄的载体也有 19 个修饰族，所以去重不会让候选凑不满 5 张。
     */
    private static List<Affix> takeDistinct(List<Affix> shuffled, int want) {
        int limit = Math.min(want, shuffled.size());
        List<Affix> out = new ArrayList<>(limit);
        Set<String> families = new HashSet<>();
        for (Affix a : shuffled) {
            if (out.size() >= limit) {
                break;
            }
            if (families.add(a.family())) {
                out.add(a);
            }
        }
        return out;
    }

    private List<DraftCard> toCards(List<Affix> affixes, Tier floor) {
        List<DraftCard> cards = new ArrayList<>(affixes.size());
        for (Affix a : affixes) {
            cards.add(new DraftCard(a, rollTier(a, floor)));
        }
        return cards;
    }

    /**
     * 过滤候选池。
     *
     * <p>四道关，缺任何一道都会让玩家抽到读不懂的选项：
     * 类型不匹配、族已用过、载体不允许、元素不允许、族互斥。
     */
    private List<Affix> pool(Layer layer, String carrierId, String elementId) {
        List<Affix> out = new ArrayList<>();
        for (Affix a : table.layer(layer)) {
            if (!a.allowsBaseType(kind.wire())) {
                continue;
            }
            if (usedFamilies.contains(a.family())) {
                continue;
            }
            if (layer != Layer.CARRIER) {
                if (carrierId != null && !a.allowsCarrier(carrierId)) {
                    continue;
                }
                if (elementId != null && !a.allowsElement(elementId)) {
                    continue;
                }
                if (a.conflictsWith(usedFamilies)) {
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }

    /**
     * 掷品质。先掷后查，<b>绝对不能</b>写成 {@code tiers.find(...)} 的回调里调随机 ——
     * 那样每比较一次就重掷一次，红卡率会从 8% 掉到 2.6%（这个坑已经踩过一次）。
     */
    private TierData rollTier(Affix affix, Tier floor) {
        Tier rolled = rng.pickTier(weights);
        if (floor != null && rolled.rank() < floor.rank()) {
            rolled = floor;
        }
        TierData data = affix.tierData(rolled);
        for (int i = rolled.rank(); i >= 0 && data == null; i--) {
            data = affix.tierData(Tier.values()[i]);
        }
        if (data == null) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "词条 " + affix.id() + " 没有任何品质档数据");
        }
        return data;
    }

    // ---------------------------------------------------------------- 结果

    /** 已完成则返回最终组合。 */
    public Optional<Composition> composition() {
        return Optional.ofNullable(composition);
    }

    /** 当前层的候选卡；类型层返回空列表。 */
    public List<DraftCard> cards() {
        return switch (phase) {
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> shapeCards;
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> modifierCards;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> effectCards;
            default -> List.of();
        };
    }

    /** 当前层已选中的索引，升序。 */
    public List<Integer> chosen() {
        Set<Integer> src = switch (phase) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> typeChosen < 0 ? Set.of() : Set.of(typeChosen);
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> shapeChosen;
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> modifierChosen;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> effectChosen;
            default -> Set.of();
        };
        List<Integer> out = new ArrayList<>(src);
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** 当前层已选中的卡。 */
    public List<DraftCard> chosenCards() {
        List<DraftCard> src = switch (phase) {
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> shapeCards;
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> modifierCards;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> effectCards;
            default -> List.of();
        };
        List<DraftCard> out = new ArrayList<>();
        for (int i : chosen()) {
            if (i >= 0 && i < src.size()) {
                out.add(src.get(i));
            }
        }
        return List.copyOf(out);
    }

    /** 已确认的卡（跨层累计），用于 UI 显示「目前已经确定的部分」。 */
    public List<DraftCard> confirmedCards() {
        List<DraftCard> out = new ArrayList<>(
                shapePicked.size() + modifierPicked.size() + effectPicked.size());
        out.addAll(shapePicked);
        out.addAll(modifierPicked);
        out.addAll(effectPicked);
        return List.copyOf(out);
    }

    /** 一次性快照，供 UI / 网络包序列化。 */
    public BuildSnapshot snapshot() {
        return new BuildSnapshot(
                phase,
                kind,
                typeOptions(),
                cards(),
                chosen(),
                config.shapePickCount(),
                config.modifierPickCount(),
                config.effectPickCount(),
                canConfirm(),
                confirmHint(),
                confirmedCards(),
                composition,
                rng.seed()
        );
    }

    // ---------------------------------------------------------------- 内部

    /** 已确认形态的载体 id（{@code gun} / {@code staff}）。 */
    private String confirmedCarrier() {
        for (DraftCard c : shapePicked) {
            String id = c.affix().carrierId();
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** 已确认修饰里的元素 id；修饰选了属性卡时返回 null（效果池不按元素过滤）。 */
    private String confirmedElement() {
        for (DraftCard c : modifierPicked) {
            String id = c.affix().elementId();
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private Composition buildComposition() {
        List<DraftCard> all = new ArrayList<>(
                shapePicked.size() + modifierPicked.size() + effectPicked.size());
        all.addAll(shapePicked);
        all.addAll(modifierPicked);
        all.addAll(effectPicked);
        // 固定顺序：载体 → 元素 → 属性 → 效果。顺序不产生玩法意义，但必须确定，
        // 否则同一产物两次序列化的结果不相等，幂等判断会失效。
        all.sort(Comparator.comparingInt(c -> c.affix().layer().ordinal()));

        int total = 0;
        for (DraftCard c : all) {
            total += c.points();
        }
        double avg = (double) total / all.size();
        Rarity rarity = config.rarityOf(avg);

        double multiplier = 1.0;
        for (DraftCard c : all) {
            TierData.Drawback d = c.tier().drawback();
            if (d != null && d.isHatchTime()) {
                multiplier += d.value();
            }
        }
        // 卡越多越强也越久 —— 这是「越强越久」在没有点数系统后的唯一落点
        multiplier += config.extraCardHatchPct() * (all.size() - 1);

        return new Composition(kind, all, rng.seed(), rarity, total, avg, multiplier);
    }

    /** Fisher-Yates。用 {@link SeedRandom} 而不是 java.util.Random，保证可按 seed 回放。 */
    private void shuffle(List<Affix> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Affix tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private void requireBuilding(String action) {
        if (phase.isTerminal()) {
            throw new BuildException(BuildException.Code.WRONG_PHASE,
                    "流程已结束（" + phase + "），不能" + action);
        }
    }
}
