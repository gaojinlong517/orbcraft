package com.orbcraft.fabric.item;

import com.orbcraft.core.hatch.IncubationService;

/**
 * 全项目唯一的 {@link IncubationService} 实例。
 *
 * <p><b>为什么需要这么个只有一行方法的类。</b> {@code IncubationService} 的所有方法都是
 * {@code synchronized} 的实例方法，而契约里没有、也不该有「把服务传给谁」的参数
 * （{@code OrbItemContract} 的签名已冻结）。于是「谁持有这个实例」必须由一个明确的点回答：
 * <ul>
 *   <li>S2 定稿走 {@code submit}，要同一个实例；</li>
 *   <li>S4 存档走 {@code ticketsOf/stateOf/restore}，要同一个实例；</li>
 *   <li>S5 是唯一的 {@code tick} 推进点，要同一个实例；</li>
 *   <li>S1 投掷/落地走 {@code throwBall/deploy}，要同一个实例。</li>
 * </ul>
 * 任何人自己 {@code new IncubationService()}，都会得到一份互相看不见的票据表 ——
 * 症状是「S2 发出去的球 S1 说查不到票据」，而且两边都不报错。
 *
 * <p>放在 {@code item} 包是因为球最先需要它（读条与投掷都靠票据状态）。
 * 这是接线点，不是业务逻辑：需要换实现（比如上限从 5 改大）时只改这里。
 * 初始化顺序无关 —— 静态字段在第一次被访问时才创建，谁先调都拿到同一个对象。
 */
public final class OrbHatch {

    private static final IncubationService SERVICE = new IncubationService();

    private OrbHatch() {
    }

    public static IncubationService service() {
        return SERVICE;
    }
}
