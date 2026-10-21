package com.orbcraft.core.build;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.PickedSlot;
import com.orbcraft.core.draft.TierData;
import com.orbcraft.core.spec.Rarity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 构筑的最终输出 —— 三层确认完成后的唯一产物。
 *
 * <p>这是 {@link BuildSession} 与「孵化 / 配额 / 落地」之间唯一的契约。
 * 它<b>不含任何资源字段</b>：没有点数、没有材料、没有重抽次数、没有残损球消耗。
 * 那些属于 {@code settle} 包（材料投入 → 孵化时长），不属于构筑本身 ——
 * 构筑阶段玩家花掉的只有「一次机会」，不花任何可交易物。
 *
 * <p>卡牌顺序固定为「载体 → 其他修饰 → 效果」，不保留玩家点选的先后。
 * 因为顺序不产生玩法意义，保留它只会在序列化时制造无意义的差异，
 * 让同一件产物的两次快照不相等。
 *
 * @param kind             类型（装备 / 召唤物 / 消耗品）
 * @param cards            入选的卡牌，已按层归位
 * @param seed             本次构筑的随机种子（可复现，用于投诉回放）
 * @param rarity           按平均品质算出的稀有度
 * @param totalPoints      品质点数总和（仅供展示，不参与任何扣减）
 * @param avgPoints        平均品质点数，稀有度的直接依据
 * @param hatchMultiplier  孵化时长倍率（1.0 = 不变）
 */
public record Composition(
        BaseKind kind,
        List<DraftCard> cards,
        long seed,
        Rarity rarity,
        int totalPoints,
        double avgPoints,
        double hatchMultiplier
) {

    public Composition {
        if (kind == null) {
            throw new IllegalArgumentException("Composition.kind 不能为空");
        }
        if (rarity == null) {
            throw new IllegalArgumentException("Composition.rarity 不能为空");
        }
        cards = cards == null ? List.of() : List.copyOf(cards);
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("Composition 至少要有 1 张卡");
        }
        if (cards.stream().noneMatch(c -> c.affix().layer() == Layer.CARRIER)) {
            // 没有载体就没有外观 key，落地器会直接拒绝 —— 与其到那一步才炸，不如在这里挡住
            throw new IllegalArgumentException("Composition 必须包含至少 1 张载体卡");
        }
        if (!(hatchMultiplier > 0) || !Double.isFinite(hatchMultiplier)) {
            throw new IllegalArgumentException("hatchMultiplier 必须是正有限数：" + hatchMultiplier);
        }
    }

    public int size() {
        return cards.size();
    }

    /** 载体卡。构造器已保证存在，这里仍返回 Optional 以免调用方假定。 */
    public Optional<DraftCard> carrierCard() {
        return cards.stream().filter(c -> c.affix().layer() == Layer.CARRIER).findFirst();
    }

    /** 载体裸 id（{@code gun} / {@code staff}），外观匹配用。 */
    public Optional<String> carrierId() {
        return carrierCard().map(c -> c.affix().carrierId());
    }

    /** 描述卡层选中的非载体卡（元素 / 属性）。 */
    public List<DraftCard> modifiers() {
        return cards.stream()
                .filter(c -> c.affix().layer() == Layer.ELEMENT || c.affix().layer() == Layer.ATTRIBUTE)
                .toList();
    }

    /** 效果卡。 */
    public List<DraftCard> effects() {
        return cards.stream().filter(c -> c.affix().layer() == Layer.EFFECT).toList();
    }

    /**
     * 转成 {@code PickedSlot} 列表 —— {@code settle} 包消费的形态。
     *
     * <p>提供它是为了让结算层不必了解 {@link DraftCard}：结算只关心
     * 「哪一层、哪个词条、哪档数值」，正好就是 PickedSlot。
     */
    public List<PickedSlot> slots() {
        List<PickedSlot> out = new ArrayList<>(cards.size());
        for (DraftCard c : cards) {
            out.add(new PickedSlot(c.affix().layer(), c.affix(), c.tier()));
        }
        return List.copyOf(out);
    }

    /** 语义标签：载体 id、元素 id、属性名。供外观与特效匹配。 */
    public Set<String> tags() {
        Set<String> out = new LinkedHashSet<>();
        for (DraftCard c : cards) {
            Layer layer = c.affix().layer();
            if (layer == Layer.CARRIER) {
                String id = c.affix().carrierId();
                if (id != null) {
                    out.add(id);
                }
            } else if (layer == Layer.ELEMENT) {
                String id = c.affix().elementId();
                if (id != null) {
                    out.add(id);
                }
            } else if (layer == Layer.ATTRIBUTE) {
                String[] parts = c.affix().id().split("\\.");
                out.add(parts.length > 1 ? parts[1] : c.affix().id());
            }
        }
        return out;
    }

    /** 是否带不可修复代价。 */
    public boolean hasUnrepairablePart() {
        for (DraftCard c : cards) {
            TierData.Drawback d = c.tier().drawback();
            if (d != null && d.isUnrepairable()) {
                return true;
            }
        }
        return false;
    }

    /** 一行播报，直接丢聊天框。 */
    public String brief() {
        String carrier = carrierCard().map(c -> c.affix().name() + "·" + c.tier().tier().zh()).orElse("—");
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(rarity.zh()).append(']').append(kind.zh())
                .append('：').append(carrier);
        for (DraftCard c : modifiers()) {
            sb.append(" / ").append(c.affix().name()).append('·').append(c.tier().tier().zh());
        }
        for (DraftCard c : effects()) {
            sb.append(" / 「").append(c.affix().name()).append("」");
        }
        sb.append("  均质 ").append(String.format("%.2f", avgPoints));
        return sb.toString();
    }

    /** 多行详情，UI 侧栏用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(brief()).append('\n');
        for (DraftCard c : cards) {
            sb.append("  · ").append(c.affix().layer().zh()).append('：').append(c.label()).append('\n');
        }
        sb.append("  孵化倍率 ×").append(String.format("%.2f", hatchMultiplier));
        return sb.toString();
    }
}
