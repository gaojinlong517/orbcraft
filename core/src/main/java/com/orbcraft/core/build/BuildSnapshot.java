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
 * <p>{@code requiredDescPick} / {@code requiredEffectPick} / {@code carrierSlots}
 * 三个数必须一起下发：客户端要自己判断「确认按钮能不能点」，
 * 如果只发候选不发规则，客户端只能盲猜，最后靠服务端拒绝来纠正 ——
 * 玩家看到的是「点了没反应」，而不是「还差一张」。
 *
 * @param phase              当前阶段
 * @param kind               已选类型；未选时为 {@code null}
 * @param typeOptions        类型层的三个候选（其他层也下发，UI 可回显）
 * @param cards              当前层候选卡；类型层为空
 * @param chosen             当前层已选索引（升序）
 * @param requiredDescPick   描述层应选张数
 * @param requiredEffectPick 效果层应选张数
 * @param carrierSlots       描述层要求的载体张数
 * @param canConfirm         当前能否确认
 * @param confirmHint        还差什么；已满足为空串
 * @param confirmedCards     已确认的卡（跨层累计）
 * @param composition        已完成时非空
 * @param seed               随机种子（投诉回放用）
 */
public record BuildSnapshot(
        BuildPhase phase,
        BaseKind kind,
        List<TypeOption> typeOptions,
        List<DraftCard> cards,
        List<Integer> chosen,
        int requiredDescPick,
        int requiredEffectPick,
        int carrierSlots,
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

    /** 当前层要求选几张 —— UI 把这个数显示成「2 / 5」。 */
    public int requiredPickCount() {
        return switch (phase) {
            case DESC_CHOOSING, DESC_CONFIRMING -> requiredDescPick;
            case EFFECT_CHOOSING, EFFECT_CONFIRMING -> requiredEffectPick;
            default -> 0;
        };
    }

    public boolean finished() {
        return phase == BuildPhase.FINISHED;
    }

    /** 一行状态栏文案，如「描述卡层 2/5 已选 · 可确认」。 */
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
        return canConfirm ? head + " · 可确认" : head + " · " + confirmHint;
    }
}
