package com.orbcraft.core.spec;

import java.util.List;
import java.util.Map;

/**
 * 召唤物规格 —— 整个系统唯一的产物契约。
 * 生成侧（规则引擎 / LLM）、配额、孵化、落地器全部围绕它工作。
 *
 * @param id          唯一标识，用于落地幂等（同一个 id 只能兑现一次）
 * @param displayName 显示名
 * @param type        产物类型
 * @param rarity      稀有度
 * @param behavior    行为模式（装备/消耗品填 NONE）
 * @param hatchSeconds 孵化时长（秒）
 * @param materialCost 材料消耗
 * @param attrs        已结算的游戏数值（伤害/护甲/速度等）
 * @param tags         语义标签（火、剑、吸血 …），供外观与特效匹配
 * @param flavor       描述文本
 * @param slotId       外观槽位（0 表示由落地器按规则挑选）
 * @param scalePct     缩放百分比（100 = 原始大小）
 */
public record Spec(
        String id,
        String displayName,
        ProductType type,
        Rarity rarity,
        BehaviorMode behavior,
        int hatchSeconds,
        Map<String, Integer> materialCost,
        Map<String, Double> attrs,
        List<String> tags,
        String flavor,
        int slotId,
        int scalePct
) {

    public Spec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("spec id 不能为空，否则无法保证落地幂等");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 不能为空");
        }
        if (hatchSeconds < 1) {
            throw new IllegalArgumentException("hatchSeconds 必须 >= 1");
        }
        if (hatchSeconds > rarity.maxHatchSeconds()) {
            throw new IllegalArgumentException(
                    "hatchSeconds " + hatchSeconds + " 超过 " + rarity.zh() + " 上限 " + rarity.maxHatchSeconds());
        }
        boolean staticType = type == ProductType.EQUIPMENT || type == ProductType.CONSUMABLE;
        if (staticType && behavior != BehaviorMode.NONE) {
            throw new IllegalArgumentException(type.zh() + " 的行为模式必须是 NONE");
        }
        materialCost = materialCost == null ? Map.of() : Map.copyOf(materialCost);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (scalePct < 25 || scalePct > 400) {
            throw new IllegalArgumentException("scalePct 超出安全范围 25~400");
        }
    }

    /** 便捷构造：无材料消耗、默认槽位与缩放。 */
    public static Spec simple(String id, String displayName, ProductType type, Rarity rarity,
                              BehaviorMode behavior, int hatchSeconds,
                              Map<String, Double> attrs, List<String> tags, String flavor) {
        return new Spec(id, displayName, type, rarity, behavior, hatchSeconds,
                Map.of(), attrs, tags, flavor, 0, 100);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** 是否是会长期占用召唤配额的产物。 */
    public boolean isSummon() {
        return type == ProductType.ENTITY || type == ProductType.NPC;
    }

    public Spec withAttrs(Map<String, Double> newAttrs) {
        return new Spec(id, displayName, type, rarity, behavior, hatchSeconds,
                materialCost, newAttrs, tags, flavor, slotId, scalePct);
    }

    public Spec withHatchSeconds(int seconds) {
        return new Spec(id, displayName, type, rarity, behavior, seconds,
                materialCost, attrs, tags, flavor, slotId, scalePct);
    }
}
