package com.orbcraft.core.hatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.core.settle.CompositionSettler;
import com.orbcraft.core.settle.SettleRequest;
import com.orbcraft.core.spec.Spec;

/**
 * 孵化链路服务 —— 把「构筑结果 → 配额 → 孵化工时 → 投掷 → 落地」串成一条线。
 *
 * <p><b>这个类存在的唯一理由是一句话：定稿才扣额度。</b>
 * 之前的零件都是各自正确的纯函数，但没有任何东西规定它们的调用顺序，
 * 于是最容易被写成"预览时就扣了统御力"或者"tick 时顺手再校验一次配额"。
 * 前者让玩家点开确认界面就丢配额，后者让过期的判定覆盖掉真实占用。
 * 所以这里把两条路径<b>物理隔离</b>成两个方法：
 * <ul>
 *   <li>{@link #preview} —— 只读。算 Spec、算配额够不够，<b>一个字段都不改</b>。</li>
 *   <li>{@link #submit} —— 唯一的写入点。重新算一遍（因为 UI 停留期间配额可能被别的产物占了），
 *       通过了才扣，扣完立刻建票；失败则抛 {@link HatchException}，
 *       扣减与建票在同一把锁内完成，不存在"扣了没票"的中间态。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：所有公有方法都是 {@code synchronized}。不使用
 * {@code ConcurrentHashMap} 而用一把锁，是因为配额与票据是<b>两次关联写入</b>
 * （先占统御力、再落票据）。分成两个并发容器时，{@link #tick} 可能观察到
 * "配额已扣但没有球"或反之 —— 那种状态无法回滚，只能靠锁从结构上排除。
 * 单玩家调用频率是"人手点一次"，锁竞争不构成性能问题。
 *
 * <p><b>离线封顶为什么在 submit 而不是 tick</b>：进度是 {@code nowTick - startTick}，
 * 唯一能削减它的位置是<b>起点的写入时刻</b>。放到 tick 里做，就得每次扫描都判断
 * "上次登录是什么时候"，而 tick 只拿到 nowTick，根本没有那个信息；
 * 更糟的是一旦票据已经存盘，补丁式的修正会让同一张票的进度在两次扫描之间抖动。
 * 在 submit 里把 {@code startTick} 往后推，是最早、也是唯一一次能干净生效的时机。
 *
 * <p>本类无 IO、无静态状态，一个实例代表一份玩家世界的孵化状态；
 * 落盘由平台层负责（票据 6 个字段全在 {@link HatchTicket} 里）。
 */
public final class IncubationService {

    /** 20 tick/秒 × 3600 秒 × 8 小时。玩家离线多久，最多只算 8 小时进度。 */
    public static final long DEFAULT_OFFLINE_CAP_TICKS = 20L * 3600 * 8;

    /** 同时存在的球上限。与统御力池（5 点）同量级，不让玩家囤一背包进度条。 */
    public static final int DEFAULT_MAX_CONCURRENT = 5;

    /** Spec 要求 hatchSeconds >= 1，祭坛减半也不能击穿。 */
    private static final int MIN_HATCH_SECONDS = 1;

    /**
     * 预览结果 —— UI 在「确认定稿」之前展示的一切。
     *
     * @param spec     如果现在提交，会造出的产物（祭坛加速后的时长已写进 spec）
     * @param allowed  现在提交能否成功（配额 + 孵化栏一并判定）
     * @param reason   allowed=false 时的玩家可读原因；true 时为 {@code "ok"}
     * @param cost     这次会消耗的统御力点数（消耗品为 0）
     * @param category 配额类别，供 UI 画"装备 2/2"这样的计数
     */
    public record Preview(Spec spec, boolean allowed, String reason, int cost, QuotaManager.Category category) {

        public Preview {
            if (spec == null) {
                throw new IllegalArgumentException("Preview.spec 不能为空");
            }
            reason = reason == null ? "" : reason;
            category = category == null ? QuotaManager.Category.FREE : category;
        }
    }

