package com.orbcraft.core.spec;

/**
 * 召唤物行为模式。配额与风险都按这个维度分档：
 * ATTACK / GUARD 走主宠配额（1 个），CHAOS 无配额但会攻击任何人且互相攻击。
 */
public enum BehaviorMode {

    /** 无行为：装备、消耗品、纯居民 NPC。 */
    NONE("无"),
    /** 攻击型：攻击玩家指定的目标。 */
    ATTACK("攻击"),
    /** 守护型：守在原地，攻击靠近的敌对生物。 */
    GUARD("守护"),
    /** 混乱型：攻击最近的任意生物（含主人与其他召唤物），不接受指令。 */
    CHAOS("混乱");

    private final String zh;

    BehaviorMode(String zh) {
        this.zh = zh;
    }

    public String zh() {
        return zh;
    }

    /** 是否占用"主宠"唯一配额。 */
    public boolean isPrimary() {
        return this == ATTACK || this == GUARD;
    }

    /** 是否会主动攻击主人。 */
    public boolean canHarmOwner() {
        return this == CHAOS;
    }
}
