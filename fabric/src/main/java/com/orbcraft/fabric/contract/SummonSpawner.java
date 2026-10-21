package com.orbcraft.fabric.contract;

import java.util.UUID;

import com.orbcraft.core.render.ModelCapability;
import com.orbcraft.core.spec.Spec;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 召唤契约 —— 「一个 {@link Spec}，怎么变成世界里一个会打人的东西」。
 *
 * <p><b>实现方：S3 {@code spawner}。调用方：S1 命中释放、S2 预览提示。</b>
 *
 * <p>边界划在这里，是为了让 S1 不必知道属性怎么映射、S3 不必知道球怎么飞。
 * S1 只要在命中点调 {@link #spawn}，拿到实体 id 就够了。
 *
 * <p><b>外观不归本接口管。</b> {@code core} 里已经有 {@code ModelBackend}
 * （{@code spawn / play / despawn / capability}）与 {@code ModelBackends.select} 的择优逻辑。
 * 本接口只负责<b>玩法侧</b>：实体类型、属性、AI 模式、归属。
 * 在这里再写一遍「模型能不能加载」的判断，就会有两套能力等级，玩家会收到互相矛盾的提示。
 * {@link #capability()} 只是把当前后端的等级转述出来，方便 S1 决定要不要提示「外观已降级」。
 */
public interface SummonSpawner {

    /**
     * 在指定位置生成。
     *
     * <p>实现要点（不写进签名，但必须是行为的一部分）：
     * <ul>
     *   <li>幂等键是 {@code spec.id()} —— 同一次落地被两条路径触发（实体命中 + 区块补偿）
     *       时不能召出两只；这一条和 {@code IncubationService.deploy} 的幂等性是对齐的。</li>
     *   <li>失败（无法生成、位置非法、世界未加载）返回 {@code spawned=false} 与中文原因，
     *       <b>不抛异常</b>：投掷是在实体命中回调里调的，抛出去会把 tick 打断。</li>
     *   <li>装备与消耗品（{@code BehaviorMode.NONE}）不该走到这里；传进来直接拒绝。</li>
     * </ul>
     */
    SpawnResult spawn(ServerLevel level, Vec3 pos, Spec spec, String ownerKey);

    /**
     * 移除并解除绑定。已经不存在时返回 {@code false} 而不是抛异常 ——
     * 「实体被别的机制清掉了」是正常运行的一部分。
     */
    boolean despawn(ServerLevel level, UUID entityId);

    /**
     * 归属查询，用于「A 的召唤物 B 不能指挥」以及 PVP 的友伤判定。
     *
     * <p>未知实体返回 {@code false}：查不到归属时按「不是你的」处理。
     * 反过来（按你的）会让玩家凭空指挥别人的召唤物，是更严重的错误。
     */
    boolean ownedBy(UUID entityId, String ownerKey);

    /** 当前外观后端的能力等级，转述自 {@code ModelBackend.capability()}。 */
    ModelCapability capability();

    /**
     * 生成结果。
     *
     * @param spawned  是否真的生成了
     * @param entityId 生成成功时为实体 id，失败为 {@code null}
     * @param reason   给玩家看的中文说明；成功时为 {@code "ok"}
     * @param appearance 本次生成实际用到的外观等级，供调用方决定是否提示降级
     */
    record SpawnResult(boolean spawned, UUID entityId, String reason, ModelCapability appearance) {

        public SpawnResult {
            reason = reason == null || reason.isBlank() ? "未知原因" : reason;
            appearance = appearance == null ? ModelCapability.NONE : appearance;
        }

        public static SpawnResult ok(UUID entityId, ModelCapability appearance) {
            return new SpawnResult(true, entityId, "ok", appearance);
        }

        public static SpawnResult failed(String reason, ModelCapability appearance) {
            return new SpawnResult(false, null, reason, appearance);
        }
    }
}
