package com.orbcraft.fabric.equip;

/**
 * 装备球落地：把 {@code Spec} 变成一件真实可用的原版物品。
 *
 * <p><b>为什么需要这个类。</b> 构筑流程能造出装备球（{@code ProductType.EQUIPMENT}），
 * 但投出去之后没有任何落地实现 —— 装备球要么被拦在手里，要么飞出去凭空蒸发。
 * 这一版补上「落地 → 产出物品 → 进背包」这条链路。
 *
 * <p><b>它不做的事</b>（都是有意的，不是遗漏）：
 * <ul>
 *   <li>不消耗配额。配额在 {@code submit} 时已经扣过，落地只是兑现。</li>
 *   <li>不判断归属。归属由 {@code OrbItemContract.checkThrow} 在投掷时拦一次，
 *       落地时票据里的 {@code ownerKey} 就是投掷者。</li>
 *   <li>不做自定义外观。原版物品是占位，等美术资源就位再换。</li>
 * </ul>
 *
 * <p><b>已知的单位口径</b>（与召唤物一致）：{@code damage} / {@code maxHealth} / {@code armor} /
 * {@code knockback} / {@code reach} 是绝对值，直接加；{@code moveSpeed} / {@code attackSpeed}
 * 是百分比（数据里的 desc 带 {@code %}），按 {@code value / 100} 做乘法。
 * {@code critChance} / {@code critDamage} / {@code lifesteal} 原版没有对应属性，
 * 这里不硬塞（塞了也不会生效），只记一条 debug，等伤害事件那一轮再补。
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.item.OrbItems;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

/** 装备落地的唯一实现。入口只有 {@link #deploy}。 */
public final class EquipmentDeployer {

    /** 落地结果。{@code reason} 是给玩家看的中文，失败时才非空。 */
    public record Result(boolean deployed, String reason) {

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * 载体短 id → 原版物品。
     *
     * <p>这 12 个 id 来自 {@code affixes.carrier.json} 里 {@code base_type=EQUIPMENT} 的条目。
     * 原版没有「枪 / 法杖 / 匕首 / 拳套 / 盾」这几类，所以一部分只能找最近的替代品，
     * 标了 {@code placeholder=true} 的会在 lore 里如实写一句「外观为占位」——
     * 与其让玩家以为「我的拳套怎么是块铁」，不如直接说清楚。
     */
    private record BaseItem(Item item, EquipmentSlotGroup group, boolean placeholder) {
    }

    private static final Map<String, BaseItem> BASES = new LinkedHashMap<>();

