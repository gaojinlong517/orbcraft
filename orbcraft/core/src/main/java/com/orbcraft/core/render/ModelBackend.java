package com.orbcraft.core.render;

import java.util.UUID;

/**
 * 外观后端 —— 把自定义模型挂到宿主实体上的抽象。
 *
 * <p>这是全项目**唯一**为「可替换的外部依赖」预留的接口。首版实现是 BetterModel
 * （MIT、服务端驱动、客户端零安装），二期若要脱离它，只需要再写一个实现，
 * 上层 {@code common} / {@code fabric} 一行不用改。
 *
 * <p>约定：
 * <ul>
 *   <li>所有方法都**不抛异常**。外观失败永远不该阻断玩法。</li>
 *   <li>宿主实体由调用方创建并管理生命周期，本接口只负责「外观」。</li>
 *   <li>实现必须线程安全（调用可能来自网络包线程与主线程的交接处）。</li>
 * </ul>
 */
public interface ModelBackend {

    /** 后端标识，用于日志与诊断，例如 {@code "bettermodel"} / {@code "null"}。 */
    String backendId();

    /** 能力等级，决定是否要向玩家提示外观降级。 */
    ModelCapability capability();

    /** 后端是否可用。为 false 时调用方应直接走兜底而不是反复尝试。 */
    boolean available();

    /**
     * 把模型挂到宿主实体上。
     *
     * @return 永远不返回 null
     */
    ModelSpawnResult spawn(ModelRequest request);

    /**
     * 播放动画。动画不存在时返回 false，**不抛异常**。
     * 调用方应把 false 当作「这次没有动效」而不是错误。
     */
    boolean play(UUID hostEntityId, String animation);

    /** 卸载外观。宿主实体销毁时必须调用，否则会残留展示实体。 */
    void despawn(UUID hostEntityId);

    /** 当前挂载中的外观数量，用于诊断与泄漏检测。 */
    int boundCount();
}
