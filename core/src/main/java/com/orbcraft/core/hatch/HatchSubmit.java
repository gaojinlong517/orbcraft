package com.orbcraft.core.hatch;

import java.util.Map;

import com.orbcraft.core.build.Composition;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.spec.BehaviorMode;

/**
 * 一次「定稿并投料」的请求 —— {@link IncubationService} 唯一接受的输入。
 *
 * <p>它刻意和 {@code Composition} 分开，因为定稿那一刻玩家还要额外回答三个问题，
 * 而这三个答案都不属于构筑：
 * <ul>
 *   <li>{@code behavior} —— 这只召唤物是打手、守卫还是混乱体（决定配额档）</li>
 *   <li>{@code material} —— 投的是试作料还是大师料（决定材料消耗倍率）</li>
 *   <li>{@code onAltar} —— 是否摆上祭坛（决定孵化时长是否减半）</li>
 * </ul>
 * 把这三个塞进 {@code Composition} 会让「构筑」变成「下单」，
 * 抽卡结果就不再可复现了 —— 同一个种子应该永远得到同一个组合。
 *
 * @param ownerKey       玩家标识，参与 Spec id 生成；不能为空
 * @param composition    已定稿的构筑结果
 * @param behavior       行为模式；装备 / 消耗品必须传 {@link BehaviorMode#NONE}
 * @param material       材料投入档，把 {@code extraMaterials} 按档位倍率放大
 * @param extraMaterials 基础配方（如 {@code {"iron": 4}}）；结算后会写进 Spec 的 materialCost，
 *                       由平台层真正扣除；null 视作空表
 * @param onAltar        是否祭坛加速
 */
public record HatchSubmit(
        String ownerKey,
        Composition composition,
        BehaviorMode behavior,
        MaterialTier material,
        Map<String, Integer> extraMaterials,
        boolean onAltar
) {

    public HatchSubmit {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey 不能为空：它参与 Spec id 生成，缺失会让两位玩家的产物撞 id");
        }
        if (composition == null) {
            throw new IllegalArgumentException("composition 不能为空");
        }
        if (behavior == null) {
            throw new IllegalArgumentException("behavior 不能为空（装备 / 消耗品请显式传 NONE）");
        }
        if (material == null) {
            throw new IllegalArgumentException("material 不能为空");
        }
        extraMaterials = extraMaterials == null ? Map.of() : Map.copyOf(extraMaterials);
    }

    /** 最常用形态：无额外材料、不上祭坛。装备 / 消耗品走这个。 */
    public static HatchSubmit of(String ownerKey, Composition composition,
                                 BehaviorMode behavior, MaterialTier material) {
        return new HatchSubmit(ownerKey, composition, behavior, material, Map.of(), false);
    }

    /** 玩家在确认界面勾掉/勾上祭坛时用，避免平台层手写六参数构造。 */
    public HatchSubmit withAltar(boolean altar) {
        return new HatchSubmit(ownerKey, composition, behavior, material, extraMaterials, altar);
    }
}
