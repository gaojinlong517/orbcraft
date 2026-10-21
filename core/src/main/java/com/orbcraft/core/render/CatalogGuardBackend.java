package com.orbcraft.core.render;

import java.util.UUID;

/**
 * 校验层装饰器 —— 所有外观调用都必须穿过它，实现本身可以写得很薄。
 *
 * <p>它做三件实现层容易漏掉、漏掉就会出事的事：
 * <ol>
 *   <li><b>拦截未打包的模型。</b> 不在 {@link ModelCatalog} 里的 modelId 一律拒绝，
 *       防止某处代码顺手传了个 AI 刚生成的名字，把客户端资源包顶掉。</li>
 *   <li><b>吞掉所有异常。</b> 第三方库抛什么都不能冒到玩法层 ——
 *       否则一只宠物的外观错误会导致整次召唤失败，玩家看到的是「球炸了」。</li>
 *   <li><b>统一能力上报。</b> 让上层只依赖 {@link ModelCapability}，不依赖具体实现。</li>
 * </ol>
 */
public final class CatalogGuardBackend implements ModelBackend {

    private final ModelBackend delegate;
    private final ModelCatalog catalog;

    public CatalogGuardBackend(ModelBackend delegate, ModelCatalog catalog) {
        this.delegate = delegate;
        this.catalog = catalog == null ? ModelCatalog.empty() : catalog;
    }

    public ModelBackend delegate() {
        return delegate;
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    @Override
    public String backendId() {
        return delegate.backendId();
    }

    @Override
    public ModelCapability capability() {
        try {
            return delegate.capability();
        } catch (RuntimeException e) {
            return ModelCapability.NONE;
        }
    }

    @Override
    public boolean available() {
        try {
            return delegate.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ModelSpawnResult spawn(ModelRequest request) {
        if (request == null) {
            return ModelSpawnResult.failed(ModelSpawnResult.Reason.INTERNAL, "request 为 null");
        }
        if (!catalog.isPacked(request.modelId())) {
            // 故意拒绝，且不降级去尝试生成 —— 运行时生成资源包会让全服客户端卡顿
            return ModelSpawnResult.degraded(
                    ModelSpawnResult.Reason.MODEL_NOT_PACKED,
                    "模型 " + request.modelId() + " 不在预打包清单（共 " + catalog.size() + " 个），已跳过外观");
        }
        try {
            ModelSpawnResult r = delegate.spawn(request);
            return r == null
                    ? ModelSpawnResult.failed(ModelSpawnResult.Reason.INTERNAL, "实现返回了 null")
                    : r;
        } catch (RuntimeException e) {
            return ModelSpawnResult.failed(
                    ModelSpawnResult.Reason.INTERNAL,
                    "spawn 抛出异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    @Override
    public boolean play(UUID hostEntityId, String animation) {
        if (hostEntityId == null || animation == null) {
            return false;
        }
        try {
            return delegate.play(hostEntityId, animation);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void despawn(UUID hostEntityId) {
        if (hostEntityId == null) {
            return;
        }
        try {
            delegate.despawn(hostEntityId);
        } catch (RuntimeException ignored) {
            // 卸载失败不应打断宿主实体的销毁流程，交由 boundCount 诊断泄漏
        }
    }

    @Override
    public int boundCount() {
        try {
            return delegate.boundCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