    /** 内部用：一次判定的完整结果，带上"扣完之后"的状态，避免重复计算。 */
    private record Evaluation(Preview preview, QuotaManager.Result quota) {
    }

    private final QuotaManager quota = new QuotaManager();
    private final Map<String, QuotaManager.State> quotas = new HashMap<>();
    private final Map<String, HatchTicket> tickets = new LinkedHashMap<>();
    private final long offlineCapTicks;
    private final int maxConcurrent;
    private long ticketSeq;

    /** 默认参数：8 小时离线封顶、同时 5 个。 */
    public IncubationService() {
        this(DEFAULT_OFFLINE_CAP_TICKS, DEFAULT_MAX_CONCURRENT);
    }

    /**
     * @param offlineCapTicks 离线封顶的 tick 数，0 表示完全不给离线进度
     * @param maxConcurrent   同时孵化上限，必须 >= 1
     */
    public IncubationService(long offlineCapTicks, int maxConcurrent) {
        if (offlineCapTicks < 0) {
            throw new IllegalArgumentException("offlineCapTicks 不能为负：" + offlineCapTicks);
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent 必须 >= 1：" + maxConcurrent);
        }
        this.offlineCapTicks = offlineCapTicks;
        this.maxConcurrent = maxConcurrent;
    }

    // ------------------------------------------------------------------ 只读

    /**
     * 预览 —— <b>完全不改变任何状态</b>。
     *
     * <p>这是"让玩家在确认前就知道造不造得出来"的关键：UI 先 preview 出
     * 稀有度、时长、会扣几点统御力、以及能不能造；玩家点确认才 submit。
     * 两次调用同一入参必须得到相同结果，且 {@link #stateOf} 不变。
     */
    public synchronized Preview preview(HatchSubmit submit) {
        if (submit == null) {
            throw new IllegalArgumentException("submit 不能为空");
        }
        return evaluate(submit).preview();
    }

    /** 当前配额占用快照；未知玩家返回空状态，且不会凭空塞一条记录。 */
    public synchronized QuotaManager.State stateOf(String ownerKey) {
        requireOwner(ownerKey);
        return quotas.getOrDefault(ownerKey, QuotaManager.State.empty());
    }

