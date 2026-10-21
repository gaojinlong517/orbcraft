package com.orbcraft.core.draft;

/**
 * 抽卡状态机的阶段。
 *
 * <pre>
 * PICK_BASE ──pickBase()──▶ LAYER_READY ──deal()──▶ LAYER_PICKING
 *                              ▲                        │
 *                              │◀────── pick(i) ────────┘
 *                              │                        │
 *                              │                    reroll() 原地重发
 *                              │                        │
 *                              └──── 4 层走完 ──▶ FINISHED
 * </pre>
 *
 * <p>{@code LAYER_READY} 这个状态不能省。它存在的理由只有一个：
 * <b>定向卡必须在发牌前使用</b>。如果把「进入下一层」和「发牌」合成一步，
 * 玩家就会先看到牌再决定要不要用定向卡 —— 那就等于免费保底，随机性被买穿。
 */
public enum DraftPhase {

    /** 还没选基调（装备 / 消耗品 / 召唤物）。 */
    PICK_BASE,
    /** 本层就绪，还没发牌。可在此使用定向卡，然后 deal。 */
    LAYER_READY,
    /** 已发牌，等待选择或重抽。 */
    LAYER_PICKING,
    /** 四层已抽完，等待命名并定稿。 */
    AWAITING_NAME,
    /** 已定稿。 */
    FINISHED,
    /** 已放弃（不消耗额度）。 */
    CANCELLED;

    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }

    /** 抽卡动作是否已经结束（不能再发牌/选牌/重抽）。 */
    public boolean isDraftingOver() {
        return isTerminal() || this == AWAITING_NAME;
    }
}
