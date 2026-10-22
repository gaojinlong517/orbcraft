package com.orbcraft.fabric.entity.orb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * 投掷物的实体类型注册。
 *
 * <p><b>在整条链路里的位置</b>：{@code OrbItem} 右键 → 生成本类的 {@link #PROJECTILE} 实例
 * → 命中后由 {@link OrbProjectile} 调 {@code IncubationService.deploy} 并转交 S3 召唤。
 * 这里只做注册，不含玩法。
 *
 * <p><b>为什么单独一个类</b>：{@code EntityType} 的注册必须在物品注册之前或同批完成，
 * 而物品类又要反过来引用实体类型来生成投掷物。把这个注册点独立出来，
 * 两边都只依赖它，不会绕成静态初始化的环。
 */
public final class OrbEntities {

    /** 与 {@code assets/orbcraft/**} 的命名空间必须一致；改这里等于改存档里的实体 id。 */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("orbcraft", "orb_projectile");

    /**
     * {@code EntityType.Builder.build} 要一个 <b>ResourceKey 而不是 Identifier</b>（1.21.9 起），
     * 而且这个 key 必须与 {@code Registry.register} 用的 key 是同一个，
     * 否则实体的 {@code descriptionId} 会对不上，客户端日志里会出现「无法翻译的实体名」。
     */
    private static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, ID);

    /**
     * 球本身。分类用 {@code MISC}：它只由玩家投掷产生，不参与自然刷怪，
     * 也不该被刷怪上限或生物清除逻辑碰到（派单里没有「球会被刷怪系统回收」这条玩法）。
     */
    public static final EntityType<OrbProjectile> PROJECTILE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            KEY,
            EntityType.Builder.<OrbProjectile>of(OrbProjectile::new, MobCategory.MISC)
                    // 碰撞箱比视觉略小：球的目标是「落地」，卡在方块边缘飞不出去比穿模更烦人。
                    .sized(0.35F, 0.35F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(KEY));

    /** 法杖 / 枪发射的魔法弹。与 {@link #PROJECTILE} 同属 MISC：纯玩家投射物，不参与刷怪。 */
    public static final Identifier BOLT_ID = Identifier.fromNamespaceAndPath("orbcraft", "orb_bolt");

    private static final ResourceKey<EntityType<?>> BOLT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, BOLT_ID);

    public static final EntityType<OrbBolt> BOLT = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            BOLT_KEY,
            EntityType.Builder.<OrbBolt>of(OrbBolt::new, MobCategory.MISC)
                    // 弹丸比球更小：直线飞行，撞到方块边缘就该消散，不需要容错大碰撞箱。
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(BOLT_KEY));

    private OrbEntities() {
    }

    /** 触碰本类的静态初始化即完成注册；由 {@code OrbItems.init()} 调用，保证只有一条接线。 */
    public static void init() {
        // 空方法体：注册发生在类初始化时（上面的 PROJECTILE 字段）。
    }
}
