package com.orbcraft.core.draft;

/**
 * 已选进产物的一层。定稿时按层顺序拼成最终产物。
 *
 * @param layer 层
 * @param affix 词条
 * @param tier  确定的品质档
 */
public record PickedSlot(Layer layer, Affix affix, TierData tier) {

    public int points() {
        return tier.points();
    }

    public String family() {
        return affix.family();
    }

    @Override
    public String toString() {
        return layer.zh() + "：" + affix.name() + "（" + tier.tier().zh() + "）" + tier.desc();
    }
}
