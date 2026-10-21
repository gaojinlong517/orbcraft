package com.orbcraft.fabric.item;

import java.util.List;

import com.orbcraft.core.hatch.HatchSession;
import com.orbcraft.core.hatch.HatchState;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.spec.Rarity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * 球的外观写入点 —— 名字、稀有度色、耐久条、lore。
 *
 * <p><b>在整条链路里的位置</b>：{@code OrbItemContractImpl.createOrb} 造球时调 {@link #stamp}，
 * {@code OrbItem.inventoryTick} 每个 tick 调 {@link #refresh}。
 *
 * <p><b>为什么全部走数据组件。</b> 1.21.5 起物品的条与文本都是组件驱动的：
 * 覆写 {@code Item#getBarWidth}/{@code isBarVisible} 那一套已经被渲染端绕过，
 * 改了也不显示。所以这里只写组件：
 * <ul>
 *   <li>{@code MAX_DAMAGE} 固定 {@link HatchSession#BAR_MAX}，{@code DAMAGE} 由
 *       {@link HatchSession#durabilityDamage} 从满值递减到 0 —— 原版耐久条会自然从空填满，
 *       形成「充能」的观感，不需要任何资源包。这是 {@code core} 已经定好的约定，不要改数值。</li>
 *   <li>{@code ITEM_NAME} 而不是 {@code CUSTOM_NAME}：后者会被渲染端强制斜体，
 *       球的产物名不该看起来像玩家用铁砧改的名字。</li>
 * </ul>
 *
 * <p><b>为什么每次都要比对再写。</b> {@code ItemStack#set} 会把组件表标脏，
 * 而服务端每 tick 对比前后两份 stack 决定要不要发包。每个 tick 无条件重写会让客户端的
 * 物品栏一直处于「有变化」状态，白白多出 20 次/秒的同步开销。
 */
final class OrbDisplay {

    private OrbDisplay() {
    }

    /**
     * 写入不会随时间变化的部分：显示名与稀有度颜色。
     *
     * <p>稀有度只做「core 稀有度 → 原版颜色档位」的翻译，不重新判定任何东西。
     */
    static void stamp(ItemStack stack, HatchTicket ticket) {
        stack.set(DataComponents.ITEM_NAME, Component.literal(ticket.spec().displayName()));
        stack.set(DataComponents.RARITY, vanillaRarity(ticket.spec().rarity()));
        stack.set(DataComponents.MAX_DAMAGE, HatchSession.BAR_MAX);
    }

    /**
     * 写入会随时间变化的部分：耐久条与两行 lore。
     *
     * @param nowTick 与 {@code IncubationService} 同一个时钟（{@code Level#getGameTime()}），
     *                否则「条满了但投不出去」这种错位会天天出现
     */
    static void refresh(ItemStack stack, HatchTicket ticket, long nowTick) {
        int damage = HatchSession.durabilityDamage(ticket.startTick(), ticket.totalSeconds(), nowTick);
        if (stack.getOrDefault(DataComponents.MAX_DAMAGE, -1) != HatchSession.BAR_MAX) {
            stack.set(DataComponents.MAX_DAMAGE, HatchSession.BAR_MAX);
        }
        if (stack.getOrDefault(DataComponents.DAMAGE, -1) != damage) {
            stack.set(DataComponents.DAMAGE, damage);
        }

        List<Component> lines = lines(ticket, nowTick);
        ItemLore current = stack.get(DataComponents.LORE);
        if (current == null || !current.lines().equals(lines)) {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
    }

    /**
     * 两行 lore：第一行是身份，第二行是「现在能不能扔」。
     *
     * <p>进度条文字直接取 {@link HatchTicket#loreBar}：百分比、剩余秒数、十格条三者
     * 必须由同一处算出，否则玩家会看到「99%」配「0:00」。
     */
    private static List<Component> lines(HatchTicket ticket, long nowTick) {
        Component identity = Component
                .literal(ticket.spec().rarity().zh() + " · " + ticket.spec().type().zh())
                .withStyle(ChatFormatting.DARK_GRAY);

        Component status;
        if (isReady(ticket, nowTick)) {
            status = Component.literal("已充满 · 右键投出").withStyle(ChatFormatting.GREEN);
        } else if (ticket.state() == HatchState.CHARGING) {
            status = Component.literal(ticket.loreBar(nowTick)).withStyle(ChatFormatting.AQUA);
        } else {
            status = Component.literal("这颗球已经不在等待").withStyle(ChatFormatting.DARK_GRAY);
        }
        return List.of(identity, status);
    }

    /**
     * 「条已经满了」而不是「状态已经是 READY」。
     *
     * <p>两次 tick 扫描之间玩家右键是常态（{@code tick} 一秒只把状态推一次，
     * 而进度是按 tick 算的），只看状态会让球在满进度时继续显示「还差 0 秒」。
     */
    private static boolean isReady(HatchTicket ticket, long nowTick) {
        return ticket.state() == HatchState.READY
                || (ticket.state() == HatchState.CHARGING && ticket.isReady(nowTick));
    }

    /**
     * core 四档 → 原版四档。原版只有 COMMON/UNCOMMON/RARE/EPIC，
     * 而 core 是 普通/稀有/史诗/传说，逐档上移一位即可（传说用 EPIC 的紫色）。
     */
    private static net.minecraft.world.item.Rarity vanillaRarity(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> net.minecraft.world.item.Rarity.COMMON;
            case RARE -> net.minecraft.world.item.Rarity.UNCOMMON;
            case EPIC -> net.minecraft.world.item.Rarity.RARE;
            case LEGENDARY -> net.minecraft.world.item.Rarity.EPIC;
        };
    }
}
