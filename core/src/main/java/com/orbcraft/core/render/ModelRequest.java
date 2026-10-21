package com.orbcraft.core.render;

import java.util.UUID;

/**
 * 一次「把外观挂到宿主实体上」的请求。
 *
 * <p>宿主一律是**隐形的原版实体**，它负责寻路、攻击、伤害判定；
 * 外观是挂在它身上的展示层。这个拆分让玩法与美术彻底解耦 ——
 * 外观挂失败，实体照样能打。
 *
 * @param hostEntityId 宿主实体 UUID（隐形原版实体）
 * @param modelId      模型标识，**必须已在 {@link ModelCatalog} 登记的预打包清单内**
 * @param scalePct     缩放百分比，100 = 原始大小
 * @param tintArgb     染色（ARGB），null 表示用模型自带配色。L1 分级靠它产生几百种外观变体
 * @param nameTagVisible 是否显示头顶名称
 */
public record ModelRequest(
        UUID hostEntityId,
        String modelId,
        int scalePct,
        Integer tintArgb,
        boolean nameTagVisible
) {

    public ModelRequest {
        if (hostEntityId == null) {
            throw new IllegalArgumentException("hostEntityId 不能为空，外观必须挂在一个实体上");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        // 上下限与 Spec.scalePct 保持一致，避免两处各有一套边界
        if (scalePct < 25 || scalePct > 400) {
            throw new IllegalArgumentException("scalePct 超出安全范围 25~400：" + scalePct);
        }
    }

    public ModelRequest(UUID hostEntityId, String modelId) {
        this(hostEntityId, modelId, 100, null, true);
    }

    public ModelRequest withTint(Integer argb) {
        return new ModelRequest(hostEntityId, modelId, scalePct, argb, nameTagVisible);
    }

    public ModelRequest withScale(int pct) {
        return new ModelRequest(hostEntityId, modelId, pct, tintArgb, nameTagVisible);
    }
}