    static {
        BASES.put("sword", new BaseItem(Items.IRON_SWORD, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("axe", new BaseItem(Items.IRON_AXE, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("hammer", new BaseItem(Items.MACE, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("spear", new BaseItem(Items.TRIDENT, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("bow", new BaseItem(Items.BOW, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("crossbow", new BaseItem(Items.CROSSBOW, EquipmentSlotGroup.MAINHAND, false));
        BASES.put("shield", new BaseItem(Items.SHIELD, EquipmentSlotGroup.OFFHAND, false));
        BASES.put("armor", new BaseItem(Items.IRON_CHESTPLATE, EquipmentSlotGroup.ARMOR, false));
        // 以下四个原版没有对应物，选最近的替代品并标记为占位。
        BASES.put("gun", new BaseItem(Items.CROSSBOW, EquipmentSlotGroup.MAINHAND, true));
        BASES.put("staff", new BaseItem(Items.BLAZE_ROD, EquipmentSlotGroup.MAINHAND, true));
        BASES.put("dagger", new BaseItem(Items.IRON_SWORD, EquipmentSlotGroup.MAINHAND, true));
        BASES.put("gauntlet", new BaseItem(Items.IRON_INGOT, EquipmentSlotGroup.MAINHAND, true));
    }

    /**
     * 一个属性键怎么落到原版属性上。
     *
     * @param label    给玩家看的中文名，写进 lore
     * @param attribute 原版属性
     * @param operation {@code ADD_VALUE} 加绝对量；{@code ADD_MULTIPLIED_TOTAL} 按比例
     * @param percent   true 表示数据里的值已经是百分比（如 {@code moveSpeed=8} 即 +8%）
     */
    private record AttrRule(String label, Holder<Attribute> attribute,
                            AttributeModifier.Operation operation, boolean percent) {
    }

    private static final Map<String, AttrRule> RULES = new LinkedHashMap<>();

    static {
        RULES.put("damage", new AttrRule("攻击力", Attributes.ATTACK_DAMAGE,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("maxHealth", new AttrRule("生命上限", Attributes.MAX_HEALTH,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("armor", new AttrRule("护甲", Attributes.ARMOR,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("armorToughness", new AttrRule("护甲韧性", Attributes.ARMOR_TOUGHNESS,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("knockback", new AttrRule("击退", Attributes.ATTACK_KNOCKBACK,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("reach", new AttrRule("触及距离", Attributes.ENTITY_INTERACTION_RANGE,
                AttributeModifier.Operation.ADD_VALUE, false));
        RULES.put("moveSpeed", new AttrRule("移动速度", Attributes.MOVEMENT_SPEED,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, true));
        RULES.put("attackSpeed", new AttrRule("攻击速度", Attributes.ATTACK_SPEED,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, true));
        // 硬直抵抗 = 被打时少被击退。原版没有「硬直」，击退抗性是最贴近的落地，
        // 换算口径与召唤物侧 SummonAttrMap.staggerResist 一致（百分比 → 0~1）
        RULES.put("staggerResist", new AttrRule("硬直抵抗", Attributes.KNOCKBACK_RESISTANCE,
                AttributeModifier.Operation.ADD_VALUE, true));
        RULES.put("impactPower", new AttrRule("冲击力", Attributes.ATTACK_KNOCKBACK,
                AttributeModifier.Operation.ADD_VALUE, false));
    }

    /**
     * 格挡类词条：原版没有对应属性，走 {@code EquipmentCombat} 的受击事件结算，
     * 这里只负责把词条写进 lore 让玩家看得见。
     *
     * <p>语义口径（与卡面 desc 对齐，改前先看卡表）：
     * <ul>
     *   <li>{@code blockChance} 格挡率 —— 受击自动格挡的概率，格挡 = 本次伤害归零；</li>
     *   <li>{@code blockPower} 格挡强度 —— 举盾格挡（原版右键）时额外减免的百分比；</li>
     *   <li>{@code parryChance} 招架率 —— 受击自动招架的概率，招架 = 伤害归零 + 攻击者短暂虚弱；</li>
     *   <li>{@code shieldAmount} 护盾值 —— 优先于生命的吸收池，脱战缓慢回充；</li>
     *   <li>{@code thorns} 荆棘 —— 受击时反弹给攻击者的固定伤害；</li>
     *   <li>{@code resist} 抗性 —— 所有伤害的固定百分比减免。</li>
     * </ul>
     * 格挡/招架共用一个随机判定顺序：先判格挡，没中再判招架。
     */
    private static final Map<String, String> COMBAT_KEYS = new LinkedHashMap<>();
    private static final Set<String> PERCENT_KEYS = Set.of(
            "blockChance", "blockPower", "parryChance", "resist");

    static {
        COMBAT_KEYS.put("blockChance", "格挡率");
        COMBAT_KEYS.put("blockPower", "格挡强度");
        COMBAT_KEYS.put("parryChance", "招架率");
        COMBAT_KEYS.put("shieldAmount", "护盾值");
        COMBAT_KEYS.put("thorns", "荆棘");
        COMBAT_KEYS.put("resist", "抗性");
    }

    private EquipmentDeployer() {
    }

    /**
     * 在 {@code pos} 兑现一件装备。
     *
     * <p>投掷者在线就进背包（满了就地掉落）；不在线就掉在落点，等他回来捡。
     * 两种情况都不丢东西 —— 装备是玩家花了配额换来的，静默消失比数值错了更难受。
     */
    public static Result deploy(ServerLevel level, Vec3 pos, Spec spec, String ownerKey) {
        if (spec == null) {
            return Result.fail("记录里没有装备信息");
        }
        if (spec.type() != ProductType.EQUIPMENT) {
            return Result.fail("这不是装备");
        }

        String carrier = carrierOf(spec).orElse(null);
        BaseItem base = carrier == null ? null : BASES.get(carrier);
        if (base == null) {
            // 数据漂移：装备球里出现了没登记过的载体。给一件最普通的剑，别让玩家白投一次。
            OrbcraftMod.LOG.warn("[orbcraft] 装备落地遇到未知载体 · spec={} · tags={} · 已回退为铁剑",
                    spec.id(), spec.tags());
            base = new BaseItem(Items.IRON_SWORD, EquipmentSlotGroup.MAINHAND, true);
        }

        ItemStack stack = new ItemStack(base.item());
        List<Component> lore = new ArrayList<>();

        ItemAttributeModifiers.Builder mods = ItemAttributeModifiers.builder();
        List<String> unapplied = new ArrayList<>();
        boolean anyApplied = false;
        int index = 0;
        for (Map.Entry<String, Double> entry : spec.attrs().entrySet()) {
            AttrRule rule = RULES.get(entry.getKey());
            if (rule == null) {
                // 格挡类词条由 EquipmentCombat 处理、耐久单独处理：这两者不算「未落地」
                if (!COMBAT_KEYS.containsKey(entry.getKey()) && !"durability".equals(entry.getKey())) {
                    unapplied.add(entry.getKey());
                }
                continue;
            }
            double raw = entry.getValue();
            if (raw == 0.0) {
                continue;
            }
            double amount = rule.percent() ? raw / 100.0 : raw;
            // id 必须唯一且稳定：同一件装备重进世界时，属性不能被重复叠加。
            Identifier id = Identifier.fromNamespaceAndPath("orbcraft",
                    "equip/" + spec.id() + "/" + entry.getKey() + "/" + index++);
            mods.add(rule.attribute(),
                    new AttributeModifier(id, amount, rule.operation()),
                    base.group());
            anyApplied = true;
            // lore 里显示原始口径（+8% 就写 8%），不显示换算后的 0.08。
            lore.add(Component.literal((raw > 0 ? "+" : "") + trim(raw)
                    + (rule.percent() ? "% " : " ") + rule.label()));
        }
        if (anyApplied) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, mods.build());
        }

        // ---- 格挡类词条：不落属性，写 lore + 存进组件，由 EquipmentCombat 受击时结算
        List<String> combatApplied = new ArrayList<>();
        for (Map.Entry<String, Double> entry : spec.attrs().entrySet()) {
            String label = COMBAT_KEYS.get(entry.getKey());
            if (label == null) {
                unapplied.add(entry.getKey());
                continue;
            }
            double raw = entry.getValue();
            if (raw == 0.0) {
                continue;
            }
            combatApplied.add(entry.getKey());
            lore.add(Component.literal((raw > 0 ? "+" : "") + trim(raw)
                    + (PERCENT_KEYS.contains(entry.getKey()) ? "% " : " ") + label));
        }

        // ---- 数值跟着物品走：EquipmentCombat 受击时现读现算，物品在哪数值就在哪
        if (!combatApplied.isEmpty()) {
            net.minecraft.nbt.CompoundTag attrs = new net.minecraft.nbt.CompoundTag();
            for (String key : combatApplied) {
                attrs.putDouble(key, spec.attrs().get(key));
            }
            net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
            data.put("attrs", attrs);
            stack.set(OrbItems.EQUIPMENT, data);
        }

        // 耐久词条：按比例放大原版耐久上限（替代品也有耐久，跟着放大即可）
        double durabilityPct = spec.attrs().getOrDefault("durability", 0.0);
        if (durabilityPct != 0.0 && stack.getMaxDamage() > 0) {
            int scaled = Math.max(1, (int) Math.round(stack.getMaxDamage() * (1.0 + durabilityPct / 100.0)));
            stack.set(DataComponents.MAX_DAMAGE, scaled);
            lore.add(Component.literal("+" + trim(durabilityPct) + "% 耐久"));
        }

        stack.set(DataComponents.ITEM_NAME, Component.literal(spec.displayName()));
        stack.set(DataComponents.RARITY, vanillaRarity(spec.rarity()));

        if (spec.flavor() != null && !spec.flavor().isBlank()) {
            lore.add(Component.literal(spec.flavor()));
        }
        if (base.placeholder()) {
            lore.add(Component.literal("外观为占位（原版物品），数值已按构筑生效"));
        }
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }

        if (!unapplied.isEmpty()) {
            // 不静默：玩家堆了暴击却没效果时，至少日志里查得到原因。
            OrbcraftMod.LOG.debug("[orbcraft] 装备 {} 有未落地的属性键：{}", spec.id(), unapplied);
        }

        return handOver(level, pos, stack, ownerKey, spec);
    }

    /** 交给投掷者；不在线就掉在落点。 */
    private static Result handOver(ServerLevel level, Vec3 pos, ItemStack stack,
                                   String ownerKey, Spec spec) {
        ServerPlayer player = onlinePlayer(level, ownerKey);
        if (player != null) {
            if (player.getInventory().add(stack)) {
                player.displayClientMessage(
                        Component.literal("「" + spec.displayName() + "」已入手"), true);
            } else {
                // 背包满：掉在脚下而不是丢掉。创造模式玩家也可能塞满背包。
                player.drop(stack, false);
                player.displayClientMessage(
                        Component.literal("背包满了，「" + spec.displayName() + "」掉在脚边"), true);
            }
            return Result.ok();
        }

        // 投掷者不在线：掉在落点，**允许任何人捡**。
        // 这里刻意不设 setNeverPickUp：锁死的话这件装备就永久躺在世界里，玩家回来也拿不到。
        // 代价是可能被别人捡走，但那比「消失了」好 —— 至少东西还在服里，管理员能查。
        ItemEntity drop = new ItemEntity(level, pos.x, pos.y + 0.2, pos.z, stack);
        level.addFreshEntity(drop);
        OrbcraftMod.LOG.info("[orbcraft] 投掷者不在线，装备「{}」已掉落在 {} {} {}",
                spec.displayName(), pos.x, pos.y, pos.z);
        return Result.ok();
    }

    private static ServerPlayer onlinePlayer(ServerLevel level, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(ownerKey);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            // 必须同一个维度：跨维度投掷时玩家在另一个 level，背包仍然能收，但掉落位置要跟着 level。
            return player;
        } catch (IllegalArgumentException e) {
            // ownerKey 不是 UUID：契约保证它由 ownerKeyOf 生成，走到这里说明有人绕过了契约。
            OrbcraftMod.LOG.warn("[orbcraft] 装备落地时 ownerKey 不是 UUID：{}", ownerKey);
            return null;
        }
    }

    /** 从 tags 里挑载体短 id：只认登记过的，避免把元素 id 当成载体。 */
    private static Optional<String> carrierOf(Spec spec) {
        List<String> tags = spec.tags();
        if (tags == null) {
            return Optional.empty();
        }
        for (String tag : tags) {
            if (tag != null && BASES.containsKey(tag)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    private static net.minecraft.world.item.Rarity vanillaRarity(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> net.minecraft.world.item.Rarity.COMMON;
            case RARE -> net.minecraft.world.item.Rarity.UNCOMMON;
            case EPIC -> net.minecraft.world.item.Rarity.RARE;
            case LEGENDARY -> net.minecraft.world.item.Rarity.EPIC;
        };
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
