package com.orbcraft.core.draft;

import java.util.Objects;

/**
 * 定向卡：指定某一层必定出现某个词条。
 *
 * <p><b>只保类型，不保强度。</b> {@code qualityFloor} 必须是 {@code NONE}：
 * 一旦允许「必定有红龙」，玩家买到的就是产物本身，抽卡会当场退化成点菜，
 * 前面几层的随机性全部失去意义。
 *
 * <p>它还必须**在发牌前使用**（见 {@link DraftSession#useTargetCard}）：
 * 允许看完牌再补一张，等于免费保底，随机性会被买穿。
 */
public record TargetCard(
        String id,
        Layer targetLayer,
        String targetAffix,
        String qualityFloor,
        String name,
        String baseType
) {

    public TargetCard {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetLayer, "targetLayer");
        if (targetAffix == null || targetAffix.isBlank()) {
            throw new IllegalArgumentException("定向卡 " + id + " 未指定 target_affix");
        }
    }

    /** 是否违反「只保类型不保强度」。 */
    public boolean guaranteesQuality() {
        return qualityFloor != null
                && !qualityFloor.isBlank()
                && !"NONE".equalsIgnoreCase(qualityFloor);
    }

    @Override
    public String toString() {
        return name == null || name.isBlank() ? id : name;
    }
}
