package com.orbcraft.core.hatch;

import com.orbcraft.core.spec.Spec;

/**
 * 一张孵化票据 —— 一颗已经落在玩家物品栏里的球的全部可持久化状态。
 *
 * <p>字段刻意压到最少：平台层存档只需要写这 6 个字段（{@code spec} 本体另外按 specId 存），
 * 重启后重新读出来就能继续算进度，不需要任何运行时对象。
 *
 * <p><b>{@code totalSeconds} 与 {@code spec.hatchSeconds()} 的关系</b>：
 * 正常情况下二者相等 —— 祭坛加速是在结算时就把 Spec 的时长改小，而不是"跑得更快"。
 * 之所以还留一个独立字段，是因为进度计算只需要一个 int，
 * 而 {@code HatchSession} 的签名要的就是 (startTick, totalSeconds, nowTick)；
 * 让票据自带一份，平台层就不必每次去取 Spec。构造器会校验二者一致，
 * 免得将来有人只改一边，出现"条走完了但不就绪"。
 *
 * @param ticketId     票据 id，服务内唯一
 * @param specId       产物 id，落地幂等的依据
 * @param ownerKey     持有者
 * @param startTick    进度起点（已被离线封顶修正过，可能晚于真实定稿时刻）
 * @param totalSeconds 实际需要的总秒数（已含祭坛加速）
 * @param state        生命周期状态
 * @param spec         产物规格
 */
public record HatchTicket(
        String ticketId,
        String specId,
        String ownerKey,
        long startTick,
        int totalSeconds,
        HatchState state,
        Spec spec
) {

    public HatchTicket {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId 不能为空");
        }
        if (specId == null || specId.isBlank()) {
            throw new IllegalArgumentException("specId 不能为空");
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        if (!specId.equals(spec.id())) {
            throw new IllegalArgumentException("specId 与 spec.id() 不一致：" + specId + " vs " + spec.id());
        }
        if (startTick < 0) {
            throw new IllegalArgumentException("startTick 不能为负：" + startTick);
        }
        if (totalSeconds < 1) {
            throw new IllegalArgumentException("totalSeconds 必须 >= 1：" + totalSeconds);
        }
        // 上限必须按稀有度校验而不是按 spec.hatchSeconds()：祭坛加速会把时长改小，
        // 但改大（未来的减速 debuff）同样必须被挡在这里，否则 Spec 的构造器会在别处炸。
        if (totalSeconds > spec.rarity().maxHatchSeconds()) {
            throw new IllegalArgumentException("totalSeconds " + totalSeconds
                    + " 超过 " + spec.rarity().zh() + " 的孵化上限 " + spec.rarity().maxHatchSeconds());
        }
    }

    /** 状态迁移：返回新实例，票据本身不可变。 */
    public HatchTicket withState(HatchState newState) {
        return new HatchTicket(ticketId, specId, ownerKey, startTick, totalSeconds, newState, spec);
    }

    /** 0.0 ~ 1.0，直接委托 {@link HatchSession}，两个平台共用同一份进度逻辑。 */
    public double progress(long nowTick) {
        return HatchSession.progress(startTick, totalSeconds, nowTick);
    }

    public boolean isReady(long nowTick) {
        return HatchSession.ready(startTick, totalSeconds, nowTick);
    }

    public long remainingSeconds(long nowTick) {
        return HatchSession.remainingSeconds(startTick, totalSeconds, nowTick);
    }

    /** 物品 lore 用的文字条，例如 {@code ▰▰▰▰▱▱▱▱▱▱ 43% · 2:41}。 */
    public String loreBar(long nowTick) {
        return HatchSession.loreBar(startTick, totalSeconds, nowTick);
    }

    /** 是否还占着"同时孵化"的名额：离手（THROWN）之后就腾出来了。 */
    public boolean occupiesSlot() {
        return state == HatchState.CHARGING || state == HatchState.READY;
    }
}
