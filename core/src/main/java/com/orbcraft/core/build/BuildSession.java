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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 随机构筑状态机 —— 三层确认制，无交易、无点数、无扣费。
 *
 * <h2>一次完整的构筑</h2>
 * <pre>
 * new BuildSession(table, config, seed)   → TYPE_CHOOSING
 * typeOptions()                           → [装备, 召唤物, 消耗品]（带可用性）
 * choose(0)                               → TYPE_CONFIRMING（只是登记，未推进）
 * confirm()                               → 发 5 张描述牌 → DESC_CHOOSING
 * cards()                                 → 5 张描述卡（1 载体 + 4 修饰）
 * choose(2); choose(4)                    → DESC_CONFIRMING（可反复改选）
 * confirm()                               → 发 5 张效果牌 → EFFECT_CHOOSING
 * choose(1)                               → EFFECT_CONFIRMING
 * confirm()                               → FINISHED，返回 Composition
 * </pre>
 *
 * <h2>四条被刻意设计成「绕不过去」的约束</h2>
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
 * <p><b>③ 描述层必须恰好含 1 张载体。</b>
 * 没有载体 = 没有外观 key，落地器会拒绝。与其让玩家造出一件"没有形体"的东西，
 * 不如在确认时挡住并告诉他缺什么。
 *
 * <p><b>④ 没有任何资源字段。</b> 没有 points、没有 budget、没有 reroll 消耗。
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

    private BuildPhase phase = BuildPhase.TYPE_CHOOSING;
    private BaseKind kind;
    /** 类型层选中的索引，-1 表示未选。 */
    private int typeChosen = -1;

    private List<DraftCard> descCards = List.of();
    private List<DraftCard> effectCards = List.of();
    private final Set<Integer> descChosen = new LinkedHashSet<>();
    private final Set<Integer> effectChosen = new LinkedHashSet<>();

    private final List<DraftCard> descPicked = new ArrayList<>();
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
     */
    public List<TypeOption> typeOptions() {
        List<TypeOption> out = new ArrayList<>(TYPE_ORDER.length);
        for (BaseKind k : TYPE_ORDER) {
            out.add(TypeOption.of(k, usablePoolSize(k)));
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

    /**
     * 某个类型当前可用的词条总数越少，越可能在后续层抽空。
     * 这里要求同时满足载体、修饰、效果三个池子的最低张数，否则该类型不可选。
     */
    private int usablePoolSize(BaseKind k) {
        int carriers = 0;
        for (Affix a : table.layer(Layer.CARRIER)) {
            if (a.allowsBaseType(k.wire())) {
                carriers++;
            }
        }
        int modifiers = 0;
        int effects = 0;
        for (Layer l : Layer.values()) {
            if (l == Layer.CARRIER) {
                continue;
            }
            for (Affix a : table.layer(l)) {
                if (!a.allowsBaseType(k.wire())) {
                    continue;
                }
                if (l == Layer.EFFECT) {
                    effects++;
                } else {
                    modifiers++;
                }
            }
        }
        if (carriers < config.descCarrierSlots()) {
            return 0;
        }
        if (modifiers < config.descModifierPickCount()) {
            return 0;
        }
        if (effects < config.effectPickCount()) {
            return 0;
        }
        return carriers + modifiers + effects;
    }

    // ---------------------------------------------------------------- 统一选择

    /**
     * 在当前层点选第 {@code index} 张候选。
     *
     * <p>只登记，<b>不推进</b>。再次点同一张会取消选择（单选层则回到「未选」）。
     * 数量达到本层上限后再点新的，会抛 {@code TOO_MANY_CHOICES} 而不是悄悄替换 ——
     * 悄悄替换会让玩家以为自己选的还在。
     */
    public void choose(int index) {
        requireBuilding("选择");
        switch (phase) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> chooseType(index);
            case DESC_CHOOSING, DESC_CONFIRMING -> chooseDesc(index);
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> chooseEffect(index);
            default -> throw new BuildException(BuildException.Code.WRONG_PHASE,
                    "当前阶段不能选择：" + phase);
        }
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

    private void chooseDesc(int index) {
        if (index < 0 || index >= descCards.size()) {
            throw new BuildException(BuildException.Code.INDEX_OUT_OF_RANGE,
                    "描述卡索引 " + index + " 越界（本层共 " + descCards.size() + " 张）");
        }
        if (descChosen.contains(index)) {
            descChosen.remove(index);
            phase = descChosen.isEmpty() ? BuildPhase.DESC_CHOOSING : BuildPhase.DESC_CONFIRMING;
            return;
        }
        if (descChosen.size() >= config.descPickCount()) {
            throw new BuildException(BuildException.Code.TOO_MANY_CHOICES,
                    "描述卡层最多选 " + config.descPickCount() + " 张，已选满；先取消一张再选");
        }
        descChosen.add(index);
        phase = BuildPhase.DESC_CONFIRMING;
    }

    private void chooseEffect(int index) {
        if (index < 0 || index >= effectCards.size()) {
            throw new BuildException(BuildException.Code.INDEX_OUT_OF_RANGE,
                    "效果卡索引 " + index + " 越界（本层共 " + effectCards.size() + " 张）");
        }
        if (effectChosen.contains(index)) {
            effectChosen.remove(index);
            phase = effectChosen.isEmpty() ? BuildPhase.EFFECT_CHOOSING : BuildPhase.EFFECT_CONFIRMING;
            return;
        }
        if (effectChosen.size() >= config.effectPickCount()) {
            throw new BuildException(BuildException.Code.TOO_MANY_CHOICES,
                    "效果层最多选 " + config.effectPickCount() + " 张，已选满；先取消一张再选");
        }
        effectChosen.add(index);
        phase = BuildPhase.EFFECT_CONFIRMING;
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
            case DESC_CHOOSING, DESC_CONFIRMING -> {
                descChosen.clear();
                phase = BuildPhase.DESC_CHOOSING;
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
            case DESC_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择描述卡，无法确认");
            case EFFECT_CHOOSING -> throw new BuildException(BuildException.Code.NO_PENDING_CHOICE,
                    "还没有选择效果，无法确认");
            case TYPE_CONFIRMING -> {
                dealDesc();
                phase = BuildPhase.DESC_CHOOSING;
                return Optional.empty();
            }
            case DESC_CONFIRMING -> {
                commitDesc();
                dealEffect();
                phase = BuildPhase.EFFECT_CHOOSING;
                return Optional.empty();
            }
            case EFFECT_CONFIRMING -> {
                commitEffect();
                composition = buildComposition();
                phase = BuildPhase.FINISHED;
                descCards = List.of();
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
            case DESC_CONFIRMING -> descChosen.size() == config.descPickCount()
                    && countCarrier(descCards, descChosen) == config.descCarrierSlots();
            case EFFECT_CONFIRMING -> effectChosen.size() == config.effectPickCount();
            default -> false;
        };
    }

    /** 还差什么才能确认；已满足返回空串。 */
    public String confirmHint() {
        return switch (phase) {
            case TYPE_CHOOSING -> "先选一个类型";
            case DESC_CHOOSING -> "请选择 " + config.descPickCount() + " 张描述卡";
            case EFFECT_CHOOSING -> "请选择 " + config.effectPickCount() + " 张效果";
            case TYPE_CONFIRMING -> canConfirm() ? "" : "类型未选定";
            case DESC_CONFIRMING -> {
                int need = config.descPickCount() - descChosen.size();
                if (need > 0) {
                    yield "还差 " + need + " 张描述卡";
                }
                int carriers = countCarrier(descCards, descChosen);
                if (carriers != config.descCarrierSlots()) {
                    yield "需要恰好 " + config.descCarrierSlots() + " 张载体（当前 " + carriers + " 张）";
                }
                yield "";
            }
            case EFFECT_CONFIRMING -> {
                int need = config.effectPickCount() - effectChosen.size();
                yield need > 0 ? "还差 " + need + " 张效果" : "";
            }
            default -> "";
        };
    }

    private void commitDesc() {
        if (descChosen.size() != config.descPickCount()) {
            throw new BuildException(BuildException.Code.NOT_ENOUGH_CHOICES,
                    "描述卡层需要选 " + config.descPickCount() + " 张，当前选了 " + descChosen.size() + " 张");
        }
        int carriers = countCarrier(descCards, descChosen);
        if (carriers != config.descCarrierSlots()) {
            throw new BuildException(BuildException.Code.CARRIER_REQUIRED,
                    "描述卡层必须恰好包含 " + config.descCarrierSlots()
                            + " 张载体（当前 " + carriers + " 张）：没有载体的产物无法生成外观");
        }
        for (int i : descChosen) {
            DraftCard c = descCards.get(i);
            descPicked.add(c);
            usedFamilies.add(c.family());
        }
        descChosen.clear();
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
        descCards = List.of();
        effectCards = List.of();
    }

    // ---------------------------------------------------------------- 发牌

    /**
     * 描述层发牌：固定 {@code descCarrierSlots} 张载体 + 其余修饰卡。
     *
     * <p><b>载体必须先抽。</b> 修饰卡的 {@code carrier_allow} 是相对载体判断的，
     * 同时抽会让「针对枪的属性」配上法杖。先抽载体、再按它过滤修饰池，
     * 玩家看到的 5 张才是一套读得懂的选项。
     *
     * <p>载体只有 {@code descCarrierSlots} 张，也就是玩家在描述层不能换载体 ——
     * 换载体的自由属于类型层之前的「再抽一次」。这样设计是因为载体决定外观与动画，
     * 让玩家在描述层随便换，等于给了 6 种外观 × 5 张牌的搜索空间。
     */
    private void dealDesc() {
        List<Affix> carrierPool = pool(Layer.CARRIER, null, null);
        if (carrierPool.size() < config.descCarrierSlots()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "「" + kind.zh() + "」可用载体只有 " + carrierPool.size()
                            + " 个，本层需要 " + config.descCarrierSlots() + " 个");
        }
        shuffle(carrierPool);
        List<Affix> carriers = new ArrayList<>(carrierPool.subList(0, config.descCarrierSlots()));

        // 载体已定，用它去过滤修饰池
        String primaryCarrier = carriers.get(0).carrierId();
        List<Affix> modifierPool = new ArrayList<>(pool(Layer.ELEMENT, primaryCarrier, null));
        modifierPool.addAll(pool(Layer.ATTRIBUTE, primaryCarrier, null));

        int want = config.descModifierDealCount();
        if (modifierPool.size() < config.descModifierPickCount()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "「" + kind.zh() + "」在载体 " + primaryCarrier + " 下只有 "
                            + modifierPool.size() + " 条可用修饰，本层至少要能选 "
                            + config.descModifierPickCount() + " 张");
        }
        shuffle(modifierPool);
        int take = Math.min(want, modifierPool.size());
        List<Affix> modifiers = new ArrayList<>(modifierPool.subList(0, take));

        List<Affix> all = new ArrayList<>(carriers.size() + modifiers.size());
        all.addAll(carriers);
        all.addAll(modifiers);
        // 位置打散：载体不能永远在第 1 格，否则玩家不用读卡就知道该点哪里
        shuffle(all);

        List<DraftCard> cards = new ArrayList<>(all.size());
        for (Affix a : all) {
            cards.add(new DraftCard(a, rollTier(a, config.descFloor())));
        }
        descCards = List.copyOf(cards);
    }

    /** 效果层发牌：按已确定的类型 / 载体 / 元素过滤效果池。 */
    private void dealEffect() {
        String carrier = descPicked.stream()
                .filter(c -> c.affix().layer() == Layer.CARRIER)
                .map(c -> c.affix().carrierId())
                .findFirst().orElse(null);
        String element = descPicked.stream()
                .filter(c -> c.affix().layer() == Layer.ELEMENT)
                .map(c -> c.affix().elementId())
                .findFirst().orElse(null);

        List<Affix> pool = pool(Layer.EFFECT, carrier, element);
        if (pool.size() < config.effectPickCount()) {
            throw new BuildException(BuildException.Code.POOL_TOO_SMALL,
                    "载体 " + carrier + " / 元素 " + element + " 下只有 " + pool.size()
                            + " 条可用效果，本层需要至少 " + config.effectPickCount() + " 张");
        }
        shuffle(pool);
        int take = Math.min(config.effectDealCount(), pool.size());
        List<DraftCard> cards = new ArrayList<>(take);
        for (Affix a : pool.subList(0, take)) {
            cards.add(new DraftCard(a, rollTier(a, config.effectFloor())));
        }
        effectCards = List.copyOf(cards);
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
            case DESC_CHOOSING, DESC_CONFIRMING -> descCards;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> effectCards;
            default -> List.of();
        };
    }

    /** 当前层已选中的索引，升序。 */
    public List<Integer> chosen() {
        Set<Integer> src = switch (phase) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> typeChosen < 0 ? Set.of() : Set.of(typeChosen);
            case DESC_CHOOSING, DESC_CONFIRMING -> descChosen;
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
            case DESC_CHOOSING, DESC_CONFIRMING -> descCards;
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
        List<DraftCard> out = new ArrayList<>(descPicked.size() + effectPicked.size());
        out.addAll(descPicked);
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
                config.descPickCount(),
                config.effectPickCount(),
                config.descCarrierSlots(),
                canConfirm(),
                confirmHint(),
                confirmedCards(),
                composition,
                rng.seed()
        );
    }

    // ---------------------------------------------------------------- 内部

    private Composition buildComposition() {
        List<DraftCard> all = new ArrayList<>(descPicked.size() + effectPicked.size());
        all.addAll(descPicked);
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

    private static int countCarrier(List<DraftCard> cards, Set<Integer> chosen) {
        int n = 0;
        for (int i : chosen) {
            if (i >= 0 && i < cards.size() && cards.get(i).affix().layer() == Layer.CARRIER) {
                n++;
            }
        }
        return n;
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
