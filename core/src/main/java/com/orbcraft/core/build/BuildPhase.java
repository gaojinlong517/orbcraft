package com.orbcraft.core.build;

/**
 * 构筑流程的阶段。
 *
 * <p><b>每层拆成两个态，是这套流程唯一不能被简化的地方。</b>
 * 只有 {@link BuildSession#confirm()} 能推进层，{@code choose()} 只登记意向 ——
 * 如果「选中即推进」，玩家手指抖一下就直接进了下一层，
 * 而且「确认」这个动作在协议里再也没有落点，需求里那条
 * 「任一层级未确认前不可推进」就无从保证。
 *
 * <pre>
 * TYPE_CHOOSING     ⇄ TYPE_CONFIRMING       类型层    3 选 1，固定顺序
 *        │ confirm
 *        ▼
 * SHAPE_CHOOSING    ⇄ SHAPE_CONFIRMING      形态层    发 5 张载体，选 1
 *        │ confirm   → 用已确认的形态过滤修饰池
 *        ▼
 * MODIFIER_CHOOSING ⇄ MODIFIER_CONFIRMING   修饰层    发 5 张元素/属性，选 1
 *        │ confirm
 *        ▼
 * EFFECT_CHOOSING   ⇄ EFFECT_CONFIRMING     效果层    发 5 张效果，选 1
 *        │ confirm
 *        ▼
 *     FINISHED / CANCELLED
 * </pre>
 *
 * <p>{@code ⇄} 表示同层内可以反复改选：改选不推进，退选到空则回到 {@code *_CHOOSING}。
 *
 * <p><b>形态层与修饰层为什么不是一层。</b> 修饰词的 {@code carrier_allow} 是相对载体判定的，
 * 所以修饰池必须先知道形态才能过滤。混在一层发牌，那 5 张修饰就只能按「候选里的载体」过滤 ——
 * 玩家如果选了另一张形态，手里有一半修饰牌是不适用的。拆成两层后，
 * 修饰牌在结构上<b>不可能</b>早于形态确认出现。
 */
public enum BuildPhase {

    /** 类型层：尚未选。 */
    TYPE_CHOOSING,
    /** 类型层：已选一类，等玩家确认。 */
    TYPE_CONFIRMING,

    /** 形态层：已发载体牌，尚未选。 */
    SHAPE_CHOOSING,
    /** 形态层：已选 1 张形态，等玩家确认。 */
    SHAPE_CONFIRMING,

    /** 修饰层：已发元素/属性牌，尚未选。 */
    MODIFIER_CHOOSING,
    /** 修饰层：已选，等玩家确认。 */
    MODIFIER_CONFIRMING,

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
        return this == TYPE_CHOOSING || this == SHAPE_CHOOSING
                || this == MODIFIER_CHOOSING || this == EFFECT_CHOOSING;
    }

    /** 本层的「待确认」态。 */
    public boolean isConfirming() {
        return this == TYPE_CONFIRMING || this == SHAPE_CONFIRMING
                || this == MODIFIER_CONFIRMING || this == EFFECT_CONFIRMING;
    }

    /** 是否还在构筑中（未结束）。 */
    public boolean isBuilding() {
        return !isTerminal();
    }

    /** 当前层是否属于同一个层级（同层改选时用）。 */
    public int layerIndex() {
        return switch (this) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> 0;
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> 1;
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> 2;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> 3;
            case FINISHED, CANCELLED -> 4;
        };
    }

    /** 本层对应的中文名，用于报错信息与状态栏。 */
    public String layerZh() {
        return switch (this) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> "类型层";
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> "形态层";
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> "修饰层";
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> "效果层";
            case FINISHED, CANCELLED -> "已结束";
        };
    }
}
