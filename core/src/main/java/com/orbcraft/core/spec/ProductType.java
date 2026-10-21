package com.orbcraft.core.spec;

/** 产物类型。新增 SHAPER 时要同步更新 QuotaManager 与两个平台的落地器。 */
public enum ProductType {

    /** 装备：占用装备配额。 */
    EQUIPMENT("装备"),
    /** 消耗品：不占配额。 */
    CONSUMABLE("消耗品"),
    /** 实体召唤物：占用召唤配额。 */
    ENTITY("召唤物"),
    /** 可描述生成的 AI 角色：占用召唤配额，行为由 BehaviorMode 决定。 */
    NPC("角色");

    private final String zh;

    ProductType(String zh) {
        this.zh = zh;
    }

    public String zh() {
        return zh;
    }
}
