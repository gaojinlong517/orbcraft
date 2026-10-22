package com.orbcraft.fabric.item;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.entity.orb.OrbEntities;
import com.orbcraft.fabric.equip.OrbCasterItem;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * 球物品的注册与安装点 —— 本路（S1）唯一需要主线接线的地方。
 *
 * <p>主线的 {@code OrbcraftMod.onInitialize()} 里加一行：
 * <pre>{@code
 * com.orbcraft.fabric.item.OrbItems.init();
 * }</pre>
 *
 * <p><b>为什么注册写在自己的 init 里而不是塞进 {@code OrbcraftMod}。</b>
 * 五路并行时每个 {@code static final} 注册项都会让 {@code OrbcraftMod} 变成争抢点；
 * 各自暴露一个 {@code init()}，主线只按顺序调，合并时几乎不会冲突。
 *
 * <p><b>为什么 init 是幂等的。</b> 集成服务器里主入口与客户端入口在同一个 JVM 内先后初始化，
 * {@code Registry.register} 同一个 id 第二次会直接抛异常。用一次性的布尔挡住，
 * 重复调用变成无操作而不是启动即崩。
 */
public final class OrbItems {

    /** 注册命名空间。与 {@code fabric.mod.json} 的 {@code id} 一致，改一处等于改两处。 */
    public static final String NAMESPACE = "orbcraft";

    private static final Identifier ITEM_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "orb");
    private static final Identifier TICKET_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "ticket");
    private static final Identifier STAFF_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "staff");
    private static final Identifier GUN_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "gun");

    /**
     * 票据组件 —— 球上唯一的载荷。
     *
     * <p><b>必须同步到客户端</b>，不是因为客户端要用票据算进度，而是因为创造模式里
     * 移动物品时客户端会把自己那份 stack 回传给服务端（{@code ServerboundSetCreativeModeSlotPacket}）。
     * 组件若不同步，客户端手里那一份就没有票据，随便挪一下格子这颗球就废了。
     *
     * <p>用自定义组件而不是 {@code minecraft:custom_data}：{@code custom_data} 是
     * 玩家命令与数据包可以直接改的公共抽屉，把票据放进去等于允许 {@code /data} 换掉产物。
     */
    public static final DataComponentType<CompoundTag> TICKET = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            TICKET_ID,
            DataComponentType.<CompoundTag>builder()
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG)
                    .build());

    /**
     * 装备数据组件 —— 装备上唯一的载荷，内容是 {@code attrs}（属性键 → 数值）。
     *
     * <p><b>为什么装备数值要跟着物品走而不是登记在玩家身上。</b> 装备会被捡起、
     * 丢出、放进箱子、被别人捡走：登记在玩家身上就意味着要在每个容器事件里
     * 追踪一件物品的去向，漏一条就是「装备复制/蒸发」级 bug。挂在物品上，
     * 物品在哪数值就在哪，{@code EquipmentCombat} 受击时现读现算。
     *
     * <p><b>必须同步到客户端</b>，理由与 {@link #TICKET} 相同：创造模式挪格子时
     * 客户端会把物品回传，组件不同步的话挪一下装备数值就没了。
     */
    public static final DataComponentType<CompoundTag> EQUIPMENT = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(NAMESPACE, "equipment"),
            DataComponentType.<CompoundTag>builder()
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG)
                    .build());

    /**
     * 球物品本体。
     *
     * <p>堆叠上限 1：每颗球带自己的票据与进度，堆在一起就不能分别读条了。
     * 这也是「同时最多 5 个在孵」那套限制在物品栏里的自然延伸。
     *
     * <p><b>{@code setId} 不能省。</b> 1.21.4 起 {@code Item.Properties} 必须自带 id，
     * {@code Item} 构造函数会用它算 {@code descriptionId}（默认翻译键）。
     * 不给的话不是「名字显示不出来」，而是构造时就
     * {@code NullPointerException: Item id not set} —— 而且**编译期完全查不出来**，
     * 表现为服务端起不来，堆栈指向静态初始化块。这个坑是跑 {@code runServer} 才暴露的。
     */
    public static final Item ORB = Registry.register(
            BuiltInRegistries.ITEM,
            ITEM_ID,
            new OrbItem(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, ITEM_ID))
                    .stacksTo(1)));

    /**
     * 契约实现。公开成常量是为了让 S5 在需要「最早位置拦截」时能拿到同一个实例 ——
     * 但正常调用请走 {@code Services.orbItem()}，那是全项目约定的取用点。
     */
    public static final OrbItemContractImpl CONTRACT = new OrbItemContractImpl();

    /**
     * 法杖 —— 右键发射魔法弹的武器。{@code baseDamage} 是兜底伤害，
     * 构筑时 {@code damage} 词条会再加成（见 {@link OrbCasterItem}）。
     */
    public static final Item STAFF = Registry.register(
            BuiltInRegistries.ITEM,
            STAFF_ID,
            new OrbCasterItem(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, STAFF_ID)), 4.0F, 1.5F, 16));

    /**
     * 法杖枪 —— 比法杖更快、伤害更高、更准的远程武器。同样的发射机制，参数更激进。
     */
    public static final Item GUN = Registry.register(
            BuiltInRegistries.ITEM,
            GUN_ID,
            new OrbCasterItem(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, GUN_ID)), 6.0F, 2.2F, 12));

    private static boolean installed;

    private OrbItems() {
    }

    /**
     * 注册物品与实体类型，并安装 {@link OrbItemContract}。
     *
     * <p>顺序说明：先触碰 {@link OrbEntities}，因为 {@link OrbItem} 投掷时要引用实体类型；
     * 虽然静态初始化也能自己触发，但显式调一次能让「忘了注册实体」变成一个不可能发生的错误。
     */
    public static void init() {
        if (installed) {
            return;
        }
        installed = true;
        OrbEntities.init();
        Services.install(CONTRACT);
        OrbcraftMod.LOG.info("[orbcraft] 球物品已注册 · 组件 {} · 实体 {}", TICKET_ID, OrbEntities.ID);
    }
}
