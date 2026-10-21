/**
 * 球物品 —— 「票据 ↔ 一个能读条、能扔出去的物品」。
 *
 * <p><b>本包对外只开两个口</b>：
 * <ul>
 *   <li>{@link com.orbcraft.fabric.item.OrbItems#init()} —— 注册物品 / 实体类型 / 安装
 *       {@code OrbItemContract} 实现，由 {@code OrbcraftMod.onInitialize()} 调一次。</li>
 *   <li>孵化服务与时钟一律取 {@code com.orbcraft.fabric.runtime.OrbcraftRuntime}
 *       （{@code hatch()} / {@code nowTick(server)}）—— 全服只有那一份实例，
 *       本包<b>不持有</b>、也不再 {@code new IncubationService()}。</li>
 * </ul>
 *
 * <p>其余类都是实现细节：{@code OrbItem}（物品行为）、{@code OrbItemContractImpl}（契约）、
 * {@code OrbDisplay}（名字/进度条/lore 的写入点）。
 *
 * <p><b>为什么球上只存票据，不存 Spec。</b> 见 {@code OrbItemContract} 的类注释：
 * 票据里已经有 {@code spec}，再存一份就有两个真相。进度、稀有度、时长、配额全部由
 * {@code core} 算，本包只负责把它们显示出来。
 */
package com.orbcraft.fabric.item;
