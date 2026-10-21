package com.orbcraft.core.draft;

import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;

import java.util.List;
import java.util.Optional;

/**
 * 定稿产物 —— 抽卡的最终输出，也是交给孵化 / 落地器的输入。
 *
 * @param productName  玩家自己取的名字
 * @param baseKind     基调
 * @param rarity       按点数结算的稀有度
 * @param points       总点数
 * @param slots        已选各层
 * @param seed         本次抽卡的随机种子（**必须记录**，用于投诉回放）
 * @param totalRerolls 总重抽次数
 * @param orbCost      累计消耗的残损球
 */
public record DraftResult(
        String productName,
        BaseKind baseKind,
        Rarity rarity,
        int points,
        List<PickedSlot> slots,
        long seed,
        int totalRerolls,
        int orbCost
) {

    public DraftResult {
        slots = slots == null ? List.of() : List.copyOf(slots);
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("产物必须有名字");
        }
    }

    public ProductType productType() {
        return baseKind.toProductType();
    }

    public Optional<PickedSlot> slot(Layer layer) {
        return slots.stream().filter(s -> s.layer() == layer).findFirst();
    }

    /** 某层选了什么，没有则返回「—」。 */
    public String summaryOf(Layer layer) {
        return slot(layer).map(s -> s.affix().name() + "·" + s.tier().tier().zh()).orElse("—");
    }

    /**
     * 孵化时长倍率。
     *
     * <p>这是「越强越久」在抽卡里的实际落点：红卡带 {@code HATCH_TIME_PCT +0.5}，
     * 于是强度直接换算成等待时间。而且它是**累加**的——抽了 3 张红卡就是 +150%，
     * 玩家能清楚感到「我堆强度，就要等更久」。
     */
    public double hatchMultiplier() {
        double m = 1.0;
        for (PickedSlot s : slots) {
            TierData.Drawback d = s.tier().drawback();
            if (d != null && d.isHatchTime()) {
                m += d.value();
            }
        }
        return m;
    }

    /** 是否存在不可修复的部件（红卡常见代价）。 */
    public boolean hasUnrepairablePart() {
        for (PickedSlot s : slots) {
            TierData.Drawback d = s.tier().drawback();
            if (d != null && d.isUnrepairable()) {
                return true;
            }
        }
        return false;
    }

    /** 一行摘要，用于聊天框播报与日志。 */
    public String brief() {
        return productName + "（" + rarity.zh() + "·" + points + "pt）"
                + " " + summaryOf(Layer.CARRIER)
                + "·" + summaryOf(Layer.ELEMENT)
                + "·" + summaryOf(Layer.ATTRIBUTE)
                + "·" + summaryOf(Layer.EFFECT);
    }
}
