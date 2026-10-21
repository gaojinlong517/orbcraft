package com.orbcraft.core.quota;

import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Spec;

/**
 * 配额引擎（统御力池）。
 *
 * 用「5 点统御力 + 类别上限」双层约束，可以完整复现以下产品规则：
 * <pre>
 *   装备类     最多 2 件     每件 1 点
 *   主宠       最多 1 个     攻击型 3 点 / 守护型 2 点（二者共享 PRIMARY 名额）
 *   混乱 / 居民 不限数量     每只 1 点（实际被 5 点池自然封顶在 5 只）
 *   消耗品     不占配额
 * </pre>
 *
 * 关键点：混乱类"不限制"在玩法上成立，但物理上仍被统御力池封顶 ——
 * 这样既保住了设计意图，又不会让服务器被实体压垮。
 */
public final class QuotaManager {

    public static final int POOL_SIZE = 5;

    public static final int MAX_EQUIPMENT = 2;
    public static final int MAX_PRIMARY = 1;
    public static final int MAX_CHAOS = 5;

    public enum Category {
        /** 装备类，有件数上限。 */
        EQUIPMENT,
        /** 主宠（攻击/守护），有唯一性上限。 */
        PRIMARY,
        /** 混乱体与纯居民，无件数上限，仅受池约束。 */
        CHAOS,
        /** 不占配额。 */
        FREE
    }

    /** 玩家当前的配额占用快照。 */
    public record State(int equipment, int primary, int chaos, int usedPoints) {

        public static State empty() {
            return new State(0, 0, 0, 0);
        }

        public int remainingPoints() {
            return POOL_SIZE - usedPoints;
        }
    }

    public record Result(boolean allowed, int cost, State before, State after, String reason) {
    }

    public static Category categoryOf(Spec spec) {
        if (spec.type() == ProductType.CONSUMABLE) {
            return Category.FREE;
        }
        if (spec.type() == ProductType.EQUIPMENT) {
            return Category.EQUIPMENT;
        }
        if (spec.behavior().isPrimary()) {
            return Category.PRIMARY;
        }
        return Category.CHAOS;
    }

    public int costOf(Spec spec) {
        return switch (categoryOf(spec)) {
            case FREE -> 0;
            case EQUIPMENT -> 1;
            case PRIMARY -> spec.behavior() == BehaviorMode.ATTACK ? 3 : 2;
            case CHAOS -> 1;
        };
    }

    public Result tryConsume(State state, Spec spec) {
        Category cat = categoryOf(spec);
        int cost = costOf(spec);

        if (cat == Category.FREE) {
            return new Result(true, 0, state, state, "不占配额");
        }

        if (cat == Category.EQUIPMENT) {
            if (state.equipment() >= MAX_EQUIPMENT) {
                return reject(state, cost, "装备栏已满（上限 " + MAX_EQUIPMENT + " 件），需先解契一件");
            }
        }
        if (cat == Category.PRIMARY) {
            if (state.primary() >= MAX_PRIMARY) {
                return reject(state, cost, "已有一只主宠，攻击/守护型只能同时存在一个");
            }
        }
        if (cat == Category.CHAOS && state.chaos() >= MAX_CHAOS) {
            return reject(state, cost, "混乱体已达硬上限 " + MAX_CHAOS + " 只");
        }
        if (state.usedPoints() + cost > POOL_SIZE) {
            return reject(state, cost,
                    "统御力不足：需要 " + cost + " 点，剩余 " + state.remainingPoints() + " 点");
        }

        State after = switch (cat) {
            case EQUIPMENT -> new State(state.equipment() + 1, state.primary(), state.chaos(),
                    state.usedPoints() + cost);
            case PRIMARY -> new State(state.equipment(), state.primary() + 1, state.chaos(),
                    state.usedPoints() + cost);
            case CHAOS -> new State(state.equipment(), state.primary(), state.chaos() + 1,
                    state.usedPoints() + cost);
            case FREE -> state;
        };
        return new Result(true, cost, state, after, "ok");
    }

    private static Result reject(State state, int cost, String reason) {
        return new Result(false, cost, state, state, reason);
    }
}
