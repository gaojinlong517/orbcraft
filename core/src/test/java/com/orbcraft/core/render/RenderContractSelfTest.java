package com.orbcraft.core.render;

import java.util.Set;
import java.util.UUID;

/**
 * 外观契约自测 —— 无第三方测试框架依赖，直接 {@code java} 运行。
 *
 * <p>验证的是「依赖缺失 / 模型未打包 / 实现抛异常」三种最坏情况下，
 * 玩法链路是否仍然可用。这三条是引入外部渲染库之后真正新增的风险面。
 */
public final class RenderContractSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        catalogBuild();
        catalogMerge();
        selectorPicksHighest();
        selectorFallsBackToNull();
        guardRejectsUnpacked();
        guardSwallowsException();
        requestValidation();
        fallbackKeepsGameplayAlive();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("外观契约自测失败 " + failed + " 项");
        }
    }

    // ---------- 用例 ----------

    private static void catalogBuild() {
        ModelCatalog c = ModelCatalog.of(java.util.Map.of(
                "dragon_fire", Set.of("idle", "attack", "roar"),
                "sword_ice", Set.of()));
        check("清单包含已登记模型", c.isPacked("dragon_fire"));
        check("清单不含未登记模型", !c.isPacked("dragon_void"));
        check("动画可查", c.hasAnimation("dragon_fire", "attack"));
        check("静态模型无动画", !c.hasAnimation("sword_ice", "attack"));
        check("空清单拒绝一切", !ModelCatalog.empty().isPacked("dragon_fire"));
        check("大小正确", c.size() == 2);
    }

    private static void catalogMerge() {
        ModelCatalog a = ModelCatalog.ofStatic(Set.of("a", "b"));
        ModelCatalog b = ModelCatalog.of(java.util.Map.of("b", Set.of("attack"), "c", Set.of("idle")));
        ModelCatalog m = a.merge(b);
        check("合并后有 3 个模型", m.size() == 3);
        check("合并时动画取并集而非覆盖", m.hasAnimation("b", "attack"));
        check("原清单仍不可变", a.size() == 2);
    }

    private static void selectorPicksHighest() {
        ModelCatalog c = ModelCatalog.ofStatic(Set.of("m1"));
        ModelBackend full = new FakeBackend("bettermodel", ModelCapability.FULL, true);
        ModelBackend fallback = new NullModelBackend();
        ModelBackend picked = ModelBackends.select(c, fallback, full); // 故意乱序
        check("选择器按能力挑最高", "bettermodel".equals(picked.backendId()));
        check("选择结果被校验层包裹", picked instanceof CatalogGuardBackend);
        check("能力等级为 FULL", picked.capability() == ModelCapability.FULL);
    }

    private static void selectorFallsBackToNull() {
        ModelCatalog c = ModelCatalog.empty();
        ModelBackend broken = new FakeBackend("bettermodel", ModelCapability.FULL, false);
        ModelBackend picked = ModelBackends.select(c, broken);
        check("后端不可用则退化到 null", "null".equals(picked.backendId()));
        check("退化后能力为 NAMED_VANILLA", picked.capability() == ModelCapability.NAMED_VANILLA);
        check("退化后仍可用", picked.available());
        check("退化需提示玩家", picked.capability().shouldWarnPlayer());
    }

    private static void guardRejectsUnpacked() {
        ModelCatalog c = ModelCatalog.ofStatic(Set.of("dragon_fire"));
        ModelBackend b = new CatalogGuardBackend(
                new FakeBackend("bettermodel", ModelCapability.FULL, true), c);
        ModelSpawnResult r = b.spawn(new ModelRequest(UUID.randomUUID(), "dragon_void"));
        check("未打包模型被拒绝", r.status() == ModelSpawnResult.Status.DEGRADED);
        check("拒绝原因是未打包", r.reason() == ModelSpawnResult.Reason.MODEL_NOT_PACKED);
        check("未打包时不显示正确外观", !r.looksCorrect());

        ModelSpawnResult ok = b.spawn(new ModelRequest(UUID.randomUUID(), "dragon_fire"));
        check("已打包模型可挂载", ok.isOk());
    }

    private static void guardSwallowsException() {
        ModelCatalog c = ModelCatalog.ofStatic(Set.of("m1"));
        ModelBackend throwing = new ModelBackend() {
            public String backendId() { return "boom"; }
            public ModelCapability capability() { return ModelCapability.FULL; }
            public boolean available() { return true; }
            public ModelSpawnResult spawn(ModelRequest r) { throw new IllegalStateException("炸了"); }
            public boolean play(UUID h, String a) { throw new IllegalStateException("炸了"); }
            public void despawn(UUID h) { throw new IllegalStateException("炸了"); }
            public int boundCount() { return 0; }
        };
        ModelBackend guarded = new CatalogGuardBackend(throwing, c);
        ModelSpawnResult r = guarded.spawn(new ModelRequest(UUID.randomUUID(), "m1"));
        check("实现抛异常不冒到上层", r.status() == ModelSpawnResult.Status.FAILED);
        check("异常被归类为 INTERNAL", r.reason() == ModelSpawnResult.Reason.INTERNAL);
        check("play 抛异常返回 false", !guarded.play(UUID.randomUUID(), "idle"));
        guarded.despawn(UUID.randomUUID()); // 不应抛出
        check("despawn 抛异常被吞掉", true);
    }

    private static void requestValidation() {
        boolean threw = false;
        try {
            new ModelRequest(UUID.randomUUID(), "m1", 10, null, true);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("缩放低于 25 被拒", threw);

        threw = false;
        try {
            new ModelRequest(null, "m1");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("宿主为空被拒", threw);

        ModelRequest tinted = new ModelRequest(UUID.randomUUID(), "m1").withTint(0xFF3A86FF);
        check("染色可变副本生效", tinted.tintArgb() == 0xFF3A86FF);
    }

    private static void fallbackKeepsGameplayAlive() {
        // 最关键的一条：没有 BetterModel 时，召唤流程必须完整可用
        ModelBackend b = ModelBackends.select(ModelCatalog.empty());
        ModelSpawnResult r = b.spawn(new ModelRequest(UUID.randomUUID(), "anything"));
        check("无依赖时 spawn 不失败", r.status() == ModelSpawnResult.Status.DEGRADED);
        check("无依赖时不返回 FAILED", r.reason() != ModelSpawnResult.Reason.INTERNAL);
        check("无依赖时绑定计数正常", b.boundCount() >= 0);
    }

    // ---------- 工具 ----------

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    private static final class FakeBackend implements ModelBackend {
        private final String id;
        private final ModelCapability cap;
        private final boolean available;
        private final Set<UUID> bound = java.util.concurrent.ConcurrentHashMap.newKeySet();

        FakeBackend(String id, ModelCapability cap, boolean available) {
            this.id = id;
            this.cap = cap;
            this.available = available;
        }

        public String backendId() { return id; }
        public ModelCapability capability() { return cap; }
        public boolean available() { return available; }

        public ModelSpawnResult spawn(ModelRequest r) {
            bound.add(r.hostEntityId());
            return ModelSpawnResult.ok();
        }

        public boolean play(UUID h, String a) { return true; }
        public void despawn(UUID h) { bound.remove(h); }
        public int boundCount() { return bound.size(); }
    }
}
