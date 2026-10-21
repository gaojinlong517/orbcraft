package com.orbcraft.fabric.contract;

import java.util.Optional;

import com.orbcraft.core.hatch.HatchTicket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 球物品契约 —— 「一个已孵化的产物，是怎么变成一个能扔的球的」。
 *
 * <p><b>实现方：S1 {@code orb-item}。调用方：S2 发放、S5 归属校验、S1 自身。</b>
 * 其余各路只读本接口，不改签名（见 {@code docs/契约冻结与并行边界.md}）。
 *
 * <p>为什么这个接口要存在：球是唯一同时被三条路碰到的对象 ——
 * S2 造完要发一个出去、S1 负责读条与投掷、S5 要拒绝「A 的球 B 扔」。
 * 如果各自持有自己的 {@code ItemStack} 读写逻辑，三方对 NBT 布局的理解必然会分叉，
 * 症状是「A 发的球 S5 认为无主」这种谁都不认账的问题。
 *
 * <p><b>票据是唯一载荷。</b> 不要在球上另存一份 {@code Spec} 的拷贝：
 * {@link HatchTicket} 里已经有 {@code spec}，再存一份就有两个真相 ——
 * 改一处忘另一处时，球的名字和它落地后的属性会对不上，而且没有任何报错。
 */
public interface OrbItemContract {

    /**
     * 按票据造一个球。票据的 {@code state} 决定球的初始状态（通常是 {@code READY}）。
     *
     * <p>不发、不交给玩家 —— 这里只负责「造出这个物品」，放哪由调用方决定。
     * 分开的理由：S2 是给玩家背包，S5 可能塞进掉落物或展示框，不该各写一条造球路径。
     */
    ItemStack createOrb(HatchTicket ticket);

    /**
     * 从物品读出票据。
     *
     * <p>不是球、读出来的票据 id 对不上 {@code spec.id}、或者 NBT 被改坏，一律返回
     * {@link Optional#empty()}，<b>不要抛异常</b>：物品可以经由创造模式、作弊器、
     * 甚至别的模组产生，一个坏物品把服务端 tick 抛崩是最差的结果。
     */
    Optional<HatchTicket> readOrb(ItemStack stack);

    /** 这个物品是不是球。默认实现走 {@link #readOrb}，实现方不必重写。 */
    default boolean isOrb(ItemStack stack) {
        return readOrb(stack).isPresent();
    }

    /**
     * 能否投掷。{@code allowed=false} 时 {@code reason} 必须是可以直接给玩家看的中文，
     * 不能是异常类名或英文状态码。
     *
     * <p>这是「A 的球 B 扔不了」的唯一判定点。判定要覆盖至少三件事：
     * 归属、票据是否 {@code READY}、以及球是否还在同一局里（跨存档的旧球）。
     */
    ThrowCheck checkThrow(ServerPlayer player, ItemStack stack);

    /** 投掷判定的结果。用记录而不是布尔：拒绝原因必须能到玩家眼前，否则等于静默失败。 */
    record ThrowCheck(boolean allowed, String reason) {

        public static ThrowCheck ok() {
            return new ThrowCheck(true, "ok");
        }

        public static ThrowCheck deny(String reason) {
            return new ThrowCheck(false, reason == null || reason.isBlank() ? "现在不能投掷" : reason);
        }
    }
}
