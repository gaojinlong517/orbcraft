package com.orbcraft.core.render;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 兜底外观后端：不挂任何自定义模型，只保留原版实体本身 + 名称 + 粒子。
 *
 * <p>**这个类不是「空实现」，它是产品的一部分。** 三种场景会真的走到它：
 * <ol>
 *   <li>玩家没装 BetterModel（只装我们的 mod 也能玩，只是外观是原版的）</li>
 *   <li>BetterModel 初始化失败 / 版本不兼容</li>
 *   <li>服务端资源包尚未加载完成（首帧冷启动）</li>
 * </ol>
 *
 * <p>它的存在保证了「一个第三方依赖缺失，不会让整个 mod 起不来」。
 * 代价是外观退化，能力等级如实上报为 {@link ModelCapability#NAMED_VANILLA}，
 * 由上层决定是否提示玩家。
 */
public final class NullModelBackend implements ModelBackend {

    private final Set<UUID> bound = ConcurrentHashMap.newKeySet();
    private final String reason;

    public NullModelBackend() {
        this("未安装 BetterModel");
    }

    public NullModelBackend(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    @Override
    public String backendId() {
        return "null";
    }

    @Override
    public ModelCapability capability() {
        return ModelCapability.NAMED_VANILLA;
    }

    @Override
    public boolean available() {
        return true; // 兜底永远可用，这是它唯一的作用
    }

    @Override
    public ModelSpawnResult spawn(ModelRequest request) {
        bound.add(request.hostEntityId());
        return ModelSpawnResult.degraded(
                ModelSpawnResult.Reason.BACKEND_UNAVAILABLE,
                "外观后端为兜底实现：" + reason + "；实体已正常召唤，仅外观为原版");
    }

    @Override
    public boolean play(UUID hostEntityId, String animation) {
        return false; // 兜底后端没有动画，如实返回
    }

    @Override
    public void despawn(UUID hostEntityId) {
        bound.remove(hostEntityId);
    }

    @Override
    public int boundCount() {
        return bound.size();
    }
}
