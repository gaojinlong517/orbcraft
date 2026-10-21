package com.orbcraft.fabric.contract;

/**
 * 三个契约的运行期取用点 —— 让五路在别人还没写完时也能各自编译、各自跑。
 *
 * <p>为什么需要它：S1 要调 {@link SummonSpawner}，但 S3 可能还没开工。
 * 如果 S1 直接 {@code new} 一个 S3 的实现类，S1 就被 S3 的进度绑死了；
 * 反过来 S1 自己写个临时实现，合并时那份临时实现又会变成第二套真相。
 * 折中办法是：<b>依赖接口，运行期取实现</b>。S1 的代码永远只见
 * {@code Services.summonSpawner()}，S3 什么时候就位，S1 就什么时候自动接上。
 *
 * <p><b>本文件归主线所有，各路只读。</b> 参数里出现的三类实现分别由
 * S1 / S3 / S4 在各自初始化时 {@code install}。
 *
 * <p><b>为什么重复安装不同的实例要报错。</b> 两处都注册意味着有一份实现永远不生效，
 * 而它看起来「已经写完了」—— 这是最难查的一类问题（代码在、日志不报错、行为是另一套）。
 * 同一个实例重复安装则允许（热重载、双端各初始化一次都属正常）。
 */
public final class Services {

    private static volatile OrbItemContract orbItem;
    private static volatile SummonSpawner summonSpawner;
    private static volatile PlayerStore playerStore;

    private Services() {
    }

    public static void install(OrbItemContract impl) {
        orbItem = replace(orbItem, impl, "OrbItemContract（S1 orb-item）");
    }

    public static void install(SummonSpawner impl) {
        summonSpawner = replace(summonSpawner, impl, "SummonSpawner（S3 spawner）");
    }

    public static void install(PlayerStore impl) {
        playerStore = replace(playerStore, impl, "PlayerStore（S4 persistence）");
    }

    /**
     * @throws IllegalStateException 该契约还没有任何实现注册
     */
    public static OrbItemContract orbItem() {
        return require(orbItem, "OrbItemContract（S1 orb-item）");
    }

    /**
     * @throws IllegalStateException 该契约还没有任何实现注册
     */
    public static SummonSpawner summonSpawner() {
        return require(summonSpawner, "SummonSpawner（S3 spawner）");
    }

    /**
     * @throws IllegalStateException 该契约还没有任何实现注册
     */
    public static PlayerStore playerStore() {
        return require(playerStore, "PlayerStore（S4 persistence）");
    }

    /** 全部就位才算初始化完成；只给启动自检用，业务代码不要拿它做分支。 */
    public static boolean ready() {
        return orbItem != null && summonSpawner != null && playerStore != null;
    }

    private static <T> T replace(T current, T next, String name) {
        if (next == null) {
            throw new IllegalArgumentException(name + " 的实现不能为 null");
        }
        if (current != null && current != next) {
            throw new IllegalStateException(name + " 已经被另一份实现注册过："
                    + current.getClass().getName() + " vs " + next.getClass().getName()
                    + "。两份实现同时存在时只有一份生效，请先确认是不是重复实现。");
        }
        return next;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " 尚未注册。"
                    + "检查该路的模组初始化是否执行过，或在 runServer 日志里搜 install 失败的原因。");
        }
        return value;
    }
}
