package com.orbcraft.fabric.entity.orb;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.item.OrbItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 法杖 / 枪打出去的魔法弹。
 *
 * <p><b>为什么单独一个实体，而不是复用 {@link OrbProjectile}。</b> 球（{@link OrbProjectile}）
 * 是「票据载体」，它要落地后去查票据、决定召什么；而弹丸是「一次性伤害投射物」，
 * 命中即结算、即消散，两者生命周期完全不同，硬塞进同一个类只会让两个职责互相污染。
 *
 * <p><b>为什么继承 {@code ThrowableItemProjectile}。</b> 它自带「持有一个物品并能被渲染成物品」，
 * 于是弹丸不需要任何模型资源就能显示出来 —— 这里复用球的贴图当占位外观
 * （{@link #getDefaultItem()} 返回 {@link OrbItems#ORB}），等美术资源就位再换。
 *
 * <p><b>为什么直线飞行（无重力）。</b> 魔法弹的轨迹应该是「指哪打哪」，带重力会下坠、
 * 手感像雪球而不是法术弹。所以构造里直接关掉重力。
 *
 * <p><b>伤害从武器现读。</b> 弹丸不存自己的数值逻辑，只带一个 {@code damage} 字段，
 * 发射时由 {@code OrbCasterItem} 从武器上的 {@code ATTACK_DAMAGE} 属性现算出来写进来，
 * 落盘时一起存，保证重启后伤害不变。
 */
public final class OrbBolt extends ThrowableItemProjectile {

    /** 没有攻击力属性时的兜底伤害，避免「打出来 0 伤害」让玩家以为没生效。 */
    private static final float DEFAULT_DAMAGE = 3.0F;

    private static final int MAX_FLIGHT_TICKS = 20 * 8;
    private static final String TAG_DAMAGE = "Damage";

    /** 这次命中的基础伤害（已含武器攻击力加成），由发射方写入。 */
    private float damage = DEFAULT_DAMAGE;

    public OrbBolt(EntityType<? extends OrbBolt> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public OrbBolt(EntityType<? extends OrbBolt> type, LivingEntity owner, Level level) {
        // ThrowableItemProjectile 的(LivingEntity, Level)构造必须带一个 ItemStack：
        // 传 EMPTY 即可，弹丸外观由 getDefaultItem() 的球贴图决定。
        super(type, owner, level, ItemStack.EMPTY);
        this.setNoGravity(true);
    }

    /** 发射方在 addFreshEntity 之前调用，把武器攻击力折算进伤害。 */
    public void setBoltDamage(float damage) {
        this.damage = damage;
    }

    /** 飞行中显示球贴图（占位），等美术资源再换。 */
    @Override
    protected Item getDefaultItem() {
        return OrbItems.ORB;
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (this.level().isClientSide()) {
            return;
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            // 打在方块上：消散即可，不需要特效（占位阶段）。
            this.discard();
            return;
        }
        if (hit instanceof EntityHitResult ehr) {
            Entity target = ehr.getEntity();
            if (target instanceof LivingEntity victim && victim != this.getOwner()) {
                // 用魔法伤害结算：与暴击/荆棘同源，不会被「格挡类」随机否决（那是装备受击特效，
                // 只对 victim 是玩家且来源有攻击者时生效，这里 victim 通常是怪物，不受影响）。
                victim.hurtServer((ServerLevel) this.level(),
                        this.level().damageSources().magic(), this.damage);
                OrbcraftMod.LOG.debug("[orbcraft] 弹丸命中 {} · 伤害 {}",
                        victim.getName().getString(), this.damage);
            }
        }
        // 命中即消散，不在世界里留投射物。
        this.discard();
    }

    @Override
    public void tick() {
        super.tick();
        // 直线飞行也可能飞进虚空 / 未加载区块，没有命中回调，必须一个超时兜底，
        // 否则实体永远停在 THROWN 状态的逻辑外（它本来就不是票据），只会白白占实体预算。
        if (!this.level().isClientSide() && this.tickCount > MAX_FLIGHT_TICKS) {
            this.discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putFloat(TAG_DAMAGE, this.damage);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        this.damage = in.getFloatOr(TAG_DAMAGE, DEFAULT_DAMAGE);
    }
}
