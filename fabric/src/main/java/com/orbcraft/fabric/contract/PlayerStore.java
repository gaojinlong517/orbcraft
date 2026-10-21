package com.orbcraft.fabric.contract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.orbcraft.core.hatch.HatchSnapshot;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.target.TargetAllowance;

import net.minecraft.nbt.CompoundTag;

/**
 * 存档契约 —— 「一个玩家身上哪些东西必须跨重启活着」。
 *
 * <p><b>实现方：S4 {@code persistence}。调用方：S1 / S2 / S3 / S5 全部。</b>
 *
 * <p><b>为什么 ownerKey 由这里统一定义。</b> 它是 {@code core} 里所有账本的键
 * （{@code QuotaManager}、{@code TargetAllowance}、{@code HatchTicket}）。
 * 各路自己拼一个「玩家名 + 服务器名」之类的字符串，结果必然是同一玩家在两条路上
 * 拿到两套账本：配额看着是满的，实际已经用掉了。这里给唯一实现，禁止各写一份。
 *
 * <p><b>为什么用 UUID 而不是玩家名。</b> 名字会改，也允许重名回收 ——
 * 用名字做键，改名那天玩家的产物和配额全部归零，而且旧名字被别人抢注后会继承他的存档。
 * UUID 是稳定且不可冒用的。
 */
public interface PlayerStore {

    /**
     * 玩家标识的唯一生成规则。<b>全项目只能有这一处</b>。
     *
     * @throws IllegalArgumentException {@code playerId} 为 {@code null}
     */
    static String ownerKeyOf(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId 不能为空：ownerKey 是所有账本的键");
        }
        return playerId.toString();
    }

    /**
     * 读取玩家存档。读不到（新玩家 / 文件损坏 / 版本不认）一律返回
     * {@link PlayerState#fresh}，<b>不返回 {@code null}</b>：
     * 每个调用方都写一遍判空，迟早有一处漏掉。
     *
     * <p>本方法不得有副作用，也不得因为存档内容异常而抛异常 ——
     * 它在登录事件里被调用，抛出去等于玩家进不了服。
     */
    PlayerState load(UUID playerId);

    /**
     * 写回玩家存档。实现方自己决定何时落盘（登录 / 退出 / 定时），
     * 但必须保证<b>不会写出一半</b>：写临时文件再原子替换，或用 MC 自带的
     * {@code SavedData} 一次性写整个 {@code CompoundTag}。截断的存档会让
     * {@code IncubationService.restore} 走到「按票据修配额」分支 —— 那是兜底，不是常规路径。
     */
    void save(UUID playerId, PlayerState state);

    /**
     * 票据的 NBT 编解码。<b>全项目只有这一份布局。</b>
     *
     * <p>球物品（{@link OrbItemContract}）与玩家存档用的是同一张票据，
     * 两处各写一份 NBT 布局的后果是：球读得出、存档读不出，或者反过来 ——
     * 重启后玩家手里那个球变成一个读不出来的坏物品，且没有任何报错。
     */
    OrbCodec codec();

    /**
     * 标记某人的状态已变更，尽快落盘（submit / deploy / 定向卡使用之后调用）。
     *
     * <p>为什么需要这个入口，而不是让调用方直接 {@code save(...)}：{@code save} 要求调用方
     * 先自己组装一份完整的 {@link PlayerState}，而构筑层（{@code command} / {@code ui}）
     * 手里只有 ownerKey，没有配额账本也没有票据表 —— 它唯一知道的事实是「刚才那次操作
     * 改了状态」。让它去拼整份存档，等于让表现层替存储层决定存档内容。
     *
     * <p>为什么不能在实现里同步写盘：这个方法的调用点在命令回调里，同步 IO 会卡主线程
     * （单次落盘要序列化全部在途票据，玩家会看到「打完命令卡半秒」）。实现应记下 ownerKey
     * 并交给定时保存或退出时统一刷。
     *
     * <p>给的是默认空实现：定时保存的实现在最坏情况下只丢最近几分钟的增量，
     * 不值得让每个实现都必须写一遍。但<b>有磁盘落盘的实现必须覆盖它</b>，
     * 否则「玩家造完球立刻关客户端」会丢掉那次扣减 —— 配额扣了、球没造出来。
     *
     * @param ownerKey 见 {@link #ownerKeyOf(UUID)}，不要传玩家名
     */
    default void markDirty(String ownerKey) {
    }

    /**
     * 一个玩家的完整存档内容。
     *
     * @param hatch          配额占用 + 在途票据（原子单位，见 {@link HatchSnapshot}）
     * @param targets        定向卡账本断面；{@code null} 视为空账本
     * @param lastActiveTick 上次在线 tick，用于离线封顶（{@code IncubationService} 的 8 小时上限）
     */
    record PlayerState(HatchSnapshot hatch, List<TargetAllowance.Snapshot> targets, long lastActiveTick) {

        public PlayerState {
            if (hatch == null) {
                throw new IllegalArgumentException("hatch 不能为空：没有它就无法恢复配额与在途票据");
            }
            targets = targets == null ? List.of() : List.copyOf(targets);
            if (lastActiveTick < 0) {
                lastActiveTick = 0L;
            }
        }

        /** 新玩家的空存档。{@code lastActiveTick} 取 0，由调用方在首次登录时补当前 tick。 */
        public static PlayerState fresh(String ownerKey) {
            return new PlayerState(HatchSnapshot.empty(ownerKey), List.of(), 0L);
        }
    }

    /** 票据 → NBT。读失败必须返回 {@link Optional#empty()}，理由同 {@link OrbItemContract#readOrb}。 */
    interface OrbCodec {

        CompoundTag write(HatchTicket ticket);

        Optional<HatchTicket> read(CompoundTag tag);
    }
}
