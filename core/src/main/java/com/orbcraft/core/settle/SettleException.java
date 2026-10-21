package com.orbcraft.core.settle;

/**
 * 结算失败 —— 产物无法从抽卡结果转换为可落地的 {@link com.orbcraft.core.spec.Spec}。
 *
 * <p>刻意用受检异常之外的选择：结算失败通常意味着**数据或请求不合法**
 * （比如给装备指定了攻击行为），属于调用方必须修的 bug，而不是需要恢复的运行状态。
 */
public class SettleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SettleException(String message) {
        super(message);
    }

    public SettleException(String message, Throwable cause) {
        super(message, cause);
    }
}
