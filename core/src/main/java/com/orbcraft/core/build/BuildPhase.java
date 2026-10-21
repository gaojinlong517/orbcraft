package com.orbcraft.core.build;

/**
 * 构筑流程的阶段。
 *
 * <p><b>每层拆成两个态，是这套流程唯一不能被简化的地方。</b>
 * 只有 {@link #confirm()} 能推进层，{@code choose()} 只登记意向 ——
 * 如果「选中即推进」，玩家手指抖一下就直接进了下一层，
 * 而且「确认」这个动作在协议里再也没有落点，需求里那条
 * 「任一层级未确认前不可推进」就无从保证。
 *
 * <pre>
 * TYPE_CHOOSING ⇄ TYPE_CONFIRMING
 *        │ confirm
 *        ▼
 * DESC_CHOOSING ⇄ DESC_CONFIRMING
 *        │ confirm
 *        ▼
 * EFFECT_CHOOSING ⇄ EFFECT_CONFIRMING
 *        │ confirm
 *        ▼
 *     FINISHED / CANCELLED
 * </pre>
 *
 * <p>{@code ⇄} 表示同层内可以反复改选：改选不推进，退选到空则回到 {@code *_CHOOSING}。
 */
public enum BuildPhase {

    /** 类型层：尚未选。 */
    TYPE_CHOOSING,
    /** 类型层：已选一类，等玩家确认。 */
    TYPE_CONFIRMING,

    /** 描述卡层：已发牌，尚未选。 */
    DESC_CHOOSING,
    /** 描述卡层：已选，等玩家确认。 */
    DESC_CONFIRMING,

    /** 效果层：已发牌，尚未选。 */
    EFFECT_CHOOSING,
    /** 效果层：已选，等玩家确认。 */
    EFFECT_CONFIRMING,

    /** 已定稿，{@link BuildSession#composition()} 可取结果。 */
    FINISHED,
    /** 玩家主动放弃，不产生任何消耗。 */
    CANCELLED;

    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }

    /** 本层的「选择中」态。 */
    public boolean isChoosing() {
        return this == TYPE_CHOOSING || this == DESC_CHOOSING || this == EFFECT_CHOOSING;
    }

    /** 本层的「待确认」态。 */
    public boolean isConfirming() {
        return this == TYPE_CONFIRMING || this == DESC_CONFIRMING || this == EFFECT_CONFIRMING;
    }

    /** 是否还在构筑中（未结束）。 */
    public boolean isBuilding() {
        return !isTerminal();
    }

    /** 当前层是否属于同一个层级（同层改选时用）。 */
    public int layerIndex() {
        return switch (this) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> 0;
            case DESC_CHOOSING, DESC_CONFIRMING -> 1;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> 2;
            case FINISHED, CANCELLED -> 3;
        };
    }

    /** 本层对应的中文名，用于报错信息。 */
    public String layerZh() {
        return switch (this) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> "类型层";
            case DESC_CHOOSING, DESC_CONFIRMING -> "描述卡层";
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> "效果层";
            case FINISHED, CANCELLED -> "已结束";
        };
    }
}
