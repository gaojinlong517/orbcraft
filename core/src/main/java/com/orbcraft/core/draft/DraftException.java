package com.orbcraft.core.draft;

/**
 * 抽卡流程中的非法操作。
 *
 * <p>用异常而不是返回 false，是因为这些都是**调用方的编程错误**（UI 在错误阶段点了按钮），
 * 不是玩家可以触发的正常分支。真正需要提示玩家的分支（预算不够）会返回 false 或由快照暴露，
 * 不走这里。
 */
public class DraftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** 当前阶段不允许这个操作。 */
        WRONG_PHASE,
        /** 选中的牌超出预算。UI 应该提前灰化，走到这里说明灰化逻辑漏了。 */
        BUDGET_EXCEEDED,
        /** 索引越界。 */
        INDEX_OUT_OF_RANGE,
        /** 本件产物已经用过一张定向卡。 */
        TARGET_ALREADY_USED,
        /** 定向卡指定的层不是当前层。 */
        TARGET_WRONG_LAYER,
        /** 定向卡的目标词条不在本层可用池里（基调不符 / 被排除）。**必须在使用时挡住**，
         *  否则玩家会白耗一张卡才发现没用。 */
        TARGET_NOT_IN_POOL,
        /** 定向卡带 quality_floor，违反「只保类型不保强度」。 */
        TARGET_RULE_VIOLATION,
        /** 定稿门槛未满足（至少要载体 + 属性或效果之一）。 */
        FINISH_GATE_NOT_MET,
        /** 残损球不足，不允许透支重抽。 */
        INSUFFICIENT_ORBS,
        /** 池子为空，无法发牌。 */
        POOL_EMPTY
    }

    private final Code code;

    public DraftException(Code code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
