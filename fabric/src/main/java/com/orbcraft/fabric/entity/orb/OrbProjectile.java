package com.orbcraft.fabric.entity.orb;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.core.hatch.HatchException;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.render.ModelCapability;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.contract.SummonSpawner;
import com.orbcraft.fabric.item.OrbItems;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 飞出去的球 —— 「票据 THROWN 之后，到落地生效之前」这一小段生命周期的载体。
 *
 * <p><b>在整条链路里的位置</b>：{@code OrbItem.use} 在服务端生成它（携带球物品本身），
 * 命中时 {@link #land()} 调 {@code IncubationService.deploy} 完成 THROWN → DEPLOYED，
 * 再把 {@code Spec} 交给 {@code SummonSpawner}。它自己不决定召什么，也不碰配额。
 *
 * <p><b>为什么继承 {@code ThrowableItemProjectile} 而不是直接用 {@code ThrowableProjectile}。</b>
 * 前者是后者的直接子类，多出来的只有「持有一个 ItemStack 并能被渲染成物品」这一点能力。
 * 有了它，飞行中的球不需要任何模型/贴图资源就能被玩家看见（渲染的就是球物品本身），
 * 而且<b>落盘时球物品会跟着实体一起存</b> —— 服务器重启后仍能靠它取回票据，
 * 不必在实体上另存一份 {@code Spec}（那会变成第二个真相）。
 *
 * <p><b>为什么落地只认 {@code deploy} 的返回值。</b> {@code deploy} 幂等：命中与区块加载补偿
 * 可能各触发一次，返回 {@code false} 就代表「已经落地过」，此时绝不能再召第二只。
 * 票状态只由 {@code IncubationService} 决定，实体不维护自己的「已生效」标记。
 */
public final class OrbProjectile extends ThrowableItemProjectile {

    /**
     * 最长飞行 tick。球卡在未加载区块的边界、或者飞进虚空时不会有任何命中回调，
     * 票据会永远停在 THROWN（球已从背包扣掉）—— 那等于玩家的产物凭空消失。
     * 30 秒足够飞出任何正常投掷距离。
     */
    private static final int MAX_FLIGHT_TICKS = 20 * 30;

    /** 已经收到过「外观降级」提示的玩家。见 {@link #tellAppearance}。 */
    private static final Set<UUID> APPEARANCE_WARNED = ConcurrentHashMap.newKeySet();

    private static final String TAG_TICKET = "TicketId";
    private static final String TAG_OWNER = "OwnerKey";

    /** 票据 id。落地的唯一依据，缺失时这颗实体什么都不做。 */
    private String ticketId = "";

    /**
     * 落地已开始。命中回调与超时兜底都可能在同一 tick 触发，
     * {@code isRemoved()} 要等到 {@code discard()} 之后才为真，中间这段用这个标记挡住重入。
     */
    private boolean landed;

    /**
     * 归属。不从 {@code getOwner()} 现取：落地时主人可能已经下线或换了维度，
     * 而 {@code SummonSpawner} 需要的是稳定的 ownerKey（UUID 字符串），不是实体引用。
     */
    private String ownerKey = "";

    public OrbProjectile(EntityType<? extends OrbProjectile> type, Level level) {
        super(type, level);
    }

    /**
     * 出手用。{@code stack} 必须是把球从背包里复制出来的那一份 ——
     * 它既是飞行中的渲染素材，也是落盘后取回票据的唯一依据。
     */
    public OrbProjectile(EntityType<? extends OrbProjectile> type, double x, double y, double z, Level level,
            ItemStack stack) {
        super(type, x, y, z, level, stack);
    }

    /** 由 {@code OrbItem} 在出手时写入；之后再改没有意义。 */
    public void bind(String ticketId, String ownerKey) {
        this.ticketId = ticketId == null ? "" : ticketId;
        this.ownerKey = ownerKey == null ? "" : ownerKey;
    }

    /**
     * 飞行中显示球物品。
     *
     * <p>返回的不是 {@code OrbItems.ORB} 的实例而是类型：{@code ThrowableItemProjectile}
     * 用它在反序列化/客户端生成时补一个默认 stack；真正的 stack 是出手时 {@code setItem}
     * 写进去的那一份（带票据），所以这里给个空壳就够。
     */
    @Override
    protected Item getDefaultItem() {
        return OrbItems.ORB;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.tickCount > MAX_FLIGHT_TICKS) {
            // 超时按「就地落地」处理，而不是丢弃：玩家至少还能拿到产物。
            // 这条必须留日志 —— 「球飞出去没反应」是联调时最难判的一类现象，
            // 而这句能直接区分「被超时兜底了」还是「落地被拒了」。
            OrbcraftMod.LOG.warn("[orbcraft] 球飞行 {} tick 仍未命中，按就地落地处理（ticket={}）",
                    this.tickCount, this.ticketId);
            this.land(this.position());
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        // 命中点在方块表面上，而实体有 0.35 的碰撞箱，直接用交点会让召唤物陷进方块里。
        // 沿命中面的法向推 0.3 格（大于碰撞箱半宽 0.175），让落点站在方块外侧 ——
        // 贴地/避障是 S3 的边界之外，落点该怎么给由调用方负责。
        Vec3 pos = hit.getLocation();
        if (hit instanceof BlockHitResult blockHit) {
            pos = pos.add(blockHit.getDirection().getUnitVec3().scale(0.3D));
        }
        this.land(pos);
    }

    /**
     * 落地结算。三条出口都在这里：命中、超时、以及「票据已经落地过」的幂等返回。
     *
     * <p>整段不抛异常：它在实体 tick 与命中回调里执行，抛出去会打断整个 tick，
     * 症状是「服务器卡一下然后这个操作消失了」。
     */
    private void land(Vec3 pos) {
        if (this.level().isClientSide() || this.isRemoved() || this.landed) {
            return;
        }
        this.landed = true;
        ServerLevel level = (ServerLevel) this.level();

        // 球物品就是票据的载体：飞行中被 /kill 或重启打断时，这是唯一还活着的证据。
        HatchTicket carried = OrbItems.CONTRACT.readOrb(this.getItem()).orElse(null);
        String id = carried != null ? carried.ticketId() : this.ticketId;
        if (id.isEmpty()) {
            this.discard();
            return;
        }

        boolean first;
        try {
            first = OrbcraftRuntime.hatch().deploy(id, OrbcraftRuntime.nowTick(level.getServer()));
        } catch (HatchException e) {
            if (e.code() != HatchException.Code.UNKNOWN_TICKET) {
                // 未投出就落地 / 票据已失效：真正的状态错乱，记日志并让球消失比留在世界里安全。
                OrbcraftMod.LOG.warn("[orbcraft] 球落地被拒（{}）：{}", e.code(), e.getMessage());
                this.discard();
                return;
            }
            // 票据表里查不到 != 这次落地是假的：它多半来自重启前的 THROWN ——
            // IncubationService.restore 只接在途票据（CHARGING/READY），飞行中的票不会被恢复。
            // 此时球上携带的票据是唯一证据，按它兑现一次，而不是让玩家看到「球飞出去就没了」。
            // 代价：这条分支没有 deploy 的幂等保护（服务里本来就没有这张票），只能靠
            // 落盘后实体自身的存活与 isRemoved 防重入。正解是 core 增加一个「只登记不占栏」
            // 的 THROWN 恢复入口，那是 core 的改动，不属于 S1。
            OrbcraftMod.LOG.warn("[orbcraft] 票据 {} 不在服务内（多半是重启丢了 THROWN 状态），按球上携带的票据兑现一次", id);
            first = true;
        }

        if (!first) {
            // 已经落地过。重复命中的正常现象，不是错误。
            this.discard();
            return;
        }

        // 票据已经落地：标脏让存档在几秒内落盘。崩服时若没有这条，配额与票据会退回
        // 「已投出但没落地」，产物与配额对不上，而日志里什么都看不到。
        markDirtyQuietly();

        // 服务里的票据是唯一真相，球上那份只在服务查不到时兜底。
        summon(level, pos, OrbcraftRuntime.hatch().ticket(id).orElse(carried));
        this.discard();
    }

    /** 标脏。S4 未接线时静默跳过：那说明整条存档链路都不在，喊一声也没有接收方。 */
    private void markDirtyQuietly() {
        try {
            Services.playerStore().markDirty(this.ownerKey);
        } catch (IllegalStateException e) {
            // 内存状态照样推进，只是这次迁移不会立刻落盘。
        }
    }

    /**
     * 转交 S3。这里只负责「在哪个位置、给谁」，属性映射与外观选择都在 {@code SummonSpawner} 里。
     */
    private void summon(ServerLevel level, Vec3 pos, HatchTicket ticket) {
        if (ticket == null) {
            tell("球里的记录读不出来，这颗球没能生效");
            return;
        }
        Spec spec = ticket.spec();
        SummonSpawner.SpawnResult result;
        try {
            result = Services.summonSpawner().spawn(level, pos, spec, this.ownerKey);
        } catch (IllegalStateException e) {
            // S3 还没接线。这一句会在 tick 里抛，必须就地转成玩家可见的原因。
            OrbcraftMod.LOG.warn("[orbcraft] 召唤接口未注册：{}", e.getMessage());
            tell("召唤功能还没接上，这颗球先收回");
            return;
        }

        if (!result.spawned()) {
            tell("落地失败：" + result.reason());
            return;
        }
        // 外观降级的唯一发送方是这里。S3 只负责如实报「玩家实际看到」的等级，不发消息 ——
        // 24/35 个载体没有烘焙模型，如果谁都不说，玩家看到的是一只奇怪的僵尸，
        // 结论会是「这模组有 bug」，而不是「美术还没做完」。
        if (result.appearance().shouldWarnPlayer()) {
            tellAppearance(spec, result.appearance());
        }
    }

    /**
     * 每人每局只提示一次外观降级。
     *
     * <p>现在没有任何 BetterModel 适配器，{@code capability()} 恒为 {@code NAMED_VANILLA}，
     * 也就是<b>每只</b>召唤物都会降级。逐只提示会立刻变成刷屏噪声，玩家反过来会关掉提示。
     * 这份集合刻意不落盘：重启后每人再收到一次是可接受的代价，换来的是不写存档。
     */
    private void tellAppearance(Spec spec, ModelCapability capability) {
        if (!(this.getOwner() instanceof ServerPlayer player)) {
            // 投掷者不在线就不发，也不记「已提示」—— 否则他一辈子都收不到。
            return;
        }
        String name = spec.displayName();
        String message = switch (capability) {
            case NAMED_VANILLA -> "「" + name + "」的外观模型没打包，这次先用原版形态代替，数值不受影响";
            case NONE -> "「" + name + "」的外观完全用不了，这次召唤物是隐形的，数值不受影响";
            case FULL -> null;
        };
        if (message == null || !APPEARANCE_WARNED.add(player.getUUID())) {
            return;
        }
        player.displayClientMessage(Component.literal(message), true);
    }

    private void tell(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Entity owner = this.getOwner();
        if (owner instanceof ServerPlayer player) {
            player.displayClientMessage(Component.literal(message), false);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString(TAG_TICKET, this.ticketId);
        out.putString(TAG_OWNER, this.ownerKey);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        this.ticketId = in.getStringOr(TAG_TICKET, "");
        this.ownerKey = in.getStringOr(TAG_OWNER, "");
    }
}
