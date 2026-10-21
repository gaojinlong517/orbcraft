package com.orbcraft.core.draft;

import com.orbcraft.core.spec.Rarity;

import java.util.List;

/**
 * 抽卡界面的只读快照。UI 只依赖它，不直接碰 {@link DraftSession} 的内部状态。
 *
 * <p>把「该不该灰化」「还差什么才能定稿」这类判断收在这里，
 * 是为了让 UI 只需要渲染，不需要理解规则 —— 否则规则改一次就要改一遍界面。
 *
 * @param phase              当前阶段
 * @param layer              当前层；已结束时为 null
 * @param points             已花点数
 * @param budget             预算上限
 * @param projectedRarity    按当前点数**若立即定稿**会得到的稀有度
 * @param cards              当前发到的牌（未发牌时为空）
 * @param affordableCount    当前可买的牌数（用于判断「灰化」是否正常）
 * @param rerollsThisLayer   本层已重抽次数
 * @param rerollCost         本层下一次重抽的球价
 * @param canDeal            现在可以发牌
 * @param canFinish          现在可以定稿（门槛已满足）
 * @param finishHint         定稿还差什么；可定稿时为空串
 * @param activeTarget       本层生效中的定向卡
 * @param targetUsed         本件产物是否已用过定向卡
 * @param picked             已选各层
 * @param availableFamilies  本层池里还剩多少个族（诊断用，可显示在角落）
 */
public record DraftSnapshot(
        DraftPhase phase,
        Layer layer,
        int points,
        int budget,
        Rarity projectedRarity,
        List<DraftCard> cards,
        int affordableCount,
        int rerollsThisLayer,
        int rerollCost,
        boolean canDeal,
        boolean canFinish,
        String finishHint,
        TargetCard activeTarget,
        boolean targetUsed,
        List<PickedSlot> picked,
        int availableFamilies
) {

    public DraftSnapshot {
        cards = cards == null ? List.of() : List.copyOf(cards);
        picked = picked == null ? List.of() : List.copyOf(picked);
        finishHint = finishHint == null ? "" : finishHint;
    }

    public int remainingPoints() {
        return budget - points;
    }

    public boolean isOver() {
        return phase.isTerminal();
    }

    /** 还有几张牌可选（不灰）。 */
    public int selectable() {
        return affordableCount;
    }

    public String layerTitle() {
        return layer == null ? "已结束" : layer.zh();
    }
}
