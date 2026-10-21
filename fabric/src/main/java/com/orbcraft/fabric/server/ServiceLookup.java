package com.orbcraft.fabric.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.contract.Services;

/**
 * 契约实现的「容错取用点」—— 把 {@link Services} 的 {@code IllegalStateException} 变成 {@code null}。
 *
 * <p>为什么需要包一层：{@code Services.xxx()} 在实现未注册时抛异常，那是给
 * <b>启动自检</b>用的正确行为（它明确告诉你哪一路还没接上）。但本包的调用点全在
 * 事件回调里 —— 投掷回调里抛出去，玩家看到的是「物品没反应 + 服务端日志刷栈」，
 * 而不是「S1 没写完」。并行开发期这个区别很重要：五路各自跑的时候，
 * 三份实现永远不可能同时就位。
 *
 * <p>因此约定：<b>业务调用点只拿 {@code null}，不接异常</b>；缺哪一路就静默降级
 * （不改变原版行为），并且同一条原因只告警一次 —— 每次右键都刷一行日志，
 * 会把真正的错误埋掉。
 *
 * <p><b>不要用它做「功能是否可用」的业务分支</b>（比如「S1 没装所以我走另一条投掷路径」）。
 * 只允许「有就调用、没有就放行」。
 */
final class ServiceLookup {

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ServiceLookup() {
    }

    /** @return 未注册时为 {@code null}，调用方必须判空 */
    static OrbItemContract orbItem() {
        return orNull(Services::orbItem, "OrbItemContract（S1 orb-item）");
    }

    /** @return 未注册时为 {@code null}，调用方必须判空 */
    static PlayerStore playerStore() {
        return orNull(Services::playerStore, "PlayerStore（S4 persistence）");
    }

    private static <T> T orNull(Supplier<T> getter, String name) {
        try {
            return getter.get();
        } catch (IllegalStateException e) {
            if (WARNED.add(name)) {
                OrbcraftMod.LOG.warn("[orbcraft] S5 降级：{} 尚未注册，相关规则本次跳过（只提示这一次）", name);
            }
            return null;
        }
    }
}
