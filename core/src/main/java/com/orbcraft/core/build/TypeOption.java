package com.orbcraft.core.build;

import com.orbcraft.core.draft.BaseKind;

/**
 * 类型层的一个候选项。
 *
 * <p>为什么不是直接返回 {@code BaseKind[]}：类型层是流程的第一屏，
 * 而「池子里没有词条」这件事必须在<b>点之前</b>就让玩家看见。
 * 让玩家选了一个造不出来的类型，然后在描述层收到「没有可用词条」，
 * 是这类随机系统里最伤体验的失败方式 —— 他会认为整个系统坏了。
 *
 * @param kind       类型
 * @param available  该类型当前是否有可用词条
 * @param poolSize   该类型过滤后的词条总数（诊断用）
 * @param reason     不可用的原因；可用时为 {@code ""}
 */
public record TypeOption(BaseKind kind, boolean available, int poolSize, String reason) {

    public TypeOption {
        if (kind == null) {
            throw new IllegalArgumentException("TypeOption.kind 不能为空");
        }
        reason = reason == null ? "" : reason;
    }

    /** 卡面标题，如「装备」。 */
    public String title() {
        return kind.zh();
    }

    public static TypeOption of(BaseKind kind, int poolSize) {
        return poolSize > 0
                ? new TypeOption(kind, true, poolSize, "")
                : new TypeOption(kind, false, 0, "词库里没有适用于「" + kind.zh() + "」的词条");
    }
}
