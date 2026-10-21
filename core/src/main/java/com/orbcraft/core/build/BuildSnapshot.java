package com.orbcraft.core.build;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;

import java.util.List;

/**
 * 构筑状态的一次性快照 —— UI 与网络包都只读它，不读 {@link BuildSession} 本体。
 *
 * <p>为什么需要这一层：{@code BuildSession} 是可变状态机，直接把引用交给渲染层，
 * 就会出现「UI 正读到一半，玩家又点了一下」的错乱。快照是不可变的，
 * 一次渲染只看到一致的一帧。
 *
 * <p>{@code requiredShapePick} / {@code requiredModifierPick} / {@code requiredEffectPick}
 * 三个数必须一起下发：客户端要自己判断「确认按钮能不能点」，
 * 如果只发候选不发规则，客户端只能盲猜，最后靠服务端拒绝来纠正 ——
 * 玩家看到的是「点了没反应」，而不是「还差一张」。
 *
 * <p>四层用同一套字段而不是「一层一个专用包」，是因为客户端只有一屏卡牌界面，
 * 每层切换只是换 {@code cards} 和 {@code requiredPickCount()}，没有额外状态。
 *
 * @param phase                  当前阶段
 * @param kind                   已选类型；未选时为 {@code null}
 * @param typeOptions            类型层的三个候选（其他层也下发，UI 可回显）
 * @param cards                  当前层候选卡；类型层为空
 * @param chosen                 当前层已选索引（升序）
 * @param requiredShapePick      形态层应选张数（恒为 1）
 * @param requiredModifierPick   修饰层应选张数
 * @param requiredEffectPick     效果层应选张数
 * @param shapeRerollsLeft       形态层剩余「换一批」次数；0 时 UI 不该再显示换批按钮
 * @param canConfirm             当前能否确认
 * @param confirmHint            还差什么；已满足为空串
 * @param confirmedCards         已确认的卡（跨层累计，顺序为 形态 → 修饰 → 效果）
 * @param composition            已完成时非空
 * @param seed                   随机种子（投诉回放用）
 */
public record BuildSnapshot(
        BuildPhase phase,
        BaseKind kind,
        List<TypeOption> typeOptions,
        List<DraftCard> cards,
        List<Integer> chosen,
        int requiredShapePick,
        int requiredModifierPick,
        int requiredEffectPick,
        int shapeRerollsLeft,
        /**
         * 定向的元素卡因为与已确认形态不兼容而未能兑现。
         *
         * <p>放在快照里而不是给 {@code BuildSession} 加一个 getter：UI 本来就该
         * 「一次读完整状态」，多一个入口只会让序列化点分散。
         */
        boolean targetMissed,
        boolean canConfirm,
        String confirmHint,
        List<DraftCard> confirmedCards,
        Composition composition,
        long seed
) {

    public BuildSnapshot {
        typeOptions = typeOptions == null ? List.of() : List.copyOf(typeOptions);
        cards = cards == null ? List.of() : List.copyOf(cards);
        chosen = chosen == null ? List.of() : List.copyOf(chosen);
        confirmHint = confirmHint == null ? "" : confirmHint;
        confirmedCards = confirmedCards == null ? List.of() : List.copyOf(confirmedCards);
    }

    /** 当前层要求选几张 —— UI 把这个数显示成「1 / 5」。 */
    public int requiredPickCount() {
        return switch (phase) {
            case SHAPE_CHOOSING, SHAPE_CONFIRMING -> requiredShapePick;
            case MODIFIER_CHOOSING, MODIFIER_CONFIRMING -> requiredModifierPick;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> requiredEffectPick;
            default -> 0;
        };
    }

    public boolean finished() {
        return phase == BuildPhase.FINISHED;
    }

    /** 当前能否换一批。形态层未选择且还有次数 —— 与 {@code BuildSession.rerollShape()} 的准入条件一致。 */
    public boolean canRerollShape() {
        return (phase == BuildPhase.SHAPE_CHOOSING) && shapeRerollsLeft > 0;
    }

    /**
     * 一行状态栏文案，如「修饰层 0/1 · 可确认」「形态层 0/1 · 请选择 1 个形态 · 可换批 ×1」。
     *
     * <p>剩余次数为 0 时<b>不出现</b>换批字样：留着「可换批 0 次」只会让玩家反复点，
     * 点一次报一次错。按钮该跟着这句话一起消失。已选中形态（待确认）时同样不提示 ——
     * 那句话只描述<b>当下可点</b>的动作，不描述「取消选择后还能点」。
     */
    public String statusLine() {
        if (phase.isTerminal()) {
            return phase == BuildPhase.FINISHED ? "已定稿" : "已放弃";
        }
        // 类型层是固定三选一，没有「已选 n/上限」的概念，单独给文案
        if (phase == BuildPhase.TYPE_CHOOSING) {
            return "类型层 · 请选择类型";
        }
        if (phase == BuildPhase.TYPE_CONFIRMING) {
            return "类型层 · 已选「" + (kind == null ? "—" : kind.zh()) + "」· 可确认";
        }
        String head = phase.layerZh() + " · " + chosen.size() + "/" + requiredPickCount();
        String state = canConfirm ? head + " · 可确认" : head + " · " + confirmHint;
        if (canRerollShape()) {
            return state + " · 可换批 ×" + shapeRerollsLeft;
        }
        return state;
    }
}
