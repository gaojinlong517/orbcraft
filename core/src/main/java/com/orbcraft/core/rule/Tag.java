package com.orbcraft.core.rule;

/**
 * 语义标签。规则引擎的输出单位，也是外观/特效匹配的依据。
 *
 * @param dimension 维度
 * @param key       稳定标识（英文），落地器按它查表
 */
public record Tag(Dimension dimension, String key) {

    public enum Dimension {
        /** 元素：fire / ice / thunder / poison / light / dark / rock / wind */
        ELEMENT,
        /** 形态：sword / bow / dragon / artisan … 决定产物类型 */
        FORM,
        /** 效果：lifesteal / explode / haste … 映射为数据组件 */
        EFFECT,
        /** 程度：weak / strong / extreme / legend。决定稀有度 */
        INTENSITY
    }

    /** 去重键：同一维度同一 key 只保留一次。 */
    public String dedupKey() {
        return dimension.name() + ':' + key;
    }
}
