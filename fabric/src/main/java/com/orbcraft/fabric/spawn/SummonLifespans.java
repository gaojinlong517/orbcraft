package com.orbcraft.fabric.spawn;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.fabric.OrbcraftMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 限时召唤的到期表 —— 「summonLifespan 堆了 N 秒，到点就散」。
 *
 * <p><b>在链路里的位置</b>：{@link VanillaSummonSpawner} 生成限时召唤物时调
 * {@link #register}；本类在服务器 tick 末尾扫一遍，到点的走
 * {@link VanillaSummonSpawner#despawn}（账本、外观、实体一起清）。
 *
 * <p><b>为什么用 tick 时间戳而不是实体 NBT 的 Age。</b> 代理实体是原版生物，
 * {@code Age} 这类字段跟原版行为（幼年、繁殖冷却、物品消失）共用一套 NBT，
 * 往里写自定义值等于和原版逻辑对赌——哪个版本改了语义我们都是最后一个知道的。
 * 跑在服务端的内存表没有这个耦合；代价是重启后计时丢失（见下方已知限制）。
 *
 * <p><b>时钟选 overworld 的 gameTime。</b> 它随存档走、单调递增，且
 * {@code ServerLevel#getGameTime} 在所有维度共享同一值（源自 level.dat 的
 * DayTime/GameTime）。用 {@code System.currentTimeMillis} 反而会在服务器卡顿
 * （TPS 低于 20）时提前散掉——玩家堆的 15 秒会缩水。
 *
 * <p><b>已知限制（记录，不修）</b>：本表不落盘。服务器在限时召唤物存活期间重启，
 * 重启后它变成永久召唤物。发生窗口只有几秒，且「重启后召唤物还在」本身
 * 与账本的持久化行为一致（账本记得它、实体也还在世界里）；要修就得把到期表
 * 塞进 {@code SummonLedger} 的 SavedData，等真有玩家反馈再排期。
 */
final class SummonLifespans {

    /** entity → 到期时的 gameTime。ConcurrentHashMap：注册发生在 spawn 路径，清扫在 tick 路径。 */
    private static final Map<UUID, Long> EXPIRE_AT = new ConcurrentHashMap<>();

    private SummonLifespans() {
    }

    /** 由 {@link VanillaSummonSpawner#init} 调用，主线不直接接触本类。 */
    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SummonLifespans::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EXPIRE_AT.clear());
    }

    /**
     * 登记一只限时召唤物。
     *
     * @param entityId   召唤物 UUID
     * @param expireGameTime 到期时的 overworld gameTime（绝对值，不是剩余量）
     */
    static void register(UUID entityId, long expireGameTime) {
        if (entityId != null) {
            EXPIRE_AT.put(entityId, expireGameTime);
        }
    }

    private static void tick(MinecraftServer server) {
        if (EXPIRE_AT.isEmpty()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        long now = overworld.getGameTime();
        Iterator<Map.Entry<UUID, Long>> it = EXPIRE_AT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) {
                continue;
            }
            it.remove();
            UUID id = e.getKey();
            // despawn 内部自带「实体已经不在了就只清记录」的容错，
            // 所以这里不需要再判断一遍存活
            boolean removed = VanillaSummonSpawner.despawnByRuntime(overworld, id);
            if (removed) {
                OrbcraftMod.LOG.info("[orbcraft] 限时召唤 {} 存续时间到，已消散", id);
            }
        }
    }
}
