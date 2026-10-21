package com.orbcraft.core.draft;

/**
 * 发到玩家面前的一张牌 = 一个词条 + 它这一档的品质数值。
 *
 * @param affix 词条本体
 * @param tier  这次 roll 到的品质档
 */
public record DraftCard(Affix affix, TierData tier) {

    public DraftCard {
        if (affix == null || tier == null) {
            throw new IllegalArgumentException("牌缺少词条或品质档");
        }
    }

    public int points() {
        return tier.points();
    }

    public String family() {
        return affix.family();
    }

    public Tier quality() {
        return tier.tier();
    }

    /** 剩余预算够不够买这张。 */
    public boolean affordableWith(int remainingPoints) {
        return points() <= remainingPoints;
    }

    /** 卡面文案：品质色 + 名称 + 数值 + 点数。UI 直接照这个渲染。 */
    public String label() {
        String mark = switch (tier.tier()) {
            case WHITE -> "白";
            case BLUE -> "蓝";
            case GOLD -> "金";
            case RED -> "红";
        };
        return "[" + mark + "·" + affix.name() + "] " + tier.desc() + "  " + points() + "pt";
    }

    @Override
    public String toString() {
        return label();
    }
}
