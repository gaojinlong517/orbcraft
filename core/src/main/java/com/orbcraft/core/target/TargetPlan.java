package com.orbcraft.core.target;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.TargetCard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次定向方案：某一类型下要使用的若干张定向卡。
 *
 * <p>为什么把它和 {@link TargetAllowance} 分开：方案是**意图**（我这次想定向剑 + 火），
 * 配额是**事实**（今天装备还剩 0 次）。两者生命周期不同 —— 方案可以被玩家反复修改到满意为止，
 * 而配额一旦扣掉就回不来。混在一个类里，UI 每改一次方案就得担心是不是已经扣了钱。
 *
 * <p>合法性由 {@link #of} 一次性挡在构造之前，所以拿到手的 {@code TargetPlan} 一定是
 * 「卡存在、类型对、每层最多一张、不重复」的，下游不需要再校验。
 *
 * <p>{@code cards} 为空表示「本次不启用定向」—— 这是完全合法的选择，不是错误分支。
 */
public record TargetPlan(BaseKind kind, List<TargetCard> cards) {

    public TargetPlan {
        if (kind == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "基调不能为 null");
        }
        if (cards == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "定向卡列表不能为 null，空方案请传空列表");
        }
        for (TargetCard card : cards) {
            if (card == null) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT, "定向卡列表中存在 null 元素");
            }
        }
        cards = List.copyOf(cards);
    }

    /**
     * 按玩家选的 id 列表建方案。
     *
     * <p>校验顺序：卡存在 → 类型匹配 → id 不重复 → 同层唯一。
     * 「重复」排在「同层」之前，是因为同一类型下两张卡重复必然也同层，
     * 反过来排会让 {@code CARD_DUPLICATED} 永远不可达，报错就没法指认真正的问题。
     *
     * @param cardIds 允许为空（= 不启用定向），顺序被保留
     * @throws TargetException {@code UNKNOWN_CARD} / {@code WRONG_KIND} /
     *                         {@code CARD_DUPLICATED} / {@code LAYER_DUPLICATE} / {@code BAD_ARGUMENT}
     */
    public static TargetPlan of(TargetDeck deck, BaseKind kind, List<String> cardIds) {
        if (deck == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "卡池不能为 null");
        }
        if (kind == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "基调不能为 null");
        }
        if (cardIds == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "id 列表不能为 null，空方案请传空列表");
        }
        List<TargetCard> picked = new ArrayList<>(cardIds.size());
        Set<String> seenIds = new LinkedHashSet<>();
        Set<Layer> seenLayers = EnumSet.noneOf(Layer.class);

        for (String cardId : cardIds) {
            if (cardId == null || cardId.isBlank()) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT, "定向卡 id 不能为空");
            }
            TargetCard card = deck.find(cardId)
                    .orElseThrow(() -> new TargetException(TargetException.Code.UNKNOWN_CARD,
                            "定向卡不存在：" + cardId));
            if (!deck.offers(kind, card)) {
                throw new TargetException(TargetException.Code.WRONG_KIND,
                        "定向卡「" + card + "」不属于" + kind.zh() + "，不能用于" + kind.zh() + "方案");
            }
            if (!seenIds.add(card.id())) {
                throw new TargetException(TargetException.Code.CARD_DUPLICATED,
                        "定向卡「" + card + "」出现了两次");
            }
            if (!seenLayers.add(card.targetLayer())) {
                throw new TargetException(TargetException.Code.LAYER_DUPLICATE,
                        card.targetLayer().zh() + "层已有定向卡，一层最多定向一次（冲突：" + card + "）");
            }
            picked.add(card);
        }
        return new TargetPlan(kind, picked);
    }

    /** 未启用定向。 */
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /** 给 UI / 日志用的一行描述，例如「装备 定向：定向·剑、定向·火」。 */
    public String describe() {
        if (cards.isEmpty()) {
            return "未启用定向（" + kind.zh() + "）";
        }
        StringBuilder sb = new StringBuilder(kind.zh()).append(" 定向：");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(cards.get(i));
        }
        return sb.toString();
    }
}
