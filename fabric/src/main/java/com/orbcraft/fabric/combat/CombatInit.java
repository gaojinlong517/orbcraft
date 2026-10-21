package com.orbcraft.fabric.combat;

import java.util.UUID;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.spawn.SummonLedger;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

/**
 * 召唤物的战斗与归属：暴击、吸血结算 + 不打主人。
 *
 * <p><b>为什么暴击放在 {@code AFTER_DAMAGE}。</b> Fabric 的
 * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} 只能否决伤害、不能改数值
 * （签名是 {@code boolean allowDamage(LivingEntity, DamageSource, float)}，
 * 没有输出参数的入口）。要在原版结算之后补一段暴击伤害，只能挂在 {@code AFTER_DAMAGE}。
 * 代价是暴击会显示成「两段伤害」，这是当前 API 下的可见折衷，不是 bug。
 *
 * <p><b>递归风险必须挡。</b> {@code AFTER_DAMAGE} 里再调 {@code hurtServer} 会再次触发
 * 同一个回调；不挡就是每帧无限递归。用 ThreadLocal 标记「正在补暴击伤害」，
 * 只挡本线程这一次调用链。
 */
public final class CombatInit {

    /** 正在补暴击伤害时为 true，防止 AFTER_DAMAGE 递归。 */
    private static final ThreadLocal<Boolean> IN_BONUS = ThreadLocal.withInitial(() -> false);

    private static boolean initialized;

    private CombatInit() {
    }

    /** 主线在 {@code OrbcraftMod.onInitialize()} 里调这一行。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ServerLivingEntityEvents.AFTER_DAMAGE.register(CombatInit::onDamage);
        ServerTickEvents.END_SERVER_TICK.register(CombatInit::clearTargetingOwner);
    }

    /**
     * @param original 原版算出的伤害
     * @param applied  实际造成的伤害（被护甲/抗性削减后、被格挡时为 0）
     */
    private static void onDamage(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source,
                                 float original, float applied, boolean blocked) {
        float amount = blocked ? 0f : applied;
        if (Boolean.TRUE.equals(IN_BONUS.get()) || amount <= 0f) {
            return;
        }
        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity dealer)) {
            return;
        }
        CombatStats stats = CombatStats.of(attacker.getUUID());
        if (stats == null) {
            // 不是登记过的召唤物：原版伤害逻辑照旧，不介入。
            return;
        }

        float multiplier = 1f;
        if (stats.critChance() > 0d
                && attacker.level().getRandom().nextFloat() * 100f < stats.critChance()) {
            multiplier += (float) (stats.critDamage() / 100d);
        }

        if (multiplier > 1f) {
            IN_BONUS.set(true);
            try {
                // 补的是「暴击超出基础值的那一部分」，不是整段再打一次
                float bonus = amount * (multiplier - 1f);
                victim.hurtServer((ServerLevel) victim.level(), source, bonus);
            } finally {
                IN_BONUS.set(false);
            }
        }

        if (stats.lifesteal() > 0d && dealer.getHealth() > 0f) {
            float dealt = Math.min(amount * multiplier, victim.getHealth() + amount * (multiplier - 1f));
            float heal = dealt * (float) (stats.lifesteal() / 100d);
            if (heal > 0f) {
                // 不超过上限：原版 heal 自身会截断，这里再夹一次是为了不误导日志
                dealer.heal(Math.min(heal, dealer.getMaxHealth() - dealer.getHealth()));
            }
        }
    }

    /**
     * 召唤物不许把主人设为目标。
     *
     * <p>代理实体（原版僵尸/卫道士）的目标选择器不认归属，被主人打一下就会还手，
     * 观感是「我养的怪反水」。这里在服务端 tick 末尾清掉「目标是主人」的目标。
     *
     * <p><b>开销自证</b>：只遍历账本里记录过的实体（当前最多 5 个在孵上限 + 已召唤数），
     * 不是每 tick 扫全世界的 Mob。每次只是几次 UUID 比较。
     */
    private static void clearTargetingOwner(MinecraftServer server) {
        SummonLedger ledger = SummonLedger.forServer(server);
        for (String entityId : ledger.entityIds()) {
            Entity entity = find(server, entityId);
            if (!(entity instanceof Mob mob) || mob.getTarget() == null) {
                continue;
            }
            UUID ownerUuid = parse(ledger.ownerOf(entityId));
            if (ownerUuid == null) {
                continue;
            }
            if (ownerUuid.equals(mob.getTarget().getUUID())) {
                mob.setTarget(null);
                OrbcraftMod.LOG.debug("[orbcraft] 召唤物 {} 停止了攻击主人", entityId);
            }
        }
    }

    private static Entity find(MinecraftServer server, String entityId) {
        UUID uuid = parse(entityId);
        if (uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
