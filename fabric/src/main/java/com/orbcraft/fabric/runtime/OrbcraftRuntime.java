package com.orbcraft.fabric.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.target.TargetAllowance;
import net.minecraft.server.MinecraftServer;

/**
 * 运行期单例的唯一持有者。主线所有，五路只读。
 *
 * <p><b>为什么这个类必须存在。</b> {@link IncubationService} 是全服唯一的一份内存状态：
 * 谁在孵什么、孵了多久、配额用掉几格。它的所有方法都是 {@code synchronized} 且自带状态机，
 * 但<b>状态在实例里，不在数据库里</b> —— 也就是说「两个实例」等于「两套互不相干的孵化账本」。
 *
 * <p>上一轮并行开发时两路各自 {@code new IncubationService()}，合并后的症状是：
 * <ul>
 *   <li>构筑（S2）把票提交进实例 A，球投出时（S1）在实例 B 里查不到这张票 → 球永远是 0%</li>
 *   <li>登录恢复（S4）把存档写进实例 C，tick（S5）推进的是实例 D → 读条永不前进</li>
 * </ul>
 * 这些都不报错，只是「没反应」。所以实例的归属不能由某一路自己决定，必须由主线固定在这里，
 * 其余四个包一律 {@link #hatch()} 取用，<b>禁止再出现 {@code new IncubationService()}</b>。
 * 需要自定义离线封顶或并发上限时，改这里一处，不要各调各的构造函数。
 *
 * <p>同理，{@link TargetAllowance} 是「每人每天的定向卡额度」。它是不可变对象，
 * {@code use()} 返回新实例，所以真正的状态是「当前每个 ownerKey 手里拿着哪一个实例」——
 * 这份映射也必须只有一份，否则会出现「构筑时扣了额度、存档时读到没扣的那份」。
 *
 * <p>本类<b>不做任何 IO、不注册事件、不含玩法判断</b>。它只回答两个问题：
 * 那唯一一份状态在哪，以及「现在」是第几 tick。
 */
public final class OrbcraftRuntime {

    /**
     * 定向卡默认额度：三类各 1 次/天。
     *
     * <p>与 {@code TargetAllowance.daily(int)} 的默认语义一致。改这里等于改全服每日定向次数，
     * 属于数值改动，要连带跑 {@code docs/构筑流程-四层确认制.md} 里的平衡脚本。
     */
    public static final int TARGETS_PER_KIND_PER_DAY = 1;

    /** 全服唯一的孵化服务。构造参数取 core 的默认值（离线封顶 8 小时、同时 5 颗）。 */
    private static final IncubationService HATCH = new IncubationService();

    /**
     * ownerKey → 当前生效的定向卡账本。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 synchronized 方法：读远多于写（每次点击都要读
     * remaining），而 {@code computeIfAbsent} 本身对同一 key 是原子的，够用。
     */
    private static final Map<String, TargetAllowance> ALLOWANCES = new ConcurrentHashMap<>();

    private OrbcraftRuntime() {
    }

    /** 全服唯一的孵化服务。要在它上面做任何状态迁移，都从这里取。 */
    public static IncubationService hatch() {
        return HATCH;
    }

    /**
     * 当前的 game time（世界刻），所有与「孵了多久」「每日额度」相关的时间都用它。
     *
     * <p>不能用 {@code MinecraftServer#getTickCount()}：那是进程内自增，每次重启归零，
     * 存进存档的 {@code startTick} 立刻失去意义（重启后进度条会从头开始）。
     * game time 随世界一起持久化，这才是唯一能跨重启对齐的时钟。
     */
    public static long nowTick(MinecraftServer server) {
        return server.overworld().getLevelData().getGameTime();
    }

    /**
     * 取某人的定向卡账本，没有就发一份新的。
     *
     * <p>新账本按 {@link #TARGETS_PER_KIND_PER_DAY} 建。注意「新」意味着今天的三类额度都是满的 ——
     * 登录时若有存档，必须先 {@link #installAllowance} 再取用，否则玩家会白拿一次额度。
     */
    public static TargetAllowance allowanceOf(String ownerKey) {
        return ALLOWANCES.computeIfAbsent(ownerKey,
                key -> TargetAllowance.daily(TARGETS_PER_KIND_PER_DAY));
    }

    /**
     * 把一份账本装上，覆盖原值。{@code use()} 返回新实例后必须调它，
     * 否则这次扣减等于没发生（这是 {@code TargetAllowance} 的不可变设计，不是 bug）。
     */
    public static void installAllowance(String ownerKey, TargetAllowance allowance) {
        if (ownerKey == null || allowance == null) {
            throw new IllegalArgumentException("ownerKey 与 allowance 都不能为空");
        }
        ALLOWANCES.put(ownerKey, allowance);
    }

    /**
     * 丢掉某人的内存账本。下线时调用可以少占一点内存，但<b>不是必须的</b>：
     * 存档才是事实来源，登录时会重新 {@code installAllowance}。
     */
    public static void forgetAllowance(String ownerKey) {
        ALLOWANCES.remove(ownerKey);
    }
}
