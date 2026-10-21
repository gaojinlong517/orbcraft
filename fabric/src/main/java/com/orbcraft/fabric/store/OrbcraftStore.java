package com.orbcraft.fabric.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.hatch.HatchSnapshot;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.core.target.TargetAllowance;
import com.orbcraft.core.target.TargetException;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 玩家存档 —— 「玩家下线、服务器重启之后，哪些状态必须还在」的唯一实现。
 *
 * <p><b>在链路里的位置。</b> 它一头接 {@code core} 的两个断面
 * （{@link HatchSnapshot}、{@link TargetAllowance.Snapshot}），一头接
 * {@link PlayerStore} 契约。登录时把断面灌回 {@link IncubationService} 与
 * {@link TargetAllowance}，退出/定时/关键变更时把断面写回磁盘。
 *
 * <p><b>孵化服务与定向账本的实例不在本类。</b> 它们由主线的 {@link OrbcraftRuntime} 持有，
 * 这里只做「往那一份状态里 restore / 从那一份状态里取断面」。本类<b>不 new</b>
 * {@link IncubationService}：恢复（这里）与推进（S5 的 tick）必须落在同一个实例上，
 * 两个实例的症状是「登录提示恢复了 3 张票，但进度永远不涨」，而且不报错。
 * {@link #hatch()} 只是为了不改四路已有的调用点，方法体直接转给 runtime。
 *
 * <p><b>三个必须守住的恢复语义</b>（写错会静默改玩法，都是 core 侧实现的）：
 * <ol>
 *   <li><b>只接在途票据</b>（CHARGING / READY）。THROWN 的球已经是世界里的实体，
 *       恢复到孵化栏会让「同时孵化」计数虚高。</li>
 *   <li><b>配额以存盘值为准</b>，不是从票据重算。{@code QuotaManager} 没有 release，
 *       已落地的产物仍占装备位，而它们不在票据列表里；从票据重算等于重启一次就多一件装备。
 *       只有存盘值低于在途票据所需时才会按票据修并把 {@code quotaRepaired} 置真 ——
 *       那个为真说明存档只写了一半，必须打日志。</li>
 *   <li><b>票据序号由 {@code restore} 内部按恢复的 id 自动推进</b>，这里不要自己算，
 *       否则新票据会复用一张还活着的 id，把玩家的球静默换掉。</li>
 * </ol>
 *
 * <p><b>为什么 {@code enter} 只在「本进程第一次见到这个玩家」时恢复。</b>
 * {@code restore} 对同一张票恢复两次会抛「票据 id 重复」——那正说明平台层把存档吃了两遍。
 * 玩家掉线重连时票据还在内存里，再恢复一次就会被判成存档损坏、整份跳过。
 * 所以用一个进程内集合记住已经恢复过谁。
 */
public final class OrbcraftStore implements PlayerStore {

    /** 定时落盘间隔。5 分钟远小于一次崩溃可能丢的进度，也远大于一次写盘的开销。 */
    public static final int SAVE_INTERVAL_TICKS = 20 * 60 * 5;

    /** 定向账本保留天数。每类每日额度不在这里 —— 那是 {@code OrbcraftRuntime} 的数值。 */
    public static final int TARGETS_RETENTION_DAYS = TargetAllowance.DEFAULT_RETENTION_DAYS;

    /** 存档子目录，相对世界根目录。 */
    private static final String DIRECTORY = "orbcraft/players";

    private static final OrbcraftStore INSTANCE = new OrbcraftStore();
    private static final TicketCodec CODEC = new TicketCodec();

    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> hatchRestored = new LinkedHashSet<>();

    private volatile MinecraftServer server;
    private volatile Path playersDir;
    private int lastSaveServerTick;
    private boolean registered;

    private OrbcraftStore() {
    }

    /**
     * 装上契约并注册事件。由 {@code OrbcraftMod.onInitialize()} 调用一次。
     *
     * <p>只做注册，不读盘：此时世界目录还不存在（{@code SERVER_STARTED} 才有），
     * 在客户端也会走到这里，任何 IO 都会在客户端报一串无意义的错。
     */
    public static void init() {
        Services.install(INSTANCE);
        synchronized (INSTANCE) {
            if (INSTANCE.registered) {
                return;
            }
            INSTANCE.registered = true;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(INSTANCE::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(INSTANCE::onServerStopping);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> INSTANCE.onJoin(handler.player, srv));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> INSTANCE.onDisconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(INSTANCE::onEndTick);
    }

    // ================================================================== 契约

    /**
     * 读玩家存档。<b>无副作用</b>：不建会话、不恢复、不写任何东西。
     *
     * <p>读不到（新玩家 / 文件损坏 / 写到一半）一律返回 {@link PlayerState#fresh}，
     * 不返回 {@code null}，也不抛：它在登录事件里，抛出去等于玩家进不了服。
     * 「文件损坏」与「新玩家」在这里看起来一样，区别只在日志里。
     */
    @Override
    public PlayerState load(UUID playerId) {
        return readState(PlayerStore.ownerKeyOf(playerId));
    }

    /**
     * 写回玩家存档。这里做的是<b>合并后落盘</b>，不是覆盖。
     *
     * <p>为什么合并：调用方手里的 {@code state} 往往是几十秒前拼出来的（S2 的界面快照、
     * S5 的事件现场），它可能少了刚提交的一张票、或者少了刚才用掉的一次定向卡。
     * 直接覆盖等于把已经发生的事实写回旧值，症状是「重启后额度回来了」
     * 或「在孵的球没了」，且没有任何报错。合并规则：配额按字段取大、票据按 id 取并集
     * （运行中的状态优先）、定向卡按 (天, 类型) 取大、{@code lastActiveTick} 取大。
     */
    @Override
    public synchronized void save(UUID playerId, PlayerState state) {
        String key = PlayerStore.ownerKeyOf(playerId);
        if (state == null) {
            OrbcraftMod.LOG.warn("保存玩家 {} 的存档时传入 null，已忽略（不该发生，调用方有 bug）", key);
            return;
        }
        PlayerState merged = mergeWithLive(key, state);
        adoptSession(key, merged);
        writeState(key, merged);
    }

    @Override
    public OrbCodec codec() {
        return CODEC;
    }

    // ================================================================== 事件

    private void onServerStarted(MinecraftServer srv) {
        this.server = srv;
        Path dir = srv.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY);
        try {
            Files.createDirectories(dir);
            this.playersDir = dir;
        } catch (IOException e) {
            this.playersDir = null;
            // 目录建不出来时宁可整局不落盘，也不要每 5 分钟刷一条同样的错。
            OrbcraftMod.LOG.error("[orbcraft] 存档目录创建失败，本次运行不会保存任何状态：{}（{}）",
                    dir, e.toString());
        }
        synchronized (this) {
            sessions.clear();
            hatchRestored.clear();
            // 注意：孵化服务是 main 的 OrbcraftRuntime 持有的单例，本类无权也没有办法清空它。
            // 同一 JVM 里换存档（单人退出再进另一个世界）时，上一局的票据会留在那份单例里，
            // 这时 restore 会撞「票据 id 重复」并被下面的 catch 记为错误 —— 这是 runtime 的
            // 生命周期决定，不在这里绕（绕过等于自己造第二份状态）。
            lastSaveServerTick = srv.getTickCount();
        }
        OrbcraftMod.LOG.info("[orbcraft] 玩家存档目录 {}（定时保存每 {} tick）", dir, SAVE_INTERVAL_TICKS);
    }

    private void onServerStopping(MinecraftServer srv) {
        flushOnline(srv);
        OrbcraftMod.LOG.info("[orbcraft] 关服前已写回在线玩家存档");
    }

    private void onEndTick(MinecraftServer srv) {
        int now = srv.getTickCount();
        if (now - lastSaveServerTick < SAVE_INTERVAL_TICKS) {
            return;
        }
        lastSaveServerTick = now;
        flushOnline(srv);
    }

    private void onJoin(ServerPlayer player, MinecraftServer srv) {
        enter(PlayerStore.ownerKeyOf(player.getUUID()), OrbcraftRuntime.nowTick(srv));
    }

    private void onDisconnect(ServerPlayer player) {
        // 退出是最后一次能拿到完整现场的时刻；等关服再统一写会漏掉「退出后、关服前」这段。
        saveNow(player.getUUID());
    }

    private void flushOnline(MinecraftServer srv) {
        long now = OrbcraftRuntime.nowTick(srv);
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            String key = PlayerStore.ownerKeyOf(player.getUUID());
            writeSession(key, now);
        }
    }

    // ================================================================== 会话

    /**
     * 一个玩家在本进程里的会话。<b>只存 {@code lastActiveTick}</b> ——
     * 票据与配额在 {@link OrbcraftRuntime#hatch()}，定向账本在
     * {@link OrbcraftRuntime#allowanceOf}，这里再存一份就是第二个真相。
     */
    private static final class Session {

        private long lastActiveTick;

        private Session(long lastActiveTick) {
            this.lastActiveTick = lastActiveTick;
        }
    }

    private synchronized void enter(String key, long now) {
        if (hatchRestored.add(key)) {
            PlayerState state = readState(key);
            restoreHatch(key, state.hatch());
            // 先装账本再让任何人 allowanceOf 取：allowanceOf 在缺项时发的是一份「额度全满」的新账本，
            // 顺序反了玩家就白拿今天的三次定向。
            installLedger(key, state.targets());
            sessions.put(key, new Session(now));
            return;
        }
        Session session = sessions.get(key);
        if (session == null) {
            sessions.put(key, new Session(now));
            return;
        }
        session.lastActiveTick = now;
    }

    private void restoreHatch(String key, HatchSnapshot snapshot) {
        try {
            IncubationService.RestoreResult result = hatch().restore(snapshot);
            if (result.quotaRepaired()) {
                OrbcraftMod.LOG.warn("[orbcraft] 玩家 {} 的存档不自洽：存盘配额低于在途票据所需，"
                                + "已按票据修复（quotaRepaired=true，生效配额 {}）。"
                                + "这通常意味着上次存档只写了一半，请对照上面「存档不完整」的告警排查。",
                        key, result.quota());
            } else if (result.restoredTickets() > 0) {
                OrbcraftMod.LOG.info("[orbcraft] 玩家 {} 恢复 {} 张在途票据，配额 {}",
                        key, result.restoredTickets(), result.quota());
            }
        } catch (IllegalArgumentException e) {
            // 恢复失败不能挡住登录：玩家的球这次读不出来，但至少能进服。
            OrbcraftMod.LOG.error("[orbcraft] 玩家 {} 的孵化断面无法恢复，已跳过：{}", key, e.getMessage());
        }
    }

    /** 把存档里的定向账本装进 runtime。装不上就保持 runtime 里的现值并留日志。 */
    private static void installLedger(String key, List<TargetAllowance.Snapshot> snapshots) {
        try {
            OrbcraftRuntime.installAllowance(key,
                    TargetAllowance.restore(OrbcraftRuntime.TARGETS_PER_KIND_PER_DAY,
                            TARGETS_RETENTION_DAYS, snapshots));
        } catch (TargetException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 玩家 {} 的定向卡账本读不回来，保留内存里的账本（今天的额度可能不对）：{}",
                    key, e.getMessage());
        }
    }

    private void adoptSession(String key, PlayerState state) {
        installLedger(key, state.targets());
        Session session = sessions.get(key);
        if (session == null) {
            sessions.put(key, new Session(state.lastActiveTick()));
            return;
        }
        session.lastActiveTick = Math.max(session.lastActiveTick, state.lastActiveTick());
    }

    // ================================================================== 读写

    private PlayerState readState(String key) {
        Path dir = this.playersDir;
        if (dir == null) {
            return PlayerState.fresh(key);
        }
        Path file = dir.resolve(fileName(key));
        if (!Files.isRegularFile(file)) {
            return PlayerState.fresh(key);
        }
        try {
            StoreFile.Content content = StoreFile.read(file);
            if (!content.complete()) {
                OrbcraftMod.LOG.warn("[orbcraft] 存档 {} 不完整（上次可能写到一半），按已读到的 {} 段恢复",
                        file.getFileName(), content.frames().size());
            }
            return assemble(key, content.frames());
        } catch (IOException | RuntimeException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 读取存档 {} 失败，按新玩家处理（{}）：{}",
                    file.getFileName(), e.getClass().getSimpleName(), e.getMessage());
            return PlayerState.fresh(key);
        }
    }

    private static PlayerState assemble(String key, List<StoreFile.Frame> frames) {
        long lastActiveTick = 0L;
        QuotaManager.State quota = null;
        List<HatchTicket> tickets = new ArrayList<>();
        List<TargetAllowance.Snapshot> targets = new ArrayList<>();
        boolean sawMeta = false;

        for (StoreFile.Frame frame : frames) {
            switch (frame.kind()) {
                case META -> {
                    String owner = frame.tag().getStringOr("owner", "");
                    if (!key.equals(owner)) {
                        // 文件被改名/放错位置时按存档损坏处理，而不是把它当成这个玩家的。
                        throw new IllegalArgumentException("存档归属 " + owner + " 与文件名 " + key + " 不一致");
                    }
                    lastActiveTick = frame.tag().getLongOr("lastActiveTick", 0L);
                    sawMeta = true;
                }
                case QUOTA -> {
                    CompoundTag tag = frame.tag();
                    quota = new QuotaManager.State(
                            tag.getIntOr("equipment", 0),
                            tag.getIntOr("primary", 0),
                            tag.getIntOr("chaos", 0),
                            tag.getIntOr("points", 0));
                }
                case TICKET -> {
                    Optional<HatchTicket> ticket = CODEC.read(frame.tag());
                    if (ticket.isPresent()) {
                        tickets.add(ticket.get());
                    } else {
                        OrbcraftMod.LOG.warn("[orbcraft] 玩家 {} 的存档里有一张坏票据，已跳过", key);
                    }
                }
                case TARGET -> targets.add(readTarget(key, frame.tag()));
            }
        }

        if (!sawMeta) {
            throw new IllegalArgumentException("缺少 meta 段");
        }
        return new PlayerState(new HatchSnapshot(key, quota, tickets), targets, lastActiveTick);
    }

    private static TargetAllowance.Snapshot readTarget(String key, CompoundTag tag) {
        Map<BaseKind, Integer> used = new LinkedHashMap<>();
        CompoundTag counts = tag.getCompoundOrEmpty("used");
        for (String name : counts.keySet()) {
            BaseKind kind;
            try {
                kind = BaseKind.valueOf(name);
            } catch (IllegalArgumentException e) {
                // 基调被删掉时只丢这一格，不整份存档作废。
                continue;
            }
            int n = counts.getIntOr(name, 0);
            if (n > 0) {
                used.put(kind, n);
            }
        }
        return new TargetAllowance.Snapshot(key, tag.getLongOr("day", 0L), used);
    }

    private synchronized void writeSession(String key, long now) {
        Session session = sessions.get(key);
        if (session == null) {
            return;
        }
        session.lastActiveTick = now;
        writeState(key, snapshotOf(key, session));
    }

    /**
     * 组一份可以落盘的断面。
     *
     * <p><b>先读票据、再读配额，顺序不能反。</b> 两次调用不在同一把锁里（{@code core} 没提供
     * 合并读取），中间可能有 {@code submit} 插进来。先票据后配额，最坏是配额多算了刚提交的
     * 那一张 —— 恢复时「存盘值 >= 票据所需」，不会报错；反过来会写出「配额少于在途票据」，
     * 下次登录被判定为存档损坏，玩家会看到一条假的 quotaRepaired 告警。
     */
    private PlayerState snapshotOf(String key, Session session) {
        List<HatchTicket> inFlight = new ArrayList<>();
        for (HatchTicket ticket : hatch().ticketsOf(key)) {
            if (ticket.occupiesSlot()) {
                inFlight.add(ticket);
            }
        }
        QuotaManager.State quota = hatch().stateOf(key);
        return new PlayerState(
                new HatchSnapshot(key, quota, inFlight),
                OrbcraftRuntime.allowanceOf(key).snapshot(),
                session.lastActiveTick);
    }

    private void writeState(String key, PlayerState state) {
        Path dir = this.playersDir;
        if (dir == null) {
            return;
        }
        try {
            List<StoreFile.Frame> frames = new ArrayList<>();
            CompoundTag meta = new CompoundTag();
            meta.putString("owner", key);
            meta.putLong("lastActiveTick", state.lastActiveTick());
            frames.add(new StoreFile.Frame(StoreFile.Kind.META, meta));

            for (HatchTicket ticket : state.hatch().tickets()) {
                frames.add(new StoreFile.Frame(StoreFile.Kind.TICKET, CODEC.write(ticket)));
            }

            QuotaManager.State quota = state.hatch().quota();
            CompoundTag quotaTag = new CompoundTag();
            quotaTag.putInt("equipment", quota.equipment());
            quotaTag.putInt("primary", quota.primary());
            quotaTag.putInt("chaos", quota.chaos());
            quotaTag.putInt("points", quota.usedPoints());
            frames.add(new StoreFile.Frame(StoreFile.Kind.QUOTA, quotaTag));

            for (TargetAllowance.Snapshot snapshot : state.targets()) {
                CompoundTag tag = new CompoundTag();
                tag.putLong("day", snapshot.epochDay());
                CompoundTag counts = new CompoundTag();
                snapshot.used().forEach((kind, n) -> counts.putInt(kind.name(), n));
                tag.put("used", counts);
                frames.add(new StoreFile.Frame(StoreFile.Kind.TARGET, tag));
            }

            StoreFile.write(dir.resolve(fileName(key)), frames);
        } catch (IOException | RuntimeException e) {
            // 保存失败只记日志：这条路径在 tick 与事件回调里，抛出去会打断整个 tick。
            OrbcraftMod.LOG.error("[orbcraft] 保存玩家 {} 的存档失败，本次不重试：{}", key, e.toString());
        }
    }

    private PlayerState mergeWithLive(String key, PlayerState given) {
        Session session = sessions.get(key);
        if (session == null) {
            return given;
        }
        PlayerState live = snapshotOf(key, session);

        QuotaManager.State a = live.hatch().quota();
        QuotaManager.State b = given.hatch().quota();
        // 取大是因为本轮配额只增不减（QuotaManager 没有 release，解契也还没接）。
        // 将来真做了解契释放，这里必须改成「以运行中的值为准」，否则解契后重启配额会弹回去。
        QuotaManager.State quota = new QuotaManager.State(
                Math.max(a.equipment(), b.equipment()),
                Math.max(a.primary(), b.primary()),
                Math.max(a.chaos(), b.chaos()),
                Math.max(a.usedPoints(), b.usedPoints()));

        Map<String, HatchTicket> tickets = new LinkedHashMap<>();
        for (HatchTicket ticket : given.hatch().tickets()) {
            if (key.equals(ticket.ownerKey())) {
                tickets.put(ticket.ticketId(), ticket);
            } else {
                OrbcraftMod.LOG.warn("[orbcraft] 丢弃一张归属 {} 的票据 {}：它不该出现在 {} 的存档里",
                        ticket.ownerKey(), ticket.ticketId(), key);
            }
        }
        for (HatchTicket ticket : live.hatch().tickets()) {
            tickets.put(ticket.ticketId(), ticket);
        }

        Map<Long, EnumMap<BaseKind, Integer>> days = new LinkedHashMap<>();
        for (TargetAllowance.Snapshot snapshot : live.targets()) {
            mergeTarget(days, snapshot);
        }
        for (TargetAllowance.Snapshot snapshot : given.targets()) {
            mergeTarget(days, snapshot);
        }
        List<TargetAllowance.Snapshot> mergedTargets = new ArrayList<>(days.size());
        List<Long> orderedDays = new ArrayList<>(days.keySet());
        Collections.sort(orderedDays);
        for (Long day : orderedDays) {
            mergedTargets.add(new TargetAllowance.Snapshot(key, day, days.get(day)));
        }

        return new PlayerState(
                new HatchSnapshot(key, quota, new ArrayList<>(tickets.values())),
                mergedTargets,
                Math.max(live.lastActiveTick(), given.lastActiveTick()));
    }

    private static void mergeTarget(Map<Long, EnumMap<BaseKind, Integer>> days,
                                    TargetAllowance.Snapshot snapshot) {
        EnumMap<BaseKind, Integer> counts =
                days.computeIfAbsent(snapshot.epochDay(), day -> new EnumMap<>(BaseKind.class));
        snapshot.used().forEach((kind, n) -> counts.merge(kind, n, Math::max));
    }

    // ================================================================== 给其他四路用

    /**
     * 全项目唯一的孵化服务实例 —— <b>转发给主线</b>，本类不持有。
     *
     * <p>保留这个名字只是为了让四路已有的调用点（S1/S2/S5）不用改：
     * 实例的归属由 {@link OrbcraftRuntime} 决定，这里再 new 一个就是第二套账本。
     */
    public static IncubationService hatch() {
        return OrbcraftRuntime.hatch();
    }

    /**
     * 当前世界 tick —— 转发给 {@link OrbcraftRuntime#nowTick}，不要再写第二份实现。
     *
     * <p>之所以还留这个无参重载：调用方大多只有 uuid 没有 server。
     *
     * @return 服务端还没起来时返回 0
     */
    public static long nowTick() {
        MinecraftServer srv = INSTANCE.server;
        return srv == null ? 0L : OrbcraftRuntime.nowTick(srv);
    }

    /** 玩家当前的定向卡账本（只含该玩家的格子）。没装过就是一份额度全满的新账本。 */
    public static TargetAllowance targetsOf(String ownerKey) {
        return OrbcraftRuntime.allowanceOf(ownerKey);
    }

    /**
     * 用掉一次定向卡额度，把新账本装进 runtime，并立即落盘。
     *
     * <p><b>S2 请走这个方法，不要自己调 {@code TargetAllowance.use}。</b>
     * {@code use} 返回新实例、不改原实例是设计；谁忘了把新实例 {@code installAllowance}
     * 存回去，这次操作就等于没发生。这里一次性把「扣减 + 装回 + 落盘」做完。
     *
     * @return 额度不足时 {@link Optional#empty()}（不抛异常，这是玩法分支）
     */
    public static Optional<TargetAllowance> useTarget(String ownerKey, long epochDay, BaseKind kind) {
        synchronized (INSTANCE) {
            Optional<TargetAllowance> next = OrbcraftRuntime.allowanceOf(ownerKey).use(ownerKey, epochDay, kind);
            next.ifPresent(value -> {
                OrbcraftRuntime.installAllowance(ownerKey, value);
                // 额度是既成事实：等 5 分钟定时保存，玩家在这期间崩服就等于白拿一次。
                INSTANCE.saveSession(ownerKey);
            });
            return next;
        }
    }

    /** 日历天序号，给 {@link #useTarget} 用。用真实日期而不是游戏内天数：跨天归零更符合「每日额度」。 */
    public static long todayEpochDay() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    /** 关键状态变更后（submit / deploy / 用掉定向卡）立刻落盘。服务端未就绪时静默跳过。 */
    public static void saveNow(UUID playerId) {
        String key = PlayerStore.ownerKeyOf(playerId);
        synchronized (INSTANCE) {
            INSTANCE.saveSession(key);
        }
    }

    private void saveSession(String key) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        writeSession(key, OrbcraftRuntime.nowTick(srv));
    }

    /** 文件名来自 ownerKey，仍然清洗一遍：ownerKey 将来若改成含冒号/名字的形式，也不能穿越目录。 */
    private static String fileName(String key) {
        return key.replaceAll("[^A-Za-z0-9_-]", "_") + ".dat";
    }
}
