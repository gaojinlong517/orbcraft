package com.orbcraft.core.render;

import java.util.List;
import java.util.Set;

/**
 * 单个载体的外观计划 —— 「这个载体长什么样、拿不到自定义模型时退化成什么」。
 *
 * <p>为什么需要它：{@link ModelRequest} 只知道一个裸 modelId，而玩法层说得出的是
 * 「这是一把重锤」。中间的翻译层缺了两件事：
 * <ul>
 *   <li><b>无效 modelId 的拦截点。</b> modelId 由数据文件手写，写错一个字母，
 *       运行时表现为「模型已打包但客户端找不到」，排查成本极高。这里在构造期就对齐命名规则。</li>
 *   <li><b>降级外观。</b> 资源没烘焙时必须退到某个原版实体上，而不是变成隐形碰撞体。
 *       {@link #proxyEntity()} 就是这个退化目标。</li>
 * </ul>
 *
 * <p>命名约定：{@code model_id == "model." + carrier_id}，与生成器写出的
 * {@code appearance_key} 严格一致。这样「词条 → 外观」不需要第二张映射表，
 * 也不会出现两套 id 各自漂移。
 *
 * <p>不可变，可并发读。
 *
 * @param carrierId  载体 id，形如 {@code "hammer"}，对应 {@code affixes.carrier.json} 的
 *                   {@code id} 去掉 {@code "carrier."} 前缀
 * @param modelId    模型标识，必须等于 {@code "model." + carrierId}
 * @param proxyEntity 原版实体 id（含 {@code minecraft:} 前缀），仅作兜底外观
 * @param animations 合法动画名子集；空表示静态模型
 * @param tintable   是否允许用 tintArgb 生成配色变体
 * @param asset      美术工作量分类，决定能否烘焙进首版资源包
 * @param note       一句话说明，面向美术与运维
 */
public record CarrierModelPlan(
        String carrierId,
        String modelId,
        String proxyEntity,
        List<String> animations,
        boolean tintable,
        AssetKind asset,
        String note
) {

    /** modelId 的命名前缀，与生成器的 appearance_key 约定一致。 */
    public static final String MODEL_PREFIX = "model.";

    /** 美术工作量分类。 */
    public enum AssetKind {
        /** 直接复用原版模型与贴图，零美术产出即可上线。 */
        REUSE,
        /** 需要重贴图，但不必新建骨架。 */
        REPLACE,
        /** 需要从零新建模型，首版不可能烘焙完成。 */
        NEW
    }

    /** 允许的动画名。超出这个集合的名字客户端不会认识，等于静默失效。 */
    public static final Set<String> ALLOWED_ANIMATIONS =
            Set.of("idle", "walk", "attack", "death", "special");

    /**
     * 兜底可用的原版实体白名单。
     *
     * <p>限定在 1.20 起长期存在、1.21 仍在的实体：写一个不存在的 id 不会在编译期报错，
     * 只会在召唤时静默生成失败，玩家看到「球炸了」。白名单把这类错误挡在构造期。
     */
    public static final Set<String> VANILLA_PROXIES = Set.of(
            "minecraft:armor_stand",
            "minecraft:item_display",
            "minecraft:block_display",
            "minecraft:marker",
            "minecraft:interaction",
            "minecraft:zombie",
            "minecraft:husk",
            "minecraft:drowned",
            "minecraft:skeleton",
            "minecraft:stray",
            "minecraft:bogged",
            "minecraft:wither_skeleton",
            "minecraft:zombie_villager",
            "minecraft:spider",
            "minecraft:cave_spider",
            "minecraft:silverfish",
            "minecraft:endermite",
            "minecraft:bee",
            "minecraft:parrot",
            "minecraft:bat",
            "minecraft:allay",
            "minecraft:vex",
            "minecraft:phantom",
            "minecraft:breeze",
            "minecraft:blaze",
            "minecraft:magma_cube",
            "minecraft:slime",
            "minecraft:guardian",
            "minecraft:evoker",
            "minecraft:vindicator",
            "minecraft:pillager",
            "minecraft:ravager",
            "minecraft:witch",
            "minecraft:illusioner",
            "minecraft:warden",
            "minecraft:iron_golem",
            "minecraft:snow_golem",
            "minecraft:end_crystal",
            "minecraft:shulker",
            "minecraft:ghast",
            "minecraft:ender_dragon",
            "minecraft:wither",
            "minecraft:wolf",
            "minecraft:cat",
            "minecraft:fox",
            "minecraft:rabbit",
            "minecraft:chicken",
            "minecraft:cow",
            "minecraft:pig",
            "minecraft:sheep",
            "minecraft:goat",
            "minecraft:camel",
            "minecraft:sniffer",
            "minecraft:armadillo",
            "minecraft:horse",
            "minecraft:llama",
            "minecraft:turtle",
            "minecraft:dolphin",
            "minecraft:glow_squid",
            "minecraft:squid",
            "minecraft:axolotl",
            "minecraft:frog",
            "minecraft:strider",
            "minecraft:hoglin",
            "minecraft:zoglin",
            "minecraft:piglin",
            "minecraft:piglin_brute",
            "minecraft:zombified_piglin",
            "minecraft:creeper");

    public CarrierModelPlan {
        if (carrierId == null || carrierId.isBlank()) {
            throw new IllegalArgumentException("carrierId 不能为空");
        }
        String expected = MODEL_PREFIX + carrierId;
        if (modelId == null || !modelId.startsWith(MODEL_PREFIX)) {
            throw new IllegalArgumentException(
                    "载体 " + carrierId + " 的 modelId 必须以 " + MODEL_PREFIX + " 开头，实际 " + modelId);
        }
        if (!expected.equals(modelId)) {
            throw new IllegalArgumentException(
                    "载体 " + carrierId + " 的 modelId 必须等于 " + expected
                            + "（与 appearance_key 对齐），实际 " + modelId);
        }
        if (proxyEntity == null || !VANILLA_PROXIES.contains(proxyEntity)) {
            throw new IllegalArgumentException(
                    "载体 " + carrierId + " 的 proxyEntity 不在原版实体白名单内：" + proxyEntity);
        }
        if (asset == null) {
            throw new IllegalArgumentException("载体 " + carrierId + " 缺少 asset 分类");
        }
        animations = List.copyOf(animations == null ? List.of() : animations);
        for (String a : animations) {
            if (!ALLOWED_ANIMATIONS.contains(a)) {
                throw new IllegalArgumentException(
                        "载体 " + carrierId + " 的动画名非法：" + a + "，只允许 " + ALLOWED_ANIMATIONS);
            }
        }
        note = note == null ? "" : note;
        if (animations.isEmpty() && !note.contains("静态")) {
            throw new IllegalArgumentException(
                    "载体 " + carrierId + " 无动画，note 必须写明「静态」以免被误当成漏配");
        }
    }

    /** 静态模型：没有动画帧，只需一张贴图 / 一个网格。 */
    public boolean isStatic() {
        return animations.isEmpty();
    }

    /**
     * 首版资源包能否烘焙进本模型。
     *
     * <p>{@link AssetKind#NEW} 需要从零建模，首版不可能就位；
     * 它不该被登记进 {@link ModelCatalog}，否则运行时会把「资源缺失」伪装成「已就绪」。
     */
    public boolean baked() {
        return asset != AssetKind.NEW;
    }

    /** 该模型的动画名集合，可直接喂给 {@link ModelCatalog}。 */
    public Set<String> animationSet() {
        return Set.copyOf(animations);
    }
}
