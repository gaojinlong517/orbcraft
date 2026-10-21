package com.orbcraft.core.build;

/**
 * 构筑流程的非法操作。
 *
 * <p>用受检的语义（不同 {@link Code}）而不是一句字符串，是因为这些错误最终要
 * 分发到三种完全不同的处理路径：客户端提示、服务端拒绝、以及运营日志告警。
 * 混成一种，日志里就分不出「玩家点太快」和「卡表坏了」。
 */
public class BuildException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码。前四项是玩家可自行纠正的，后四项是必须报警的。 */
    public enum Code {
        /** 当前阶段不允许这个动作。 */
        WRONG_PHASE,
        /** 还没选就点确认。 */
        NO_PENDING_CHOICE,
        /** 选项索引越界。 */
        INDEX_OUT_OF_RANGE,
        /** 选的数量超过本层允许的选取数。 */
        TOO_MANY_CHOICES,
        /** 选的数量不足本层要求的选取数。 */
        NOT_ENOUGH_CHOICES,
        /** 形态层换批次数已用尽 —— 玩家可自行纠正（不换就是了），不是故障。 */
        REROLL_EXHAUSTED,
        /** 同一层选了两张同族的卡 —— 叠两次同一项强化，比拒绝掉危险。 */
        FAMILY_CONFLICT,
        /** 描述卡层缺少载体 —— 没有载体的产物无法生成外观与落地。 */
        CARRIER_REQUIRED,
        /** 候选池过滤后不足以发出本层要求的最小张数。 */
        POOL_TOO_SMALL,
        /** 定向卡与本局类型不符、词条不存在，或在发牌后才提交。 */
        TARGET_REJECTED,
        /** 配置本身不合法（启动期就该校验出来）。 */
        BAD_CONFIG
    }

    private final Code code;

    public BuildException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    @Override
    public String toString() {
        return "BuildException[" + code + "] " + getMessage();
    }
}