    public synchronized Optional<HatchTicket> ticket(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tickets.get(ticketId));
    }

    /** 该玩家的全部票据，按创建顺序（含已结束的，供历史/审计）。 */
    public synchronized List<HatchTicket> ticketsOf(String ownerKey) {
        requireOwner(ownerKey);
        List<HatchTicket> out = new ArrayList<>();
        for (HatchTicket t : tickets.values()) {
            if (t.ownerKey().equals(ownerKey)) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    public long offlineCapTicks() {
        return offlineCapTicks;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 定稿提交 —— <b>整条链路上唯一扣配额的地方</b>。
     *
     * <p>配额判定在这里重算一遍，而不是复用玩家看到的 preview：UI 停在确认界面的几十秒里，
     * 同一玩家的另一件产物可能已经占掉了装备位。复用旧判定会让两件装备同时通过，
     * 于是"最多 2 件"变成"至少 3 件"。
     *
     * <p>失败路径保证无副作用：先判定、再写入，中间没有任何"预留"。
     *
     * @param nowTick        当前游戏 tick
     * @param lastActiveTick 玩家上次在线（或本次登录）的 tick，用于离线封顶
     * @throws HatchException {@code QUOTA_REJECTED} / {@code BAR_FULL}
     */
    public synchronized HatchTicket submit(long nowTick, long lastActiveTick, HatchSubmit submit) {
        if (submit == null) {
            throw new IllegalArgumentException("submit 不能为空");
        }
        if (nowTick < 0) {
            throw new IllegalArgumentException("nowTick 不能为负：" + nowTick);
        }

        Evaluation eval = evaluate(submit);
        if (!eval.quota().allowed()) {
            throw new HatchException(HatchException.Code.QUOTA_REJECTED, eval.preview().reason());
        }
        if (!eval.preview().allowed()) {
            // 配额过了但孵化栏满了。两者要分开报：一个叫玩家去解契，一个叫玩家先投出去。
            throw new HatchException(HatchException.Code.BAR_FULL, eval.preview().reason());
        }

        Spec spec = eval.preview().spec();
        quotas.put(submit.ownerKey(), eval.quota().after());

        HatchTicket ticket = new HatchTicket(
                nextTicketId(),
                spec.id(),
                submit.ownerKey(),
                startTickFor(nowTick, lastActiveTick),
                spec.hatchSeconds(),
                HatchState.CHARGING,
                spec);
        tickets.put(ticket.ticketId(), ticket);
        return ticket;
    }

    /**
     * 推进所有充能中的票据，返回<b>本次新就绪</b>的那些（供播报"你的球亮了"）。
     *
     * <p>不碰配额：就绪只是状态迁移，解除占用要等落地。
     */
    public synchronized List<HatchTicket> tick(long nowTick) {
        List<HatchTicket> ready = new ArrayList<>();
        for (Map.Entry<String, HatchTicket> e : tickets.entrySet()) {
            HatchTicket t = e.getValue();
            if (t.state() == HatchState.CHARGING && t.isReady(nowTick)) {
                HatchTicket next = t.withState(HatchState.READY);
                e.setValue(next);
                ready.add(next);
            }
        }
        return List.copyOf(ready);
    }

    /**
     * 投掷：READY → THROWN。
     *
     * <p>若票据仍是 CHARGING 但按 {@code nowTick} 其实已经充满（玩家在两次 tick 扫描之间右键），
     * 这里会就地补一次迁移再放行 —— 否则玩家会遇到"条明明满了却投不出去"，
     * 而这种失败没有任何可解释的反馈。
     *
     * @throws HatchException {@code NOT_READY}（确实没充满）/ {@code UNKNOWN_TICKET}
     */
    public synchronized HatchTicket throwBall(String ticketId, long nowTick) {
        HatchTicket t = requireTicket(ticketId);

        HatchTicket current = t;
        if (current.state() == HatchState.CHARGING && current.isReady(nowTick)) {
            current = current.withState(HatchState.READY);
        }
        if (current.state() != HatchState.READY) {
            throw new HatchException(HatchException.Code.NOT_READY,
                    "还没充满（" + current.state() + "），当前不能投掷：" + ticketId);
        }

        HatchTicket thrown = current.withState(HatchState.THROWN);
        tickets.put(ticketId, thrown);
        return thrown;
    }

    /**
     * 落地：THROWN → DEPLOYED。<b>幂等</b> —— 已落地再调返回 false 而不是抛异常。
     *
     * <p>为什么幂等而不是抛：落地会被"实体命中"和"区块加载补偿"两条路径同时触发，
     * 重复调用是正常现象，不是错误。反过来"未投出就落地"是真正的状态错乱，必须抛。
     *
     * @return true 表示本次调用完成了状态迁移；false 表示早已落地
     * @throws HatchException {@code NOT_READY} / {@code ALREADY_FINISHED} / {@code UNKNOWN_TICKET}
     */
    public synchronized boolean deploy(String ticketId, long nowTick) {
        HatchTicket t = requireTicket(ticketId);
        return switch (t.state()) {
            case DEPLOYED -> false;
            case THROWN -> {
                tickets.put(ticketId, t.withState(HatchState.DEPLOYED));
                yield true;
            }
            case FAILED -> throw new HatchException(HatchException.Code.ALREADY_FINISHED,
                    "票据已失效，不能落地：" + ticketId);
            case CHARGING, READY -> throw new HatchException(HatchException.Code.NOT_READY,
                    "票据还没投出（" + t.state() + "），不能落地：" + ticketId);
        };
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 一次完整判定：结算 Spec → 试扣配额 → 查孵化栏。
     *
     * <p>{@link QuotaManager#tryConsume} 本身是纯函数（返回新 State，不改入参），
     * 所以这里"试扣"对状态零影响 —— 真正的写入只在 {@link #submit} 里发生。
     */
    private Evaluation evaluate(HatchSubmit submit) {
        Spec spec = settledSpec(submit);
        QuotaManager.Result qr = quota.tryConsume(stateOf(submit.ownerKey()), spec);

        int active = activeCount(submit.ownerKey());
        boolean barOk = active < maxConcurrent;

        String reason;
        if (!qr.allowed()) {
            reason = qr.reason();
        } else if (!barOk) {
            reason = "同时孵化已达上限 " + maxConcurrent + " 个，先投出或等一个完成";
        } else {
            reason = "ok";
        }

        Preview preview = new Preview(spec, qr.allowed() && barOk, reason, qr.cost(),
                QuotaManager.categoryOf(spec));
        return new Evaluation(preview, qr);
    }

    /** 结算 Spec，并在上祭坛时改写时长。 */
    private static Spec settledSpec(HatchSubmit submit) {
        SettleRequest request = new SettleRequest(
                submit.ownerKey(), submit.behavior(), submit.material(), submit.extraMaterials());
        Spec spec = CompositionSettler.settle(submit.composition(), request);
        return submit.onAltar() ? spec.withHatchSeconds(altarSeconds(spec)) : spec;
    }

    /**
     * 祭坛加速后的秒数。
     *
     * <p><b>这里必须显式 clamp</b>，不能只写 {@code / 2}。原因不是除法会超上限
     * （它不会），而是这个数值最终要喂回 {@code Spec}，而 {@code Spec} 的构造器
     * 会把 {@code > rarity.maxHatchSeconds()} 直接判成非法参数。项目里已经踩过一次：
     * 结算层把倍率叠到史诗 240 秒，构造 Spec 时整个定稿流程崩掉。
     * 所以凡是"在 Spec 之外再改时长"的地方，都要把上限重新钉一遍 ——
     * 将来如果有人把祭坛从 ×0.5 改成"再叠一层倍率"，这行 clamp 就是唯一的保险。
     *
     * <p>向下取整而不是四舍五入：加速只应该让玩家少等，不能因为进位多等一秒。
     * 下限钉在 1 秒，与 {@code HatchTime.MIN_SECONDS} 一致。
     */
    static int altarSeconds(Spec spec) {
        int halved = Math.max(MIN_HATCH_SECONDS, spec.hatchSeconds() / 2);
        return Math.min(halved, spec.rarity().maxHatchSeconds());
    }

    /**
     * 离线封顶：把进度起点推到"最多 8 小时前"。
     *
     * <p>再补两个夹取，都防的是脏数据而非玩法：
     * <ul>
     *   <li>下界 0 —— tick 不会是负数，但存档里可能是</li>
     *   <li>上界 nowTick —— {@code lastActiveTick} 若因为回滚/改时间跑到未来，
     *       起点落在未来会让进度永远为 0，球就卡死了</li>
     * </ul>
     */
    private long startTickFor(long nowTick, long lastActiveTick) {
        long base = Math.max(lastActiveTick, nowTick - offlineCapTicks);
        return Math.max(0, Math.min(base, nowTick));
    }

    private int activeCount(String ownerKey) {
        int n = 0;
        for (HatchTicket t : tickets.values()) {
            if (t.ownerKey().equals(ownerKey) && t.occupiesSlot()) {
                n++;
            }
        }
        return n;
    }

    /** 服务内唯一：单调递增，跨重启不保证唯一，所以平台层必须连同票据一起存盘。 */
    private String nextTicketId() {
        ticketSeq++;
        return "tk-" + Long.toHexString(ticketSeq);
    }

    private HatchTicket requireTicket(String ticketId) {
        HatchTicket t = ticketId == null ? null : tickets.get(ticketId);
        if (t == null) {
            throw new HatchException(HatchException.Code.UNKNOWN_TICKET, "查不到票据：" + ticketId);
        }
        return t;
    }

    private static void requireOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey 不能为空");
        }
    }
}
