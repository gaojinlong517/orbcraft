package com.orbcraft.core.render;

/**
 * 外观挂载的结果。
 *
 * <p>**设计铁律：不抛异常，必须返回结果。**
 * 外观是玩法的附加层，一个模型文件缺失不应该让玩家花 3 分钟孵化出来的恶龙召不出来。
 * 调用方拿到 {@link Status#FAILED} 时应当继续走召唤流程，只是不挂外观。
 *
 * @param status  结果状态
 * @param reason  失败/降级原因
 * @param detail  可读说明，仅用于日志，不直接展示给玩家
 */
public record ModelSpawnResult(Status status, Reason reason, String detail) {

    public enum Status {
        /** 完整外观已挂上。 */
        OK,
        /** 玩法可用，但外观退化（兜底后端 / 模型不存在 / 资源包未就绪）。 */
        DEGRADED,
        /** 连降级都失败，调用方不要期待有任何外观。 */
        FAILED
    }

    public enum Reason {
        NONE,
        /** 后端不可用（BetterModel 未安装 / 初始化失败）。 */
        BACKEND_UNAVAILABLE,
        /** modelId 不在预打包清单内 —— 这是**故意拒绝**，绝不在运行时动态生成资源。 */
        MODEL_NOT_PACKED,
        /** 模型已登记但后端报告找不到（资源包尚未加载完）。 */
        RESOURCE_PACK_PENDING,
        /** 宿主实体已失效 / 已在别的维度卸载。 */
        HOST_INVALID,
        INTERNAL
    }

    public static ModelSpawnResult ok() {
        return new ModelSpawnResult(Status.OK, Reason.NONE, "");
    }

    public static ModelSpawnResult degraded(Reason reason, String detail) {
        return new ModelSpawnResult(Status.DEGRADED, reason, detail);
    }

    public static ModelSpawnResult failed(Reason reason, String detail) {
        return new ModelSpawnResult(Status.FAILED, reason, detail);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** 外观是否已经能正常显示（含失败时的 false）。 */
    public boolean looksCorrect() {
        return status == Status.OK;
    }
}
