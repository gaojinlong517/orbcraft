package com.orbcraft.core.draft;

import com.orbcraft.core.spec.Rarity;

import java.util.EnumMap;
import java.util.Map;

/**
 * 抽卡的数值配置。所有可调参数集中在这里，方便策划改一个数就重跑模拟。
 *
 * <p>几个值的来历必须记住，改之前先想清楚：
 * <ul>
 *   <li>{@code budget = 28} —— 不是目标，是上限。4 层全红正好 28，对应传说</li>
 *   <li>{@code minAffordable = 2} —— 低于这个数会出现「五张牌四张灰」，
 *       玩家的感受不是「难」，是「坏了」</li>
 *   <li>{@code bands} 的 5/11/18 —— 按纯随机抽卡实测分布校准过。
 *       若沿用原始 4/8/16，平均分 9.84 正好落在史诗，实测史诗占 54%、普通只占 2%</li>
 * </ul>
 */
public record DraftConfig(
        int budget,
        int drawCount,
        int minAffordable,
        Map<Tier, Integer> qualityWeights,
        Map<Layer, Tier> floorByLayer,
        int rerollBaseCost,
        int maxRerollsPerLayer,
        int commonMax,
        int rareMax,
        int epicMax
) {

    public DraftConfig {
        if (budget < 4 || budget > 60) {
            throw new IllegalArgumentException("budget 应在 4~60 之间：" + budget);
        }
        if (drawCount < 3 || drawCount > 9) {
            throw new IllegalArgumentException("drawCount 应在 3~9 之间：" + drawCount);
        }
        if (minAffordable < 1 || minAffordable > drawCount) {
            throw new IllegalArgumentException("minAffordable 应在 1~drawCount 之间");
        }
        qualityWeights = qualityWeights == null
                ? Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8)
                : Map.copyOf(qualityWeights);
        floorByLayer = floorByLayer == null
                ? Map.of(Layer.CARRIER, Tier.WHITE, Layer.ELEMENT, Tier.WHITE,
                          Layer.ATTRIBUTE, Tier.BLUE, Layer.EFFECT, Tier.BLUE)
                : Map.copyOf(floorByLayer);
        if (!(commonMax > 0 && commonMax < rareMax && rareMax < epicMax)) {
            throw new IllegalArgumentException(
                    "稀有度阈值必须递增且为正：" + commonMax + "/" + rareMax + "/" + epicMax);
        }
    }

    /** 默认配置：预算 28、抽 5、保底 2 张可选、属性/效果层保底蓝。 */
    public static DraftConfig defaults() {
        return new DraftConfig(
                28, 5, 2,
                Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8),
                Map.of(Layer.CARRIER, Tier.WHITE, Layer.ELEMENT, Tier.WHITE,
                        Layer.ATTRIBUTE, Tier.BLUE, Layer.EFFECT, Tier.BLUE),
                1, 10,
                5, 11, 18
        );
    }

    /**
     * 无保底配置 —— 用于和默认配置做 A/B 对比。
     * 保底会把平均点数往上顶，直接影响稀有度分布，不能凭感觉开。
     */
    public static DraftConfig withoutFloor() {
        Map<Layer, Tier> floor = new EnumMap<>(Layer.class);
        for (Layer l : Layer.values()) {
            floor.put(l, Tier.WHITE);
        }
        return new DraftConfig(
                28, 5, 2,
                Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8),
                floor, 1, 10, 5, 11, 18
        );
    }

    public Tier floorFor(Layer layer) {
        return floorByLayer.getOrDefault(layer, Tier.WHITE);
    }

    public int weightOf(Tier tier) {
        return qualityWeights.getOrDefault(tier, 0);
    }

    public int totalWeight() {
        int n = 0;
        for (Tier t : Tier.values()) {
            n += weightOf(t);
        }
        return n;
    }

    /** 点数 → 稀有度。这是「4 层全同品质对齐四档稀有度」的落点。 */
    public Rarity rarityOf(int points) {
        if (points <= commonMax) {
            return Rarity.COMMON;
        }
        if (points <= rareMax) {
            return Rarity.RARE;
        }
        if (points <= epicMax) {
            return Rarity.EPIC;
        }
        return Rarity.LEGENDARY;
    }

    /** 某层第 n 次重抽（从 0 开始计）的残损球价格。 */
    public int rerollCost(int rerollsAlreadyDone) {
        return rerollBaseCost * (rerollsAlreadyDone + 1);
    }

    /** 一键切换保底，方便对比测试。 */
    public DraftConfig withFloor(Map<Layer, Tier> floor) {
        return new DraftConfig(budget, drawCount, minAffordable, qualityWeights, floor,
                rerollBaseCost, maxRerollsPerLayer, commonMax, rareMax, epicMax);
    }

    /**
     * 换一个预算档 —— 用于验证「预算由玩家投入决定」这条替代方案。
     *
     * <p>背景：固定预算 28 时，贪心玩家 4 层抽完平均 18.8 点，
     * 实测传说占 57%、普通占 0%。稀有度变成了「玩家有多克制」，
     * 而不是「他投入了多少」—— 这是一个必须修的数值结构问题。
     */
    public DraftConfig withBudget(int newBudget) {
        return new DraftConfig(newBudget, drawCount, minAffordable, qualityWeights, floorByLayer,
                rerollBaseCost, maxRerollsPerLayer, commonMax, rareMax, epicMax);
    }
}
