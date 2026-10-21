package com.orbcraft.core.hatch;

/** 孵化球生命周期。落地器必须保证 DEPLOYED 只到达一次（按 specId 幂等）。 */
public enum HatchState {

    /** 正在充能，物品栏显示进度条。 */
    CHARGING,
    /** 充满，可以投掷。 */
    READY,
    /** 已投出，等待命中判定。 */
    THROWN,
    /** 已生效，物品消耗完毕。 */
    DEPLOYED,
    /** 失效（校验失败 / 超时未投掷）。 */
    FAILED
}
