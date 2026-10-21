package com.orbcraft.core.draft;

import java.util.Map;
import java.util.Set;

/**
 * 一个词条（卡表的一行）。品质变体存在 {@link #tiers} 里，一条数据覆盖四档强度。
 *
 * <p>两个过滤字段承担了整个系统的可信度：
 * <ul>
 *   <li>{@code carrierAllow / carrierDeny} —— 选了枪就不该抽到「跳跃高度 +3」</li>
 *   <li>{@code elementAllow} —— 空集表示不限元素</li>
 * </ul>
 * 没有它们，5 选 1 会频繁出现玩家读不懂的选项，那样玩家会判定游戏坏了。
 *
 * @param id           全局唯一，如 {@code attr.damage.gun}
 * @param family       族；**同族在整件产物里只能出现一次**，否则等于把同一选项复制多遍
 * @param layer        所属层
 * @param name         显示名
 * @param baseType     基调约束（EQUIPMENT / CONSUMABLE / ENTITY / ANY）
 * @param carrierAllow 允许的载体（裸 id，如 gun）；空集 = 不限
 * @param carrierDeny  禁止的载体
 * @param elementAllow 允许的元素（裸 id，如 fire）；空集 = 不限
 * @param excludes     与哪些族的词条互斥（完整 family，如 effect.combo）
 * @param weight       抽取权重
 * @param appearanceKey 外观映射 key（模型 / 特效）
 * @param tiers        品质档 → 数值
 */
public record Affix(
        String id,
        String family,
        Layer layer,
        String name,
        String baseType,
        Set<String> carrierAllow,
        Set<String> carrierDeny,
        Set<String> elementAllow,
        Set<String> excludes,
        int weight,
        String appearanceKey,
        Map<Tier, TierData> tiers
) {

    public Affix {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("affix.id 不能为空");
        }
        if (family == null || family.isBlank()) {
            // family 为空会导致去重失效，同族检查变成永远通过，牌面会出现重复项
            throw new IllegalArgumentException("affix.family 不能为空：" + id);
        }
        if (layer == null) {
            throw new IllegalArgumentException("affix.layer 不能为空：" + id);
        }
        carrierAllow = carrierAllow == null ? Set.of() : Set.copyOf(carrierAllow);
        carrierDeny = carrierDeny == null ? Set.of() : Set.copyOf(carrierDeny);
        elementAllow = elementAllow == null ? Set.of() : Set.copyOf(elementAllow);
        excludes = excludes == null ? Set.of() : Set.copyOf(excludes);
        tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
        name = name == null ? id : name;
        baseType = baseType == null ? "ANY" : baseType;
        appearanceKey = appearanceKey == null ? "" : appearanceKey;
    }

    public TierData tierData(Tier tier) {
        return tiers.get(tier);
    }

    public boolean hasTier(Tier tier) {
        return tiers.containsKey(tier);
    }

    /** {@code carrier.gun} → {@code gun}；非载体层返回 null。 */
    public String carrierId() {
        if (layer != Layer.CARRIER) {
            return null;
        }
        return id.startsWith("carrier.") ? id.substring("carrier.".length()) : id;
    }

    /** {@code elem.fire} → {@code fire}；非元素层返回 null。 */
    public String elementId() {
        if (layer != Layer.ELEMENT) {
            return null;
        }
        return id.startsWith("elem.") ? id.substring("elem.".length()) : id;
    }

    public boolean allowsCarrier(String carrierId) {
        if (carrierId == null) {
            return carrierAllow.isEmpty();
        }
        if (carrierDeny.contains(carrierId)) {
            return false;
        }
        return carrierAllow.isEmpty() || carrierAllow.contains(carrierId);
    }

    public boolean allowsElement(String elementId) {
        if (elementAllow.isEmpty()) {
            return true;
        }
        return elementId != null && elementAllow.contains(elementId);
    }

    /**
     * 本词条是否与<b>其中任意一个</b>元素兼容。
     *
     * <p>产物可以同时带两个元素（修饰层双选时），这时效果池必须是
     * 「火能用的」∪「冰能用的」，而不是「既能火又能冰的」——
     * 后者会把 64 个效果族砍到 9 个，且结果取决于玩家先点哪张元素卡。
     *
     * <p>{@code elementIds} 为空表示不按元素过滤（等价于 {@code allowsElement(null)}
     * 在无限制词条上的效果，但语义更明确：空集不是「什么都不匹配」）。
     */
    public boolean allowsAnyElement(Set<String> elementIds) {
        if (elementAllow.isEmpty()) {
            return true;
        }
        if (elementIds == null || elementIds.isEmpty()) {
            return true;
        }
        for (String id : elementIds) {
            if (id != null && elementAllow.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean allowsBaseType(String productType) {
        return "ANY".equals(baseType) || baseType.equals(productType);
    }

    /** 该词条是否与本件产物已选的某个族互斥。 */
    public boolean conflictsWith(Set<String> pickedFamilies) {
        for (String ex : excludes) {
            if (pickedFamilies.contains(ex)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name + "(" + id + ")";
    }
}
