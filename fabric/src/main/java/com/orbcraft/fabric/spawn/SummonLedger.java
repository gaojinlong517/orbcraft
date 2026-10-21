package com.orbcraft.fabric.spawn;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.orbcraft.fabric.OrbcraftMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 召唤物归属账本 —— {@code entityId → ownerKey}，以及 {@code spec.id() → entityId}。
 *
 * <p><b>为什么不能用内存 Map。</b> 「A 的召唤物 B 不能指挥」和 PVP 友伤判定
 * 都查这里。进程内 Map 在重启后清空，症状是「重启后我的宠物可以被别人指挥」——
 * 而且是静默的：没有任何报错，只有玩家在联机时发现。所以落盘到
 * {@link SavedData}，跟着世界存档走。
 *
 * <p><b>为什么放在主世界的数据存储里。</b> {@code DimensionDataStorage} 是按维度分的，
 * 而召唤物可以跟着玩家过传送门。归属查询的输入只有 {@code entityId}，
 * 没有维度信息，所以只能存在一个全局位置；主世界的数据存储是唯一稳定的那个。
 *
 * <p><b>两张表的作用不同，不要合并：</b>
 * <ul>
 *   <li>{@code owners} 是玩法判定用的（跨重启必须准）。</li>
 *   <li>{@code specs} 是落地幂等用的（同一个球被两条路径触发时只出一只）。
 *       它在重启后也要有效 —— 否则「投出 → 服务器重启 → 区块补偿再次 deploy」
 *       就会召出第二只。</li>
 * </ul>
 *
 * <p><b>已知取舍</b>：只有 {@code despawn} 会清理条目。被别的机制清掉的实体
 * （/kill、区块被删）会留下一条垃圾记录，账本因此单调增长。
 * 没在这里加「定期扫描清理」，是因为「区块未加载」与「实体已死」在账本这一层
 * 分不开 —— 扫一轮会把远处玩家还在用的召唤物误删成「不是你的」，
 * 那比多占几百字节严重得多。
 */
public final class SummonLedger extends SavedData {

    private static final String KEY_OWNERS = "owners";
    private static final String KEY_SPECS = "specs";

    /** 存档文件里的数据 id。改名等于丢掉所有旧存档的归属，别改。 */
    private static final String DATA_ID = "orbcraft_summons";

    /**
     * 结构是两张「字符串 → 字符串」表，没有版本化的数值字段，所以直接用
     * {@link CompoundTag} 承载：{@code unboundedMap} 的键会被 NBT 路径规则限制
     * （{@code .} 与 {@code :} 在 ownerKey/维度前缀里都可能出现），
     * 自己拼 tag 反而更稳。
     *
     * <p>{@code DataFixTypes} 只是跨版本升级时给 DFU 指个方向；
     * 本结构不含任何需要迁移的字段，取一个通用的 saved-data 分类即可。
     */
    private static final Codec<SummonLedger> CODEC =
            CompoundTag.CODEC.xmap(SummonLedger::fromTag, SummonLedger::toTag);

    public static final SavedDataType<SummonLedger> TYPE =
            new SavedDataType<>(DATA_ID, SummonLedger::new, CODEC, DataFixTypes.SAVED_DATA_MAP_DATA);

    /** 数据存储拿不到时的退化容器：玩法照常，但重启会丢归属，且必须留日志。 */
    private static final SummonLedger FALLBACK = new SummonLedger();

    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, String> specs = new HashMap<>();

    public SummonLedger() {
    }

    /**
     * 取当前世界的账本。任何一步失败都退化成内存账本并打 WARN，
     * 绝不抛异常 —— 归属查不到只是玩法降级，抛出去会打断投掷命中的 tick。
     */
    public static SummonLedger of(ServerLevel level) {
        return level == null ? FALLBACK : forServer(level.getServer());
    }

    /**
     * 按服务器取账本。{@code ownedBy(UUID, String)} 没有 level 参数（契约如此），
     * 只能靠服务器级引用拿到同一份账本 —— 所以两个入口必须指到同一个
     * {@link SavedDataType}，否则「刚召的宠物查不到归属」。
     */
    public static SummonLedger forServer(MinecraftServer server) {
        if (server == null) {
            return FALLBACK;
        }
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                return FALLBACK;
            }
            return overworld.getDataStorage().computeIfAbsent(TYPE);
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 召唤归属账本无法落盘，本次会话使用内存账本（重启后归属会丢）：{}",
                    e.toString());
            return FALLBACK;
        }
    }

    public String ownerOf(String entityId) {
        return entityId == null ? null : owners.get(entityId);
    }

    public String entityOfSpec(String specId) {
        return specId == null ? null : specs.get(specId);
    }

    /**
     * 记一次落地。同一时刻只允许一条 {@code spec → entity} 映射，
     * 覆盖表示「上一只已经不存在了」，由调用方判定。
     */
    public void remember(String specId, String entityId, String ownerKey) {
        if (entityId == null || ownerKey == null) {
            return;
        }
        owners.put(entityId, ownerKey);
        if (specId != null) {
            specs.put(specId, entityId);
        }
        setDirty();
    }

    /** 移除一条实体记录，同时清掉指向它的 spec 映射。 */
    public void forget(String entityId) {
        if (entityId == null) {
            return;
        }
        owners.remove(entityId);
        specs.values().removeIf(entityId::equals);
        setDirty();
    }

    private CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        CompoundTag ownerTag = new CompoundTag();
        owners.forEach(ownerTag::putString);
        root.put(KEY_OWNERS, ownerTag);
        CompoundTag specTag = new CompoundTag();
        specs.forEach(specTag::putString);
        root.put(KEY_SPECS, specTag);
        return root;
    }

    private static SummonLedger fromTag(CompoundTag root) {
        SummonLedger ledger = new SummonLedger();
        CompoundTag ownerTag = root.getCompoundOrEmpty(KEY_OWNERS);
        for (String key : ownerTag.keySet()) {
            ownerTag.getString(key).ifPresent(value -> ledger.owners.put(key, value));
        }
        CompoundTag specTag = root.getCompoundOrEmpty(KEY_SPECS);
        for (String key : specTag.keySet()) {
            specTag.getString(key).ifPresent(value -> ledger.specs.put(key, value));
        }
        return ledger;
    }
}
