package com.orbcraft.fabric.equip;

import java.util.Set;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.item.OrbItems;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 单 / 双手装备互斥规则（方向 A）。
 *
 * <p><b>规则（与用户确认）</b>：盾为单手（副手），可搭配单手主手武器；重锤(hammer)与长矛(spear)
 * 为双手武器，双手武器要求副手为空 —— 副手有任何道具（含盾）都与之冲突；护甲不受限。
 *
 * <p><b>为什么用「每 tick 校验 + 自动纠正」而不是拦截穿戴。</b> 玩家在背包界面把物品拖进装备栏，
 * 走的是 {@code Inventory.setItem}（按格子下标），并不经过 {@code LivingEntity.setItemSlot}，
 * 想用 Mixin 精确拦截要 hook 两套入口且容易误伤正常的整理操作。相比之下，装备是否有效只取决于
 * 「当前这一刻主手 + 副手是什么」，所以每 tick 看一眼、发现冲突就把副手道具收回背包
 * （背包满则掉在脚边），并给一句提示，是最稳、也最不容易把正常操作搞坏的做法。
 *
 * <p><b>为什么只收副手、不收主手的双手武器。</b> 规则约束的是「双手武器不能配副手道具」，
 * 双手武器本身可以正常持有；冲突时让副手让位是唯一符合直觉的纠正方向。
 *
 * <p><b>怎么识别双手武器。</b> 装备落地时 {@link EquipmentDeployer} 把载体短 id（hammer/spear…）
 * 写进 {@code orbcraft:equipment} 组件，这里现读现判，不依赖原版物品类型（因为锤=原版重锤、矛=原版三叉戟，
 * 没法从物品类本身区分是不是本模组的产物）。
 */
public final class EquipmentRules {

    /** 双手武器载体：占主手且要求副手为空。 */
    private static final Set<String> TWO_HANDED = Set.of("hammer", "spear");

    private static boolean initialized;

    private EquipmentRules() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ServerTickEvents.START_SERVER_TICK.register(EquipmentRules::onTick);
    }

    private static void onTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            enforce(player);
        }
    }

    private static void enforce(ServerPlayer player) {
        ItemStack main = player.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
        // 两边任一边空都不可能构成冲突。
        if (main.isEmpty() || off.isEmpty()) {
            return;
        }
        if (!isTwoHanded(main)) {
            return;
        }
        // 双手武器 + 非空副手：把副手道具收回背包（满了就地掉落），并提示玩家。
        ItemStack moved = off.copy();
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (player.getInventory().add(moved)) {
            player.displayClientMessage(
                    Component.literal("§c双手武器需要空副手，已收起副手物品"), true);
        } else {
            player.drop(moved, false);
            player.displayClientMessage(
                    Component.literal("§c双手武器需要空副手，背包已满，副手物品掉落在脚边"), true);
        }
        OrbcraftMod.LOG.debug("[orbcraft] 双手武器冲突纠正 · 玩家 {} · 副手 {} 已收起",
                player.getName().getString(), moved.getItem());
    }

    private static boolean isTwoHanded(ItemStack stack) {
        var data = stack.get(OrbItems.EQUIPMENT);
        if (data == null) {
            return false;
        }
        String carrier = data.getStringOr("carrier", "");
        return TWO_HANDED.contains(carrier);
    }
}
