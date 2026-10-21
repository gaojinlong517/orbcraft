package com.orbcraft.core.render;

import com.orbcraft.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 载体模型映射自测 —— 无测试框架依赖，直接 {@code java} 运行。
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.render.CarrierModelSelfTest [dataDir]}
 *
 * <p>验证的是最容易出错、出错又最难在游戏里发现的三件事：
 * <ol>
 *   <li>35 个载体一个不缺、一个不多，且 modelId 与生成器写的 appearance_key **逐条相等**；</li>
 *   <li>代理实体全部落在白名单内（写错 id 不会编译报错，只会在召唤时静默失败）；</li>
 *   <li>未烘焙（NEW）的模型确实进不了 catalog，用真实的 {@link CatalogGuardBackend}
 *       能拿到 {@code MODEL_NOT_PACKED} 而不是「以为挂上了」。</li>
 * </ol>
 */
public final class CarrierModelSelfTest {

    private static int passed;
    private static int failed;

    private static Path dataDir;
    private static CarrierModelRegistry registry;

    public static void main(String[] args) throws IOException {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        Map<String, String> appearanceKeys = loadAppearanceKeys();

        loadRegistry();
        coverage(appearanceKeys);
        modelIdAlignment(appearanceKeys);
        proxies();
        animations();
        assets();
        catalogGate();
        guardRejectsUnbaked();
        fallback();
        badInput();
        immutability();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("载体模型自测失败 " + failed + " 项");
        }
    }

    // ================================================================ 数据准备

    /** 读真实词条表：carrier id → appearance_key。这是对齐断言的唯一真源。 */
    private static Map<String, String> loadAppearanceKeys() throws IOException {
        Map<String, Object> root = Json.parseObject(read("affixes.carrier.json"));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> a : Json.objectListOf(root, "affixes")) {
            String id = Json.str(a, "id", "");
            if (!id.startsWith("carrier.")) {
                continue;
            }
            out.put(id.substring("carrier.".length()), Json.str(a, "appearance_key", ""));
        }
        return out;
    }

    private static String read(String file) throws IOException {
        return Files.readString(dataDir.resolve(file), StandardCharsets.UTF_8);
    }

    private static void loadRegistry() throws IOException {
        section("注册表装载");
        registry = CarrierModelRegistry.load(read("carrier_models.json"));
        check("35 条载体计划", registry.size() == 35, "实际 " + registry.size());
        check("carrier_id 无重复", new LinkedHashSet<>(carrierIds()).size() == registry.size());
        check("modelId 无重复", registry.modelIds().size() == registry.size(),
                "实际 " + registry.modelIds().size());
        check("plans() 与 size() 一致", registry.plans().size() == registry.size());
    }

    private static List<String> carrierIds() {
        List<String> ids = new ArrayList<>();
        for (CarrierModelPlan p : registry.plans()) {
            ids.add(p.carrierId());
        }
        return ids;
    }

    // ================================================================ 全覆盖

    private static void coverage(Map<String, String> appearanceKeys) {
        section("35 载体全覆盖");
        check("词条表里有 35 个载体", appearanceKeys.size() == 35, "实际 " + appearanceKeys.size());

        int missing = 0;
        for (String id : appearanceKeys.keySet()) {
            boolean present = registry.planFor(id).isPresent();
            if (!present) {
                missing++;
            }
            check("载体 " + id + " 有计划", present);
        }
        check("无缺漏", missing == 0, "缺失 " + missing + " 个");

        int extra = 0;
        for (String id : carrierIds()) {
            if (!appearanceKeys.containsKey(id)) {
                extra++;
                System.out.println("    多余载体：" + id);
            }
        }
        check("无多余条目", extra == 0, "多余 " + extra + " 个");
    }

    /**
     * 最关键的一条：手写 JSON 最常见的错误就是 model_id 与 appearance_key 漂移。
     * 漂移不会崩溃，只会让「词条 pick 到了 hammer，模型却叫 model.hammer_new」这种静默错配。
     */
    private static void modelIdAlignment(Map<String, String> appearanceKeys) {
        section("model_id 与 appearance_key 逐条相等");
        for (Map.Entry<String, String> e : appearanceKeys.entrySet()) {
            CarrierModelPlan plan = registry.planFor(e.getKey()).orElse(null);
            String actual = plan == null ? "<缺失>" : plan.modelId();
            check(e.getKey() + " → " + e.getValue(), e.getValue().equals(actual), "实际 " + actual);
        }
    }

    private static void proxies() {
        section("代理实体白名单");
        int bad = 0;
        for (CarrierModelPlan p : registry.plans()) {
            if (!CarrierModelPlan.VANILLA_PROXIES.contains(p.proxyEntity())) {
                bad++;
                System.out.println("    越界：" + p.carrierId() + " → " + p.proxyEntity());
            }
            if (!p.proxyEntity().startsWith("minecraft:")) {
                bad++;
                System.out.println("    缺命名空间：" + p.carrierId() + " → " + p.proxyEntity());
            }
        }
        check("35 条代理全部命中白名单", bad == 0, "越界 " + bad + " 条");
        check("白名单只含 minecraft: 前缀",
                CarrierModelPlan.VANILLA_PROXIES.stream().allMatch(s -> s.startsWith("minecraft:")));

        long itemDisplay = registry.plans().stream()
                .filter(p -> "minecraft:item_display".equals(p.proxyEntity())).count();
        check("装备与消耗品统一用 item_display", itemDisplay == 21, "实际 " + itemDisplay);
        check("没有用 marker 当召唤物兜底", registry.plans().stream()
                .noneMatch(p -> "minecraft:marker".equals(p.proxyEntity())));
        check("龙用末影龙兜底", "minecraft:ender_dragon"
                .equals(registry.planFor("dragon").orElseThrow().proxyEntity()));
        check("傀儡用铁傀儡兜底", "minecraft:iron_golem"
                .equals(registry.planFor("golem").orElseThrow().proxyEntity()));
        check("虫群用蠹虫兜底", "minecraft:silverfish"
                .equals(registry.planFor("swarm").orElseThrow().proxyEntity()));
        check("骸骨用骷髅兜底", "minecraft:skeleton"
                .equals(registry.planFor("undead").orElseThrow().proxyEntity()));
        check("护甲用盔甲架兜底", "minecraft:armor_stand"
                .equals(registry.planFor("armor").orElseThrow().proxyEntity()));
    }

    private static void animations() {
        section("动画合法性");
        int illegal = 0;
        for (CarrierModelPlan p : registry.plans()) {
            for (String a : p.animations()) {
                if (!CarrierModelPlan.ALLOWED_ANIMATIONS.contains(a)) {
                    illegal++;
                    System.out.println("    非法动画：" + p.carrierId() + " → " + a);
                }
            }
            if (!p.animations().isEmpty() && !p.animations().contains("idle")) {
                illegal++;
                System.out.println("    缺 idle：" + p.carrierId());
            }
        }
        check("动画名全部合法且含 idle", illegal == 0, "问题 " + illegal + " 处");

        for (String id : List.of("crystal", "rune", "totem")) {
            CarrierModelPlan p = registry.planFor(id).orElseThrow();
            check(id + " 是静态模型", p.isStatic() && p.animations().isEmpty());
            check(id + " 的 note 写明静态", p.note().contains("静态"), "实际 " + p.note());
        }

        long dynamic = registry.plans().stream().filter(p -> !p.isStatic()).count();
        check("动态模型 32 个", dynamic == 32, "实际 " + dynamic);
        check("isStatic 与动画集等价", registry.plans().stream()
                .allMatch(p -> p.isStatic() == p.animations().isEmpty()));
    }

    private static void assets() {
        section("美术工作量分布");
        long reuse = countAsset(CarrierModelPlan.AssetKind.REUSE);
        long replace = countAsset(CarrierModelPlan.AssetKind.REPLACE);
        long fresh = countAsset(CarrierModelPlan.AssetKind.NEW);
        check("REUSE 至少 13 条", reuse >= 13, "实际 " + reuse);
        check("REUSE 18 条", reuse == 18, "实际 " + reuse);
        check("REPLACE 6 条", replace == 6, "实际 " + replace);
        check("NEW 11 条", fresh == 11, "实际 " + fresh);
        check("三类合计 35", reuse + replace + fresh == 35);
        check("baked 与 asset 等价", registry.plans().stream()
                .allMatch(p -> p.baked() == (p.asset() != CarrierModelPlan.AssetKind.NEW)));
        check("REUSE 全部可烘焙", registry.plans().stream()
                .filter(p -> p.asset() == CarrierModelPlan.AssetKind.REUSE)
                .allMatch(CarrierModelPlan::baked));
        check("NEW 全部不可烘焙", registry.plans().stream()
                .filter(p -> p.asset() == CarrierModelPlan.AssetKind.NEW)
                .noneMatch(CarrierModelPlan::baked));
    }

    private static long countAsset(CarrierModelPlan.AssetKind kind) {
        return registry.plans().stream().filter(p -> p.asset() == kind).count();
    }

    // ================================================================ 烘焙闸门

    private static void catalogGate() {
        section("catalog 只收已烘焙模型");
        ModelCatalog catalog = registry.toCatalog();
        long baked = registry.plans().stream().filter(CarrierModelPlan::baked).count();
        check("catalog 数量 = 35 - NEW", catalog.size() == 35 - 11, "实际 " + catalog.size());
        check("catalog 数量 = baked 数", catalog.size() == baked, "baked " + baked);

        int leaked = 0;
        for (CarrierModelPlan p : registry.plans()) {
            if (p.asset() == CarrierModelPlan.AssetKind.NEW && catalog.isPacked(p.modelId())) {
                leaked++;
                System.out.println("    未烘焙却登记：" + p.modelId());
            }
        }
        check("NEW 模型一个都没进 catalog", leaked == 0, "泄漏 " + leaked + " 个");

        int dropped = 0;
        for (CarrierModelPlan p : registry.plans()) {
            if (p.baked() && !catalog.isPacked(p.modelId())) {
                dropped++;
                System.out.println("    已烘焙却漏登记：" + p.modelId());
            }
        }
        check("已烘焙模型全部登记", dropped == 0, "漏登记 " + dropped + " 个");

        int animMismatch = 0;
        for (CarrierModelPlan p : registry.plans()) {
            if (!p.baked()) {
                continue;
            }
            Set<String> inCatalog = catalog.animationsOf(p.modelId()).orElse(Set.of());
            if (!inCatalog.equals(p.animationSet())) {
                animMismatch++;
            }
        }
        check("catalog 动画集与计划一致", animMismatch == 0, "不一致 " + animMismatch + " 个");

        check("静态模型在 catalog 里动画为空",
                catalog.animationsOf("model.crystal").orElseThrow().isEmpty());
        check("动态模型在 catalog 里带 idle", catalog.hasAnimation("model.sword", "idle"));
        check("catalog 不含未烘焙的 gun", !catalog.isPacked("model.gun"));
    }

    /** 用真实校验层走一遍「未烘焙 → 拒绝」的完整路径。 */
    private static void guardRejectsUnbaked() {
        section("CatalogGuardBackend 拒绝未烘焙载体");
        FakeBackend inner = new FakeBackend();
        ModelBackend guard = new CatalogGuardBackend(inner, registry.toCatalog());

        CarrierModelPlan raw = registry.planFor("gun").orElseThrow();
        check("gun 确实是未烘焙", !raw.baked());

        ModelSpawnResult rejected = guard.spawn(new ModelRequest(UUID.randomUUID(), raw.modelId()));
        check("未烘焙载体被降级", rejected.status() == ModelSpawnResult.Status.DEGRADED);
        check("原因是 MODEL_NOT_PACKED", rejected.reason() == ModelSpawnResult.Reason.MODEL_NOT_PACKED);
        check("降级即没有正确外观", !rejected.looksCorrect());
        check("未烘焙请求没有落到后端", inner.boundCount() == 0, "实际 " + inner.boundCount());
        check("降级需要提示玩家", ModelCapability.NAMED_VANILLA.shouldWarnPlayer());

        CarrierModelPlan ready = registry.planFor("sword").orElseThrow();
        ModelSpawnResult ok = guard.spawn(new ModelRequest(UUID.randomUUID(), ready.modelId()));
        check("已烘焙载体可挂载", ok.isOk());
        check("已烘焙请求落到后端", inner.boundCount() == 1, "实际 " + inner.boundCount());
    }

    private static void fallback() {
        section("兜底计划");
        CarrierModelPlan unknown = registry.fallbackFor("not_registered");
        check("未登记载体不返回 null", unknown != null);
        check("未登记载体代理为 item_display",
                "minecraft:item_display".equals(unknown.proxyEntity()), "实际 " + unknown.proxyEntity());
        check("未登记载体无动画", unknown.isStatic());
        check("未登记载体 modelId 仍遵守命名",
                "model.not_registered".equals(unknown.modelId()), "实际 " + unknown.modelId());
        check("未登记载体不可烘焙", !unknown.baked());
        check("null 载体 id 也不抛异常", registry.fallbackFor(null) != null);
        check("已登记载体返回原计划",
                registry.fallbackFor("hammer").asset() == CarrierModelPlan.AssetKind.NEW);
        check("未登记载体的 planFor 为空", registry.planFor("not_registered").isEmpty());
        check("已登记载体的 planFor 存在", registry.planFor("hammer").isPresent());
    }

    // ================================================================ 坏数据

    private static void badInput() {
        section("坏 JSON 的明确拒绝");

        expectIae("缺 model_id", """
                {"carriers":[{"carrier_id":"gun","proxy_entity":"minecraft:item_display","animations":[],"asset":"REUSE","note":"静态测试"}]}
                """, "model_id");

        expectIae("动画名非法", one("gun", "model.gun", "minecraft:item_display", "\"idle\",\"swing\"", "NEW"), "动画名非法");

        expectIae("代理实体越界", one("gun", "model.gun", "minecraft:laser_gun", "\"idle\"", "NEW"), "白名单");

        expectIae("model_id 缺前缀", one("gun", "gun", "minecraft:item_display", "\"idle\"", "NEW"), "开头");

        expectIae("model_id 与 carrier 错配",
                one("gun", "model.hammer", "minecraft:item_display", "\"idle\"", "NEW"), "必须等于");

        expectIae("asset 非法", one("gun", "model.gun", "minecraft:item_display", "\"idle\"", "MAYBE"), "asset 非法");

        expectIae("carriers 为空", "{\"carriers\":[]}", "carriers");

        expectIae("载体重复", """
                {"carriers":[
                  {"carrier_id":"gun","model_id":"model.gun","proxy_entity":"minecraft:item_display","animations":["idle"],"asset":"NEW","note":"a"},
                  {"carrier_id":"gun","model_id":"model.gun","proxy_entity":"minecraft:item_display","animations":["idle"],"asset":"NEW","note":"b"}
                ]}
                """, "重复");

        expectIae("静态模型未写静态说明",
                one("crystal", "model.crystal", "minecraft:end_crystal", "", "REUSE"), "静态");

        boolean threwOnNull = false;
        try {
            CarrierModelRegistry.load(null);
        } catch (RuntimeException e) {
            threwOnNull = true;
        }
        check("null JSON 被拒", threwOnNull);

        check("空注册表可构造", CarrierModelRegistry.empty().size() == 0);
        check("空注册表兜底仍可用",
                CarrierModelRegistry.empty().fallbackFor("x").proxyEntity().equals("minecraft:item_display"));
        check("空注册表 catalog 为空", CarrierModelRegistry.empty().toCatalog().size() == 0);
    }

    private static void expectIae(String name, String json, String keyword) {
        String message = null;
        try {
            CarrierModelRegistry.load(json);
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        check(name + " → 抛异常", message != null);
        check(name + " → 说明含「" + keyword + "」",
                message != null && message.contains(keyword), "实际 " + message);
    }

    private static String one(String carrierId, String modelId, String proxy, String animations, String asset) {
        return "{\"carriers\":[{\"carrier_id\":\"" + carrierId
                + "\",\"model_id\":\"" + modelId
                + "\",\"proxy_entity\":\"" + proxy
                + "\",\"animations\":[" + animations + "]"
                + ",\"tintable\":false,\"asset\":\"" + asset
                + "\",\"note\":\"测试\"}]}";
    }

    private static void immutability() {
        section("不可变性");
        boolean plansImmutable = false;
        try {
            registry.plans().add(registry.plans().get(0));
        } catch (UnsupportedOperationException e) {
            plansImmutable = true;
        }
        check("plans() 不可修改", plansImmutable);

        boolean animsImmutable = false;
        try {
            registry.plans().get(0).animations().add("idle");
        } catch (UnsupportedOperationException e) {
            animsImmutable = true;
        }
        check("animations 不可修改", animsImmutable);
        check("plans() 顺序与数据文件一致",
                "gun".equals(registry.plans().get(0).carrierId()), "实际 " + registry.plans().get(0).carrierId());
        check("最后一条是 serpent",
                "serpent".equals(registry.plans().get(registry.size() - 1).carrierId()));
    }

    // ================================================================ 工具

    private static void section(String name) {
        System.out.println("【" + name + "】");
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "（" + detail + "）"));
        }
    }

    private static final class FakeBackend implements ModelBackend {
        private final Set<UUID> bound = new LinkedHashSet<>();

        public String backendId() {
            return "bettermodel";
        }

        public ModelCapability capability() {
            return ModelCapability.FULL;
        }

        public boolean available() {
            return true;
        }

        public ModelSpawnResult spawn(ModelRequest request) {
            bound.add(request.hostEntityId());
            return ModelSpawnResult.ok();
        }

        public boolean play(UUID hostEntityId, String animation) {
            return true;
        }

        public void despawn(UUID hostEntityId) {
            bound.remove(hostEntityId);
        }

        public int boundCount() {
            return bound.size();
        }
    }
}
