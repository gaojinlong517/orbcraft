package com.orbcraft.core.hatch;

/**
 * 孵化链路里唯一会抛的异常 —— 所有失败原因都收敛到 {@link Code}。
 *
 * <p>为什么要有 code 而不是只靠 message：这条链路的失败<b>必须能被 UI 分类处理</b>。
 * 「配额不够」要弹出解契界面，「没充满」只需要在动作条上抖一下，
 * 「票据不存在」则是日志里要报警的状态不同步。如果平台层只能靠字符串匹配，
 * 这些分支迟早会被改文案改坏。
 */
public class HatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败原因。平台层按这个分支，不要按 message 分支。 */
    public enum Code {

        /** 统御力 / 类别上限 / 同时孵化上限之外，唯一允许"拒绝但无副作用"的出口。 */
        QUOTA_REJECTED("配额不足"),
        /** 票据已到终态（DEPLOYED / FAILED），不可能再推进。 */
        ALREADY_FINISHED("已结束"),
        /** 状态机不允许这一步（例如没充满就投掷）。 */
        NOT_READY("时机未到"),
        /** 同时孵化的球已达上限，等一个完成或投出去再来。 */
        BAR_FULL("孵化栏已满"),
        /** 票据 id 查不到 —— 通常是存档与内存不同步，属于该报警的状态。 */
        UNKNOWN_TICKET("票据不存在");

        private final String zh;

        Code(String zh) {
            this.zh = zh;
        }

        public String zh() {
            return zh;
        }
    }

    private final Code code;

    public HatchException(Code code, String message) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException("HatchException.code 不能为空");
        }
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** 带 code 前缀的完整信息，直接进日志或调试界面。 */
    public String describe() {
        return "[" + code.name() + "/" + code.zh() + "] " + getMessage();
    }
}
