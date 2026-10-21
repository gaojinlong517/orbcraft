package com.orbcraft.fabric.ui;

import java.util.UUID;

import com.orbcraft.core.build.BuildSession;
import com.orbcraft.core.hatch.HatchSubmit;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.spec.BehaviorMode;

/**
 * 一个玩家的构筑席位 —— 一次构筑里「还没落盘」的全部状态都挂在这里。
 *
 * <p><b>为什么用 ownerKey（UUID）而不是 {@code ServerPlayer} 做键</b>：
 * 玩家换维度、退出重进都会换一个 {@code ServerPlayer} 实例。用实例做键，
 * 玩家过一道传送门回来构筑就没了，而那时候他可能已经花掉了当天的定向卡额度。
 * ownerKey 来自 {@code PlayerStore.ownerKeyOf}，与配额、票据是同一个键。
 *
 * <p><b>为什么定向额度不放在这里</b>：额度是「已经发生的事实」，
 * 席位却会因为玩家半小时不操作被回收（见 {@link BuildRuntime}）。
 * 两者生命周期不同，混在一起就会出现「挂机半小时，当天额度回来了」。
 * 账本就是运行期单例里的那一份（S4 负责落盘），这里只放这一次构筑的过程状态。
 *
 * <p><b>为什么定稿参数（行为 / 材料 / 祭坛）留在这里而不是塞进 Composition</b>：
 * 同一个种子必须永远产出同一个组合，{@code Composition} 一旦带上「玩家选了什么材料」，
 * 抽卡结果就不再可复现（见 {@code HatchSubmit} 的类注释）。
 */
final class BuildSeat {

    private final String ownerKey;
    private final UUID playerId;

    private BuildSession session;

    /** 召唤物行为；{@code null} 表示还没选，按 {@code BuildFlow} 的默认值兜底。 */
    private BehaviorMode behavior;
    private MaterialTier material = MaterialTier.TRIAL;
    private boolean onAltar;

    /**
     * 已算好、等玩家点确认的那份提交请求。
     *
     * <p>存它而不是只存一份 {@code Preview}：{@code submit} 要用同一个
     * {@link HatchSubmit} 重新算一遍，拿预览里的 {@code Spec} 去重建请求，
     * 就等于把「什么时候算的」和「什么时候交的」拆成两件事。
     */
    private HatchSubmit pending;
    private IncubationService.Preview preview;

    private long lastTouchTick;

    BuildSeat(String ownerKey, UUID playerId) {
        this.ownerKey = ownerKey;
        this.playerId = playerId;
    }

    String ownerKey() {
        return ownerKey;
    }

    UUID playerId() {
        return playerId;
    }

    long lastTouchTick() {
        return lastTouchTick;
    }

    void touch(long nowTick) {
        this.lastTouchTick = nowTick;
    }

    BuildSession session() {
        return session;
    }

    void session(BuildSession next) {
        this.session = next;
        clearPending();
    }

    void endSession() {
        this.session = null;
        this.behavior = null;
        this.material = MaterialTier.TRIAL;
        this.onAltar = false;
        clearPending();
    }

    BehaviorMode behavior() {
        return behavior;
    }

    void behavior(BehaviorMode next) {
        this.behavior = next;
        // 改了行为，之前那份预览描述的就是另一件东西了，必须作废
        clearPending();
    }

    MaterialTier material() {
        return material;
    }

    void material(MaterialTier next) {
        this.material = next;
        clearPending();
    }

    boolean onAltar() {
        return onAltar;
    }

    void toggleAltar() {
        this.onAltar = !this.onAltar;
        clearPending();
    }

    HatchSubmit pending() {
        return pending;
    }

    IncubationService.Preview preview() {
        return preview;
    }

    void awaitConfirm(HatchSubmit submit, IncubationService.Preview preview) {
        this.pending = submit;
        this.preview = preview;
    }

    /**
     * 作废待确认的预览。
     *
     * <p>每种「会改变产物」的操作（选牌、行为、材料、祭坛）都必须调它：
     * 预览里那条「能不能造、扣几点」是按旧参数算的，玩家改了参数却沿用旧预览，
     * 就会出现「预览说扣 1 点，实际扣了 2 点」。
     */
    void clearPending() {
        this.pending = null;
        this.preview = null;
    }
}
