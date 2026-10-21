package com.orbcraft.fabric.item;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.entity.orb.OrbEntities;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
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
     * 球物品本体。
     *
     * <p>堆叠上限 1：每颗球带自己的票据与进度，堆在一起就不能分别读条了。
     * 这也是「同时最多 5 个在孵」那套限制在物品栏里的自然延伸。
     */
    public static final Item ORB = Registry.register(
            BuiltInRegistries.ITEM,
            ITEM_ID,
            new OrbItem(new Item.Properties().stacksTo(1)));

    /**
     * 契约实现。公开成常量是为了让 S5 在需要「最早位置拦截」时能拿到同一个实例 ——
     * 但正常调用请走 {@code Services.orbItem()}，那是全项目约定的取用点。
     */
    public static final OrbItemContractImpl CONTRACT = new OrbItemContractImpl();

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
