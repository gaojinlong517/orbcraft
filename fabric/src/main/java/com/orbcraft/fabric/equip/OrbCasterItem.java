package com.orbcraft.fabric.equip;

import com.orbcraft.fabric.entity.orb.OrbBolt;
import com.orbcraft.fabric.entity.orb.OrbEntities;

import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * 法杖 / 枪这类「右键发射魔法弹」的武器。
 *
 * <p><b>为什么需要一个自定义 {@code Item} 而不是用原版物品占位。</b>
 * 原版物品（烈焰棒 / 弩）的 {@code use()} 是定死的，无法塞进「造一颗弹丸实体」的逻辑。
 * 法杖/枪的核心玩法就是远程攻击，必须有一个能重写 {@link #use} 的载体。
 *
 * <p><b>伤害从武器属性现读。</b> 构筑时 {@link EquipmentDeployer} 会把 {@code damage} 词条
 * 写进物品的 {@code ATTACK_DAMAGE} 属性；发射弹丸时在这里把那个值折算成弹丸伤害，
 * 这样「堆了攻击力的法杖，弹丸也更强」—— 不让攻击力词条只在近战生效。
 *
 * <p><b>武器本身不消耗。</b> 法杖是耐用品不是投掷物：每次右键发射弹丸但武器留在手里，
 * 只加一个短冷却（不同武器冷却不同）。这与雪球/末影珍珠「扔一个少一个」的体验是
 * 刻意不同的 —— 玩家不会在刷怪时突然没杖可用。
 */
public final class OrbCasterItem extends Item {

    private final float baseDamage;
    private final float speed;
    private final int cooldownTicks;

    public OrbCasterItem(Properties properties, float baseDamage, float speed, int cooldownTicks) {
        super(properties);
        this.baseDamage = baseDamage;
        this.speed = speed;
        this.cooldownTicks = cooldownTicks;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // 客户端只回传 PASS，实体由服务端生成后自动同步。
            return InteractionResult.PASS;
        }

        float dmg = baseDamage;
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
                if (entry.attribute() == Attributes.ATTACK_DAMAGE) {
                    dmg += (float) entry.modifier().amount();
                }
            }
        }

        OrbBolt bolt = new OrbBolt(OrbEntities.BOLT, player, level);
        bolt.setBoltDamage(dmg);
        // 沿玩家视线方向发射；inaccuracy 给一点散射。
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, speed, 1.0F);
        level.addFreshEntity(bolt);

        level.playSound(null, player.blockPosition(), SoundEvents.EGG_THROW,
                SoundSource.PLAYERS, 0.6F, 1.0F);
        player.getCooldowns().addCooldown(stack, cooldownTicks);
        return InteractionResult.SUCCESS;
    }
}
