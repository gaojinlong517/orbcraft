package com.orbcraft.core.draft;

import com.orbcraft.core.spec.Rarity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 抽卡状态机 —— 玩法的核心交互，也是玩家每天点最多次的地方。
 *
 * <h2>一次完整的抽卡</h2>
 * <pre>
 * pickBase(装备)            → 层 1 就绪
 *   [可选] useTargetCard()  → 指定层 1 必定出某张卡（必须在 deal 之前）
 * deal()                  → 发 5 张牌
 *   [可选] reroll(持有球数) → 花球重发本层（保留已选、保留定向）
 * pick(i)                 → 选一张，进入层 2 就绪
 *   …重复到层 4…
 * finish("烈焰巨枪")       → 定稿
 * </pre>
 *
 * <h2>三条不能动的规则，以及它们为什么不能动</h2>
 *
 * <p><b>① 定向卡必须在发牌前使用。</b> 允许「看完 5 张再决定用不用」，玩家必然每次都等看完
 * 再决定，成功率接近 100%，随机性被买穿。{@link DraftPhase#LAYER_READY} 这个阶段就是为此存在的。
 *
 * <p><b>② 定向卡只保类型不保强度。</b> 若「必定有红龙」，玩家买到的是产物本身，
 * 前几层全部失去意义。
 *
 * <p><b>③ 保底只在预算允许时生效。</b> 强行保底会让玩家面对一张灰掉的高品质卡，
 * 那是「坏了」而不是「难」。
 *
 * <p>本类不是线程安全的，一个 session 只属于一个玩家的一次抽卡。
 */
public final class DraftSession {

    private final AffixTable table;
    private final DraftConfig config;
    private final SeedRandom rng;
    private final int[] weights;

    private BaseKind baseKind;
    private DraftPhase phase = DraftPhase.PICK_BASE;
    private int layerIndex;

    private final List<PickedSlot> picked = new ArrayList<>();
    /** 已占用的族 —— 同族在整件产物里只能出现一次。 */
    private final Set<String> usedFamilies = new LinkedHashSet<>();

    private List<DraftCard> current = List.of();
    private int points;
    private int rerollsThisLayer;
    private int totalRerolls;
    private int orbCost;

    private TargetCard activeTarget;
    private boolean targetUsedOnce;
    private String carrierId;
    private String elementId;

    /** 最近一次发牌是否真的注入了定向目标（用于诊断「用了卡却没出现」）。 */
    private boolean lastDealInjectedTarget;
    /** 整件产物是否曾成功注入过定向目标 —— 定稿后仍可查，用于运营统计。 */
    private boolean targetInjectedEver;

    public DraftSession(AffixTable table, DraftConfig config, long seed) {
        this.table = Objects.requireNonNull(table, "table");
        this.config = config == null ? DraftConfig.defaults() : config;
        this.rng = new SeedRandom(seed);
        this.weights = new int[Tier.values().length];
        for (Tier t : Tier.values()) {
            weights[t.rank()] = this.config.weightOf(t);
        }
    }

    public static DraftSession create(AffixTable table, long seed) {
        return new DraftSession(table, DraftConfig.defaults(), seed);
    }

    public long seed() {
        return rng.seed();
    }

    public DraftConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- 层 0

    /** 选基调：装备 / 消耗品 / 召唤物。之后进入层 1 就绪。 */
    public void pickBase(BaseKind kind) {
        requirePhase(DraftPhase.PICK_BASE, "选基调");
        this.baseKind = Objects.requireNonNull(kind, "baseKind");
        this.layerIndex = 0;
        this.phase = DraftPhase.LAYER_READY;
    }

    public BaseKind baseKind() {
        return baseKind;
    }

    public Layer currentLayer() {
        return phase.isDraftingOver() || baseKind == null
                ? null
                : Layer.byIndex(Math.min(layerIndex, Layer.values().length - 1));
    }

    // ---------------------------------------------------------------- 定向卡

    /**
     * 对本层使用定向卡。<b>必须在本层发牌之前调用。</b>
     *
     * <p>这里做的是「严格前置校验」而不是「尽量帮忙」：
     * 目标词条不在池里、层不匹配、已经用过一次、带品质保证 —— 任何一种都在**使用时**拒绝。
     * 因为「用掉一张卡才发现没用」比「不让用」伤人得多。
     */
    public void useTargetCard(TargetCard card) {
        requirePhase(DraftPhase.LAYER_READY, "使用定向卡");
        Objects.requireNonNull(card, "card");
        Layer layer = currentLayer();

        if (targetUsedOnce) {
            throw new DraftException(DraftException.Code.TARGET_ALREADY_USED,
                    "每件产物最多使用 1 张定向卡");
        }
        if (card.guaranteesQuality()) {
            throw new DraftException(DraftException.Code.TARGET_RULE_VIOLATION,
                    "定向卡 " + card.id() + " 带 quality_floor=" + card.qualityFloor()
                            + "，违反「只保类型不保强度」");
        }
        if (card.targetLayer() != layer) {
            throw new DraftException(DraftException.Code.TARGET_WRONG_LAYER,
                    "定向卡指定的是 " + card.targetLayer().zh() + " 层，当前是 " + layer.zh() + " 层");
        }
        Affix target = table.findById(card.targetAffix())
                .orElseThrow(() -> new DraftException(DraftException.Code.TARGET_NOT_IN_POOL,
                        "卡表里不存在词条 " + card.targetAffix()));
        if (!eligible(target, layer)) {
            throw new DraftException(DraftException.Code.TARGET_NOT_IN_POOL,
                    "词条 " + target.name() + " 在当前条件下不可用（基调 / 载体 / 元素 / 互斥）");
        }
        this.activeTarget = card;
    }

    public Optional<TargetCard> activeTarget() {
        return Optional.ofNullable(activeTarget);
    }

    public boolean targetUsed() {
        return targetUsedOnce;
    }

    // ---------------------------------------------------------------- 发牌

    /** 发本层的牌。只能在 {@link DraftPhase#LAYER_READY} 调用。 */
    public List<DraftCard> deal() {
        requirePhase(DraftPhase.LAYER_READY, "发牌");
        Layer layer = currentLayer();
        if (baseKind == null) {
            throw new DraftException(DraftException.Code.WRONG_PHASE, "还没选基调");
        }
        dealInternal(layer);
        phase = DraftPhase.LAYER_PICKING;
        return current;
    }

    /**
     * 重抽本层。已选内容与定向卡都保留。
     *
     * @param ownedOrbs 玩家当前持有的残损球数，不足会被拒绝（不允许透支）
     */
    public List<DraftCard> reroll(int ownedOrbs) {
        requirePhase(DraftPhase.LAYER_PICKING, "重抽");
        if (rerollsThisLayer >= config.maxRerollsPerLayer()) {
            throw new DraftException(DraftException.Code.WRONG_PHASE,
                    "本层重抽次数已达上限 " + config.maxRerollsPerLayer());
        }
        int cost = rerollCost();
        if (ownedOrbs < cost) {
            throw new DraftException(DraftException.Code.INSUFFICIENT_ORBS,
                    "重抽需要 " + cost + " 个残损球，当前只有 " + ownedOrbs);
        }
        rerollsThisLayer++;
        totalRerolls++;
        orbCost += cost;
        dealInternal(currentLayer());
        return current;
    }

    /** 本层下一次重抽的价格（残损球）。 */
    public int rerollCost() {
        return config.rerollCost(rerollsThisLayer);
    }

    /**
     * 选中第 index 张牌。
     *
     * <p>预算检查放在这里而不是只放在 UI：UI 灰化是体验，这里是正确性。
     * 任何绕过 UI 的调用（比如网络包重放）都必须被挡住。
     */
    public void pick(int index) {
        requirePhase(DraftPhase.LAYER_PICKING, "选牌");
        if (index < 0 || index >= current.size()) {
            throw new DraftException(DraftException.Code.INDEX_OUT_OF_RANGE,
                    "索引 " + index + " 越界（本层共 " + current.size() + " 张）");
        }
        DraftCard card = current.get(index);
        if (card.points() > remainingPoints()) {
            throw new DraftException(DraftException.Code.BUDGET_EXCEEDED,
                    "选 " + card.affix().name() + " 需要 " + card.points()
                            + " 点，只剩 " + remainingPoints() + " 点");
        }

        Layer layer = currentLayer();
        picked.add(new PickedSlot(layer, card.affix(), card.tier()));
        usedFamilies.add(card.family());
        points += card.points();
        if (layer == Layer.CARRIER) {
            carrierId = card.affix().carrierId();
        } else if (layer == Layer.ELEMENT) {
            elementId = card.affix().elementId();
        }
        if (activeTarget != null) {
            activeTarget = null; // 定向卡随本层结束而消耗
        }

        current = List.of();
        rerollsThisLayer = 0;
        layerIndex++;
        // 四层抽完不是「定稿」，只是抽卡动作结束 —— 名字还没取，额度也还没扣
        phase = layerIndex >= Layer.values().length ? DraftPhase.AWAITING_NAME : DraftPhase.LAYER_READY;
    }

    // ---------------------------------------------------------------- 定稿

    /**
     * 定稿门槛：<b>至少载体 + 属性或效果之一</b>。
     *
     * <p>只抽载体就定稿会得到一把没有任何属性的裸枪，玩家不会认为那是「我造的东西」。
     */
    public boolean canFinish() {
        boolean hasCarrier = picked.stream().anyMatch(s -> s.layer() == Layer.CARRIER);
        boolean hasAttrOrEffect = picked.stream()
                .anyMatch(s -> s.layer() == Layer.ATTRIBUTE || s.layer() == Layer.EFFECT);
        return hasCarrier && hasAttrOrEffect;
    }

    public String finishHint() {
        if (phase == DraftPhase.FINISHED) {
            return "";
        }
        if (baseKind == null) {
            return "先选一个基调";
        }
        boolean hasCarrier = picked.stream().anyMatch(s -> s.layer() == Layer.CARRIER);
        if (!hasCarrier) {
            return "还需要一个载体";
        }
        boolean hasAttrOrEffect = picked.stream()
                .anyMatch(s -> s.layer() == Layer.ATTRIBUTE || s.layer() == Layer.EFFECT);
        if (!hasAttrOrEffect) {
            return "还需要至少 1 个属性或效果";
        }
        return "";
    }

    /** 用默认名定稿（元素 + 载体，如「雷·枪」）。 */
    public DraftResult finish() {
        return finish(defaultName());
    }

    public DraftResult finish(String name) {
        if (phase == DraftPhase.FINISHED) {
            throw new DraftException(DraftException.Code.WRONG_PHASE, "已经定稿了");
        }
        if (phase == DraftPhase.CANCELLED) {
            throw new DraftException(DraftException.Code.WRONG_PHASE, "本次抽卡已放弃");
        }
        if (!canFinish()) {
            throw new DraftException(DraftException.Code.FINISH_GATE_NOT_MET, finishHint());
        }        String finalName = name == null || name.isBlank() ? defaultName() : name.trim();
        if (finalName.length() > 24) {
            finalName = finalName.substring(0, 24);
        }
        phase = DraftPhase.FINISHED;
        current = List.of();
        return new DraftResult(finalName, baseKind, config.rarityOf(points), points,
                picked, rng.seed(), totalRerolls, orbCost);
    }

    /** 放弃本次抽卡。不消耗额度、不消耗材料。 */
    public void cancel() {
        if (phase.isTerminal()) {
            throw new DraftException(DraftException.Code.WRONG_PHASE, "已经结束了");
        }
        phase = DraftPhase.CANCELLED;
        current = List.of();
    }

    private String defaultName() {
        String elem = picked.stream()
                .filter(s -> s.layer() == Layer.ELEMENT)
                .map(s -> s.affix().name())
                .findFirst().orElse("");
        String carrier = picked.stream()
                .filter(s -> s.layer() == Layer.CARRIER)
                .map(s -> s.affix().name())
                .findFirst().orElse("产物");
        return elem.isEmpty() ? carrier : elem + "·" + carrier;
    }

    // ---------------------------------------------------------------- 查询

    public DraftPhase phase() {
        return phase;
    }

    public int points() {
        return points;
    }

    public int remainingPoints() {
        return config.budget() - points;
    }

    public List<PickedSlot> picked() {
        return List.copyOf(picked);
    }

    public List<DraftCard> currentCards() {
        return current;
    }

    /** 本次发牌是否真的注入了定向目标。 */
    public boolean lastDealInjectedTarget() {
        return lastDealInjectedTarget;
    }

    /** 整件产物是否曾注入过定向目标。定稿后仍可查。 */
    public boolean targetInjectedEver() {
        return targetInjectedEver;
    }

    public DraftSnapshot snapshot() {
        Layer layer = currentLayer();
        int affordable = 0;
        for (DraftCard c : current) {
            if (c.points() <= remainingPoints()) {
                affordable++;
            }
        }
        return new DraftSnapshot(
                phase, layer, points, config.budget(), config.rarityOf(points),
                current, affordable, rerollsThisLayer,
                phase == DraftPhase.LAYER_PICKING ? rerollCost() : 0,
                phase == DraftPhase.LAYER_READY,
                canFinish(), finishHint(),
                activeTarget, targetUsedOnce, picked,
                layer == null ? 0 : availableFamilyCount(layer)
        );
    }

    // ---------------------------------------------------------------- 内部

    private void dealInternal(Layer layer) {
        List<Affix> pool = filterPool(layer);

        // 去同族：同族只保留一条，且族已被整件产物占用则跳过
        LinkedHashMap<String, Affix> byFamily = new LinkedHashMap<>();
        for (Affix a : pool) {
            if (usedFamilies.contains(a.family())) {
                continue;
            }
            byFamily.putIfAbsent(a.family(), a);
        }
        List<Affix> avail = new ArrayList<>(byFamily.values());
        if (avail.isEmpty()) {
            throw new DraftException(DraftException.Code.POOL_EMPTY,
                    layer.zh() + " 层没有可用词条（基调 " + baseKind.zh()
                            + "，载体 " + carrierId + "，元素 " + elementId + "）");
        }

        shuffle(avail);
        int take = Math.min(config.drawCount(), avail.size());
        List<Affix> drawn = new ArrayList<>(avail.subList(0, take));
        List<TierData> tiers = new ArrayList<>(take);
        for (Affix a : drawn) {
            tiers.add(rollTier(a));
        }

        // 定向注入：替换其中一个位置（位置随机，避免玩家记住「定向总在第 3 格」）
        lastDealInjectedTarget = false;
        if (activeTarget != null && activeTarget.targetLayer() == layer) {
            Affix target = table.findById(activeTarget.targetAffix()).orElse(null);
            if (target != null && eligible(target, layer)) {
                int pos = indexOfId(drawn, target.id());
                if (pos < 0) {
                    pos = rng.nextInt(drawn.size());
                    drawn.set(pos, target);
                    tiers.set(pos, rollTier(target));
                }
                lastDealInjectedTarget = true;
                targetInjectedEver = true;
                targetUsedOnce = true;
            }
        }

        enforceAffordable(drawn, tiers);
        applyFloor(layer, drawn, tiers);

        List<DraftCard> cards = new ArrayList<>(drawn.size());
        for (int i = 0; i < drawn.size(); i++) {
            cards.add(new DraftCard(drawn.get(i), tiers.get(i)));
        }
        this.current = List.copyOf(cards);
    }

    private TierData rollTier(Affix affix) {
        Tier rolled = rng.pickTier(weights);
        TierData data = affix.tierData(rolled);
        // 理论上四档齐全（loader 已校验）；这里降级查找是为了防止将来数据放宽后静默出错
        for (int i = rolled.rank(); i >= 0 && data == null; i--) {
            data = affix.tierData(Tier.values()[i]);
        }
        if (data == null) {
            throw new DraftException(DraftException.Code.POOL_EMPTY,
                    "词条 " + affix.id() + " 没有任何品质档数据");
        }
        return data;
    }

    /**
     * 保证至少 {@code minAffordable} 张牌买得起。
     *
     * <p>不这么做的后果不是「难」，是「坏」：玩家看到五张牌四张灰，
     * 会认为系统出了故障，而不是认为自己点数不够。
     */
    private void enforceAffordable(List<Affix> drawn, List<TierData> tiers) {
        int remaining = remainingPoints();
        int guard = 0;
        while (countAffordable(tiers, remaining) < Math.min(config.minAffordable(), tiers.size())
                && guard++ < 12) {
            boolean changed = false;
            for (int i = 0; i < tiers.size(); i++) {
                if (tiers.get(i).points() > remaining) {
                    TierData lower = highestAffordable(drawn.get(i), remaining);
                    if (lower != null) {
                        tiers.set(i, lower);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    private TierData highestAffordable(Affix affix, int remaining) {
        for (int i = Tier.values().length - 1; i >= 0; i--) {
            TierData d = affix.tierData(Tier.values()[i]);
            if (d != null && d.points() <= remaining) {
                return d;
            }
        }
        return null;
    }

    private int countAffordable(List<TierData> tiers, int remaining) {
        int n = 0;
        for (TierData t : tiers) {
            if (t.points() <= remaining) {
                n++;
            }
        }
        return n;
    }

    /**
     * 本层保底。只在「提升后仍买得起」时生效 ——
     * 强行保底会让玩家拿到一张灰掉的高品质卡，比没有保底更糟。
     */
    private void applyFloor(Layer layer, List<Affix> drawn, List<TierData> tiers) {
        Tier floor = config.floorFor(layer);
        if (floor == Tier.WHITE) {
            return;
        }
        for (TierData t : tiers) {
            if (t.tier().rank() >= floor.rank()) {
                return; // 已满足
            }
        }
        int remaining = remainingPoints();
        int bestIdx = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < tiers.size(); i++) {
            TierData up = drawn.get(i).tierData(floor);
            if (up == null || up.points() > remaining) {
                continue;
            }
            if (tiers.get(i).tier().rank() < bestRank) {
                bestRank = tiers.get(i).tier().rank();
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            tiers.set(bestIdx, drawn.get(bestIdx).tierData(floor));
        }
    }

    private List<Affix> filterPool(Layer layer) {
        List<Affix> out = new ArrayList<>();
        for (Affix a : table.layer(layer)) {
            if (eligible(a, layer)) {
                out.add(a);
            }
        }
        return out;
    }

    private boolean eligible(Affix a, Layer layer) {
        if (!a.allowsBaseType(baseKind.wire())) {
            return false;
        }
        if (layer == Layer.CARRIER || layer == Layer.ELEMENT) {
            // 载体的基调由它自己的 base_type 决定；元素是全局的
            return true;
        }
        return a.allowsCarrier(carrierId)
                && a.allowsElement(elementId)
                && !a.conflictsWith(usedFamilies);
    }

    private int availableFamilyCount(Layer layer) {
        Set<String> fams = new LinkedHashSet<>();
        for (Affix a : filterPool(layer)) {
            if (!usedFamilies.contains(a.family())) {
                fams.add(a.family());
            }
        }
        return fams.size();
    }

    private void shuffle(List<Affix> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Affix tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private static int indexOfId(List<Affix> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void requirePhase(DraftPhase expected, String action) {
        if (phase != expected) {
            throw new DraftException(DraftException.Code.WRONG_PHASE,
                    action + " 需要处于 " + expected + "，当前是 " + phase);
        }
    }

    /** 供 UI 展示「若现在定稿会是什么稀有度」。 */
    public Rarity projectedRarity() {
        return config.rarityOf(points);
    }
}
