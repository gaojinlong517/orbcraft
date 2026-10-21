package com.orbcraft.core.settle;

import java.util.Map;

import com.orbcraft.core.spec.BehaviorMode;

/**
 * 结算请求 —— 抽卡结果之外、结算时才知道的那些输入。
 *
 * <p>为什么这些不能从 {@code DraftResult} 里推出来：抽卡的四层是「载体 / 元素 / 属性 / 效果」，
 * 没有任何一层表达「这只召唤物是打手还是守卫」，也没有表达「花了多少材料」。
 * 前者由玩家在定稿后单独指定（和层 0 选基调同一性质的选择），后者由经济系统给。
 *
 * @param ownerKey      玩家标识（UUID 字符串或名字）。**必须参与 id 生成**：
 *                      两只玩家用同样的种子抽出同样配方时，id 必须不同，
 *                      否则落地幂等会让第二件永远无法兑现。
 * @param behavior      召唤物行为模式；装备与消耗品必须传 {@link BehaviorMode#NONE}
 * @param material      材料投入档，决定抽卡预算与本件产物的材料消耗
 * @param baseMaterials 基础配方；会按档位倍率放大后写入 Spec
 */
public record SettleRequest(
        String ownerKey,
        BehaviorMode behavior,
        MaterialTier material,
        Map<String, Integer> baseMaterials
) {

    public SettleRequest {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new SettleException("ownerKey 不能为空：它参与 id 生成，缺失会导致多位玩家的产物撞 id");
        }
        if (behavior == null) {
            behavior = BehaviorMode.NONE;
        }
        if (material == null) {
            material = MaterialTier.MASTERWORK;
        }
        baseMaterials = baseMaterials == null ? Map.of() : Map.copyOf(baseMaterials);
    }

    /** 最简请求：只给玩家与材料档，装备/消耗品用这个即可。 */
    public static SettleRequest of(String ownerKey, MaterialTier material) {
        return new SettleRequest(ownerKey, BehaviorMode.NONE, material, Map.of());
    }

    /** 召唤物请求：必须显式指定行为模式。 */
    public static SettleRequest summon(String ownerKey, BehaviorMode behavior, MaterialTier material) {
        return new SettleRequest(ownerKey, behavior, material, Map.of());
    }

    public SettleRequest withMaterials(Map<String, Integer> newBaseMaterials) {
        return new SettleRequest(ownerKey, behavior, material, newBaseMaterials);
    }
}
