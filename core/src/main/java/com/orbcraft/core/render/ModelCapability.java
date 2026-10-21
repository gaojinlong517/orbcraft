package com.orbcraft.core.render;

/**
 * 外观后端的能力等级。
 *
 * <p>存在的意义不是「描述功能」，而是**让上层知道该不该骗玩家**。
 * 兜底后端能召出实体、能打、能掉血，但长得完全不是玩家造的那个东西。
 * 如果不把这个差别暴露到 UI，玩家的结论会是「这游戏有 bug」，而不是「特效没加载」。
 */
public enum ModelCapability {

    /** 完整自定义模型：BlockBench 骨架 + 动画 + 染色。BetterModel 在场时的状态。 */
    FULL(3),

    /** 原版实体 + 名称颜色 + 粒子。玩法完整，外观退化。 */
    NAMED_VANILLA(2),

    /** 完全不可见，只有一个碰撞体。仅用于自测与压测，不给玩家。 */
    NONE(1);

    private final int rank;

    ModelCapability(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 是否需要向玩家提示「外观已降级」。 */
    public boolean shouldWarnPlayer() {
        return this != FULL;
    }
}
