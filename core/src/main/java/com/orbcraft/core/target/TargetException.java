package com.orbcraft.core.target;

/**
 * 定向卡分层配额中的非法调用。
 *
 * <p>和 {@link com.orbcraft.core.draft.DraftException} 一样只承载**调用方的编程错误**：
 * UI 传了不存在的卡 id、把召唤物卡塞进装备方案、绕过灰化点了第二次。
 * 玩家可以正常触发的分支（当天配额用完）不走异常，{@link TargetAllowance#use} 返回
 * {@code Optional.empty()}，由 UI 灰化按钮。
 */
public class TargetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** 卡表里没有这个 id。 */
        UNKNOWN_CARD,
        /** 卡存在，但不属于本次方案的类型（也不是 ANY 的元素层卡）。 */
        WRONG_KIND,
        /** 同一层出现两张定向卡：一层只能被定向一次，否则「必定出现」会自相矛盾。 */
        LAYER_DUPLICATE,
        /** 同一个 id 出现两次。 */
        CARD_DUPLICATED,
        /** 配额已满。{@link TargetAllowance} 不抛这个码，留给偏好异常风格的适配层使用。 */
        OVER_QUOTA,
        /** 参数非法（null、空格 id、负数配额）。 */
        BAD_ARGUMENT
    }

    private final Code code;
    private final String detail;

    public TargetException(Code code, String detail) {
        super("[" + code + "] " + detail);
        this.code = code;
        this.detail = detail;
    }

    public Code code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
