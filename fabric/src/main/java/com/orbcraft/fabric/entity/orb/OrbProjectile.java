package com.orbcraft.fabric.entity.orb;

import com.orbcraft.core.hatch.HatchException;
import com.orbcraft.core.hatch.HatchTicket;
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

    private static final String TAG_TICKET = "TicketId";
    private static final String TAG_OWNER = "OwnerKey";

    /** 票据 id。落地的唯一依据，缺失时这颗实体什么都不做。 */
    private String ticketId = "";

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
            this.land(this.position());
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        // 命中点用 HitResult 的交点而不是实体坐标：方块命中时实体自身还在方块外一截，
        // 直接取坐标会让召唤物站在墙外。
        this.land(hit.getLocation());
    }

    /**
     * 落地结算。三条出口都在这里：命中、超时、以及「票据已经落地过」的幂等返回。
     *
     * <p>整段不抛异常：它在实体 tick 与命中回调里执行，抛出去会打断整个 tick，
     * 症状是「服务器卡一下然后这个操作消失了」。
     */
    private void land(Vec3 pos) {
        if (this.level().isClientSide() || this.isRemoved()) {
            return;
        }
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
            // 重启后 IncubationService.restore 只接在途票据（CHARGING/READY），
            // 飞行中的 THROWN 票不会被恢复。此时球上携带的票据是唯一证据，按它兑现一次，
            // 否则玩家会看到「球飞出去，重启后就没了」。
            OrbcraftMod.LOG.warn("[orbcraft] 票据 {} 不在服务内（多半是重启丢了 THROWN 状态），按球上携带的票据兑现一次", id);
            first = true;
        }

        if (!first) {
            // 已经落地过。重复命中的正常现象，不是错误。
            this.discard();
            return;
        }

        // 服务里的票据是唯一真相，球上那份只在服务查不到时兜底。
        summon(level, pos, OrbcraftRuntime.hatch().ticket(id).orElse(carried));
        this.discard();
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
        // 外观降级提示由 S3 在 spawn 内部发给在线主人，这里不再重复发 ——
        // 同一次召唤弹两条一模一样的「外观已降级」，玩家会以为出了两次问题。
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
