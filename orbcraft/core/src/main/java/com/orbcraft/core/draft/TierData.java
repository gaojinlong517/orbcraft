package com.orbcraft.core.draft;

/**
 * 一个词条在某个品质档下的具体数值。
 *
 * <p>「品质变体不做成四张卡，而是一条词条的四档」——这条设计让卡表规模是 1 而不是 4。
 * 红色吸血不需要单独建条目，它只是吸血那条数据的第四个 tier。
 *
 * @param tier      品质档
 * @param points    预算点数（必须等于 {@link Tier#designPoints()}）
 * @param magnitude 相对强度倍率（1.0 / 1.6 / 2.5 / 4.0）
 * @param value     实际数值，单位由词条自身决定
 * @param drawback  代价；白卡为 null。**高品阶必带代价，否则 5 选 1 会退化成无脑点红卡**
 * @param desc      展示文案
 */
public record TierData(
        Tier tier,
        int points,
        double magnitude,
        double value,
        Drawback drawback,
        String desc
) {

    public TierData {
        if (tier == null) {
            throw new IllegalArgumentException("tier 不能为空");
        }
        if (points < 0) {
            throw new IllegalArgumentException("points 不能为负");
        }
        desc = desc == null ? "" : desc;
    }

    public boolean hasDrawback() {
        return drawback != null;
    }

    /**
     * 高品阶的代价。
     *
     * <p>第一个用途就是「越强越久」：红卡带 {@code HATCH_TIME_PCT +0.5}，
     * 于是抽卡里重新出现了「强度换时间」的因果 —— 这比原来的「描述越长越久」更合理，
     * 因为玩家买的是强度，不是字数。
     */
    public record Drawback(String type, double value) {

        public Drawback {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("drawback.type 不能为空");
            }
        }

        /** 孵化时间增加的百分比，0.5 = 多等 50%。 */
        public boolean isHatchTime() {
            return "HATCH_TIME_PCT".equals(type);
        }

        public boolean isUnrepairable() {
            return "UNREPAIRABLE".equals(type);
        }
    }
}
