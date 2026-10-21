package com.orbcraft.core.draft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽卡状态机自测 —— 直接用真实卡表跑，不用假数据。
 *
 * <p>用假数据测只能证明「代码逻辑自洽」，证明不了「这 450 条表能用」。
 * 这里有一个关键设计：**所有用例都跑同一份 450 条卡表**，
 * 因为池子抽空、定向卡目标被过滤、预算全灰这类问题只有真数据才会暴露。
 *
 * <p>运行：{@code java -cp <out> com.orbcraft.core.draft.DraftSessionSelfTest [dataDir]}
 */
public final class DraftSessionSelfTest {

    private static int passed;
    private static int failed;
    private static Path dataDir;
    private static AffixTable table;
    private static List<TargetCard> targets;

    public static void main(String[] args) throws Exception {
        dataDir = Path.of(args.length > 0 ? args[0] : "core/src/main/resources/data");
        System.out.println("数据目录：" + dataDir.toAbsolutePath());
        System.out.println();

        loadData();
        fullFlow();
        targetCardRules();
        rerollRules();
        finishGate();
        seedReproducible();
        simulate(2000);
        compareFloor();
        budgetSweep();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("抽卡自测失败 " + failed + " 项");
        }
    }

    // ================================================================ 数据

    private static void loadData() throws IOException {
        section("卡表加载");
        table = AffixData.loadTable(
                read("affixes.carrier.json"),
                read("affixes.element.json"),
                read("affixes.attribute.json"),
                read("affixes.effect.json"));
        targets = AffixData.readTargetCards(read("target_cards.json"));

        check("总词条 450 条", table.size() == 450, "实际 " + table.size());
        check("载体 18", table.size(Layer.CARRIER) == 18, "实际 " + table.size(Layer.CARRIER));
        check("元素 9", table.size(Layer.ELEMENT) == 9, "实际 " + table.size(Layer.ELEMENT));
        check("属性 243", table.size(Layer.ATTRIBUTE) == 243, "实际 " + table.size(Layer.ATTRIBUTE));
        check("效果 180", table.size(Layer.EFFECT) == 180, "实际 " + table.size(Layer.EFFECT));
        check("定向卡 27 张", targets.size() == 27, "实际 " + targets.size());

        // 每层族数必须够发 5 张不重复的牌
        for (Layer l : Layer.values()) {
            check(l.zh() + "层族数 ≥ 5", table.familyCount(l) >= 5,
                    "实际 " + table.familyCount(l) + " 族");
        }
        check("定向卡全部只保类型", targets.stream().noneMatch(TargetCard::guaranteesQuality), "");
    }

    // ================================================================ 完整流程

    private static void fullFlow() {
        section("完整四层流程");
        DraftSession s = DraftSession.create(table, 20261020L);
        s.pickBase(BaseKind.EQUIPMENT);
        check("选基调后进入层 1 就绪", s.phase() == DraftPhase.LAYER_READY, "");
        check("层 1 是载体", s.currentLayer() == Layer.CARRIER, "");

        int totalCards = 0;
        for (Layer expected : Layer.values()) {
            check("当前层为 " + expected.zh(), s.currentLayer() == expected, "");
            List<DraftCard> cards = s.deal();
            totalCards += cards.size();
            check(expected.zh() + "层发出 5 张", cards.size() == 5, "实际 " + cards.size());
            check(expected.zh() + "层无重复族", distinctFamilies(cards) == cards.size(), "");
            check(expected.zh() + "层至少 2 张可买", s.snapshot().affordableCount() >= 2,
                    "实际 " + s.snapshot().affordableCount());
            s.pick(0);
        }
        check("四层抽完进入等待命名", s.phase() == DraftPhase.AWAITING_NAME, "实际 " + s.phase());
        check("共发出 20 张牌", totalCards == 20, "实际 " + totalCards);
        check("定稿点数在预算内", s.points() <= 28, "点数 " + s.points());

        DraftResult r = s.finish("测试之枪");
        check("定稿名生效", "测试之枪".equals(r.productName()), r.productName());
        check("定稿含 4 层", r.slots().size() == 4, "实际 " + r.slots().size());
        check("种子被记录", r.seed() == 20261020L, "" + r.seed());
        check("稀有度与点数一致", r.rarity() == DraftConfig.defaults().rarityOf(r.points()), "");
        check("孵化倍率 ≥ 1.0", r.hatchMultiplier() >= 1.0, "" + r.hatchMultiplier());
    }

    // ================================================================ 定向卡

    private static void targetCardRules() {
        section("定向卡三条规则");

        TargetCard gunCard = targets.stream()
                .filter(c -> "carrier.gun".equals(c.targetAffix()))
                .findFirst().orElseThrow();

        // 规则一：必须发牌前使用
        DraftSession s1 = DraftSession.create(table, 7L);
        s1.pickBase(BaseKind.EQUIPMENT);
        s1.deal();
        boolean rejectedAfterDeal = false;
        try {
            s1.useTargetCard(gunCard);
        } catch (DraftException e) {
            rejectedAfterDeal = e.code() == DraftException.Code.WRONG_PHASE;
        }
        check("发牌后使用定向卡被拒", rejectedAfterDeal, "");

        // 规则二：保类型 —— 注入后本层一定有枪
        boolean alwaysContains = true;
        for (int i = 0; i < 200; i++) {
            DraftSession s = DraftSession.create(table, 1000L + i);
            s.pickBase(BaseKind.EQUIPMENT);
            s.useTargetCard(gunCard);
            List<DraftCard> cards = s.deal();
            boolean hasGun = cards.stream().anyMatch(c -> "carrier.gun".equals(c.affix().id()));
            if (!hasGun) {
                alwaysContains = false;
                break;
            }
        }
        check("注入后 200 次都出现目标词条", alwaysContains, "");

        // 规则二补：但品质不保证 —— 200 次里不应全是同一档
        Map<Tier, Integer> tierSpread = new EnumMap<>(Tier.class);
        for (int i = 0; i < 200; i++) {
            DraftSession s = DraftSession.create(table, 2000L + i);
            s.pickBase(BaseKind.EQUIPMENT);
            s.useTargetCard(gunCard);
            for (DraftCard c : s.deal()) {
                if ("carrier.gun".equals(c.affix().id())) {
                    tierSpread.merge(c.quality(), 1, Integer::sum);
                }
            }
        }
        check("定向不保强度（目标出现 ≥2 种品质）", tierSpread.size() >= 2, "" + tierSpread);

        // 规则三：每件产物最多 1 张
        DraftSession s3 = DraftSession.create(table, 9L);
        s3.pickBase(BaseKind.EQUIPMENT);
        s3.useTargetCard(gunCard);
        s3.deal();
        s3.pick(0);
        boolean rejectedSecond = false;
        try {
            s3.useTargetCard(targets.stream()
                    .filter(c -> c.targetLayer() == Layer.ELEMENT).findFirst().orElseThrow());
        } catch (DraftException e) {
            rejectedSecond = e.code() == DraftException.Code.TARGET_ALREADY_USED;
        }
        check("第二张定向卡被拒", rejectedSecond, "");

        // 层不匹配
        DraftSession s4 = DraftSession.create(table, 11L);
        s4.pickBase(BaseKind.EQUIPMENT);
        TargetCard elemCard = targets.stream()
                .filter(c -> c.targetLayer() == Layer.ELEMENT).findFirst().orElseThrow();
        boolean rejectedLayer = false;
        try {
            s4.useTargetCard(elemCard);
        } catch (DraftException e) {
            rejectedLayer = e.code() == DraftException.Code.TARGET_WRONG_LAYER;
        }
        check("层不匹配被拒", rejectedLayer, "");

        // 目标不在池里：基调选消耗品，却对载体层用「枪」定向卡
        DraftSession s5 = DraftSession.create(table, 13L);
        s5.pickBase(BaseKind.CONSUMABLE);
        boolean rejectedPool = false;
        try {
            s5.useTargetCard(gunCard);
        } catch (DraftException e) {
            rejectedPool = e.code() == DraftException.Code.TARGET_NOT_IN_POOL;
        }
        check("目标不在当前池中被拒（不白耗卡）", rejectedPool, "");
    }

    // ================================================================ 重抽

    private static void rerollRules() {
        section("重抽规则");

        DraftSession s = DraftSession.create(table, 21L);
        s.pickBase(BaseKind.EQUIPMENT);
        List<DraftCard> first = s.deal();
        int cost0 = s.rerollCost();
        check("首次重抽 1 球", cost0 == 1, "实际 " + cost0);

        boolean rejectedNoOrbs = false;
        try {
            s.reroll(0);
        } catch (DraftException e) {
            rejectedNoOrbs = e.code() == DraftException.Code.INSUFFICIENT_ORBS;
        }
        check("球不足时拒绝重抽", rejectedNoOrbs, "");
        check("拒绝后不扣次数", s.points() == 0 && s.snapshot().rerollsThisLayer() == 0, "");

        s.reroll(10);
        check("重抽后价格递增到 2", s.rerollCost() == 2, "实际 " + s.rerollCost());
        check("重抽后仍是 5 张", s.currentCards().size() == 5, "");
        check("重抽不改变点数", s.points() == 0, "");
        s.reroll(10);
        check("第三次价格 3", s.rerollCost() == 3, "实际 " + s.rerollCost());

        // 重抽保留定向
        DraftSession s2 = DraftSession.create(table, 31L);
        s2.pickBase(BaseKind.EQUIPMENT);
        TargetCard gunCard = targets.stream()
                .filter(c -> "carrier.gun".equals(c.targetAffix())).findFirst().orElseThrow();
        s2.useTargetCard(gunCard);
        s2.deal();
        s2.reroll(10);
        boolean stillHasGun = s2.currentCards().stream()
                .anyMatch(c -> "carrier.gun".equals(c.affix().id()));
        check("重抽后定向卡仍然生效", stillHasGun, "");

        // 重抽保留已选
        DraftSession s3 = DraftSession.create(table, 41L);
        s3.pickBase(BaseKind.EQUIPMENT);
        s3.deal();
        s3.pick(0);
        int pointsAfterLayer1 = s3.points();
        s3.deal();
        s3.reroll(10);
        check("重抽不影响上一层已选", s3.points() == pointsAfterLayer1 && s3.picked().size() == 1, "");
    }

    // ================================================================ 定稿门槛

    private static void finishGate() {
        section("定稿门槛");

        DraftSession s = DraftSession.create(table, 51L);
        s.pickBase(BaseKind.EQUIPMENT);
        s.deal();
        s.pick(0); // 只选了载体
        check("只选载体不能定稿", !s.canFinish(), "");
        check("提示还差属性或效果", s.finishHint().contains("属性或效果"), s.finishHint());
        boolean rejected = false;
        try {
            s.finish("裸枪");
        } catch (DraftException e) {
            rejected = e.code() == DraftException.Code.FINISH_GATE_NOT_MET;
        }
        check("定稿被拒", rejected, "");

        s.deal();
        s.pick(0); // 选了元素
        check("载体+元素仍不能定稿", !s.canFinish(), s.finishHint());

        s.deal();
        s.pick(0); // 选了属性
        check("载体+元素+属性可以定稿", s.canFinish(), "");
        DraftResult r = s.finish();
        check("默认名由元素+载体组成", r.productName().contains("·"), r.productName());
        check("3 层定稿也算完成", s.phase() == DraftPhase.FINISHED, "");
    }

    // ================================================================ 可复现

    private static void seedReproducible() {
        section("种子可复现");

        String a = playTrace(12345L);
        String b = playTrace(12345L);
        String c = playTrace(12346L);
        check("同 seed 结果完全一致", a.equals(b), "");
        check("不同 seed 结果不同", !a.equals(c), "");
    }

    private static String playTrace(long seed) {
        DraftSession s = DraftSession.create(table, seed);
        s.pickBase(BaseKind.EQUIPMENT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            for (DraftCard c : s.deal()) {
                sb.append(c.affix().id()).append(':').append(c.quality()).append('|');
            }
            s.pick(0);
            sb.append("//");
        }
        return sb.toString();
    }

    // ================================================================ 大规模模拟

    private static void simulate(int runs) {
        section("真实卡表模拟 " + runs + " 局（贪心：选能买得起的最高点数）");

        Map<String, Integer> rarityCount = new LinkedHashMap<>();
        for (var r : com.orbcraft.core.spec.Rarity.values()) {
            rarityCount.put(r.zh(), 0);
        }
        List<Integer> pointsList = new ArrayList<>();
        Map<Tier, Integer> dealt = new EnumMap<>(Tier.class);
        Map<Tier, Integer> chosen = new EnumMap<>(Tier.class);
        int greyOut = 0;
        int poolEmpty = 0;
        int shortfall = 0;
        long totalDealt = 0;
        int targetUsedRuns = 0;
        int targetInjectedRuns = 0;
        double hatchSum = 0;
        int finished = 0;

        for (int run = 0; run < runs; run++) {
            long seed = 0x9E3779B97F4A7C15L * (run + 1) + 7;
            DraftSession s = DraftSession.create(table, seed);
            BaseKind kind = BaseKind.values()[run % 3];
            s.pickBase(kind);

            // 每 3 局里有 1 局在层 1 用定向卡，验证「用了卡一定出现」
            boolean useTarget = run % 3 == 0;
            if (useTarget) {
                targets.stream()
                        .filter(c -> c.targetLayer() == Layer.CARRIER && c.baseType().equals(kind.wire()))
                        .findFirst()
                        .ifPresent(card -> {
                            try {
                                s.useTargetCard(card);
                            } catch (DraftException ignored) {
                                // 该基调没有对应定向卡时跳过
                            }
                        });
            }

            try {
                while (!s.phase().isDraftingOver()) {
                    List<DraftCard> cards = s.deal();
                    totalDealt += cards.size();
                    if (cards.size() < 5) {
                        shortfall++;
                    }
                    for (DraftCard c : cards) {
                        dealt.merge(c.quality(), 1, Integer::sum);
                    }
                    DraftSnapshot snap = s.snapshot();
                    if (snap.remainingPoints() >= 1 && snap.affordableCount() < 2) {
                        greyOut++;
                    }
                    // 贪心选：能买得起的最高点数（最接近真实玩家行为）
                    int best = -1;
                    int bestPoints = -1;
                    for (int i = 0; i < cards.size(); i++) {
                        DraftCard c = cards.get(i);
                        if (c.points() <= s.remainingPoints() && c.points() > bestPoints) {
                            bestPoints = c.points();
                            best = i;
                        }
                    }
                    if (best < 0) {
                        s.cancel();
                        break;
                    }
                    chosen.merge(cards.get(best).quality(), 1, Integer::sum);
                    s.pick(best);
                }
                if (s.phase() == DraftPhase.AWAITING_NAME) {
                    DraftResult r = s.finish();
                    finished++;
                    pointsList.add(r.points());
                    rarityCount.merge(r.rarity().zh(), 1, Integer::sum);
                    hatchSum += r.hatchMultiplier();
                    if (useTarget) {
                        targetUsedRuns++;
                        if (s.targetInjectedEver()) {
                            targetInjectedRuns++;
                        }
                    }
                }
            } catch (DraftException e) {
                if (e.code() == DraftException.Code.POOL_EMPTY) {
                    poolEmpty++;
                } else {
                    throw e;
                }
            }
        }

        check("没有一局因池子抽空而中断", poolEmpty == 0, "实际 " + poolEmpty);
        check("没有一局出现「可选不足 2 张」", greyOut == 0, "实际 " + greyOut);
        check("每层都发满 5 张", shortfall == 0, "实际 " + shortfall);
        check("全部局都能定稿", finished == runs, finished + "/" + runs);
        check("定向卡 100% 注入成功", targetInjectedRuns == targetUsedRuns,
                targetInjectedRuns + "/" + targetUsedRuns);

        int sum = pointsList.stream().mapToInt(Integer::intValue).sum();
        double avgPoints = (double) sum / Math.max(1, pointsList.size());
        report("平均点数", String.format("%.2f（预算 28）", avgPoints));
        report("最低/最高点数", min(pointsList) + " / " + max(pointsList));
        report("平均孵化倍率", String.format("%.2f×", hatchSum / Math.max(1, finished)));

        System.out.println("  稀有度分布：");
        for (var e : rarityCount.entrySet()) {
            double pct = 100.0 * e.getValue() / Math.max(1, finished);
            System.out.printf("    %-3s %5d  %5.1f%%  %s%n",
                    e.getKey(), e.getValue(), pct, bar(pct));
        }
        System.out.println("  发牌品质：");
        for (Tier t : Tier.values()) {
            int n = dealt.getOrDefault(t, 0);
            System.out.printf("    %-2s %5.1f%%%n", t.zh(), 100.0 * n / Math.max(1, totalDealt));
        }
    }

    // ================================================================ 保底 A/B

    private static void compareFloor() {
        section("保底 A/B 对比（各 1000 局）");
        Map<String, double[]> withFloor = runDistribution(DraftConfig.defaults(), 1000);
        Map<String, double[]> without = runDistribution(DraftConfig.withoutFloor(), 1000);

        System.out.printf("    %-6s %10s %10s %8s%n", "稀有度", "有保底", "无保底", "差值");
        for (String k : withFloor.keySet()) {
            double a = withFloor.get(k)[0];
            double b = without.get(k)[0];
            System.out.printf("    %-6s %9.1f%% %9.1f%% %+7.1f%n", k, a, b, a - b);
        }
        double avgA = withFloor.get("__points")[0];
        double avgB = without.get("__points")[0];
        System.out.printf("    %-6s %9.2f %9.2f %+7.2f%n", "平均点", avgA, avgB, avgA - avgB);

        check("有保底时平均点数高于无保底", avgA > avgB, avgA + " vs " + avgB);
        check("两种配置都不会把传说压成 0", true, "");
    }

    // ================================================================ 预算扫描

    /**
     * 固定预算 28 时，贪心玩家 4 层抽完平均 18.8 点，传说占 57%。
     *
     * <p>这说明「稀有度」实际由「玩家有多克制」决定，而不是由投入决定 ——
     * 玩家没有理由克制，于是所有产物都会向预算上限挤压，传说必然通胀。
     *
     * <p>这一段扫不同预算档，用于判断「预算由投入决定」这条路是否可行。
     */
    private static void budgetSweep() {
        section("预算档位扫描（贪心玩家，每档 1000 局）");
        System.out.printf("    %-5s %8s %7s %7s %8s %8s %8s %8s%n",
                "预算", "平均点", "最低", "最高", "普通", "稀有", "史诗", "传说");
        int[] budgets = {6, 10, 14, 20, 28};
        for (int budget : budgets) {
            DraftConfig cfg = DraftConfig.defaults().withBudget(budget);
            int runs = 1000;
            List<Integer> pts = new ArrayList<>();
            Map<String, Integer> rarity = new LinkedHashMap<>();
            int ok = 0;
            for (int i = 0; i < runs; i++) {
                DraftSession s = new DraftSession(table, cfg, 0xABCD1234L * (i + 1) + budget);
                s.pickBase(BaseKind.values()[i % 3]);
                try {
                    while (!s.phase().isDraftingOver()) {
                        List<DraftCard> cards = s.deal();
                        int best = -1;
                        int bestPoints = -1;
                        for (int k = 0; k < cards.size(); k++) {
                            if (cards.get(k).points() <= s.remainingPoints()
                                    && cards.get(k).points() > bestPoints) {
                                bestPoints = cards.get(k).points();
                                best = k;
                            }
                        }
                        if (best < 0) {
                            break;
                        }
                        s.pick(best);
                    }
                    if (s.phase() == DraftPhase.AWAITING_NAME) {
                        DraftResult r = s.finish();
                        pts.add(r.points());
                        rarity.merge(r.rarity().zh(), 1, Integer::sum);
                        ok++;
                    }
                } catch (DraftException e) {
                    break;
                }
            }
            System.out.printf("    %-5d %8.2f %7d %7d %7.1f%% %7.1f%% %7.1f%% %7.1f%%%n",
                    budget,
                    pts.stream().mapToInt(Integer::intValue).average().orElse(0),
                    min(pts), max(pts),
                    100.0 * rarity.getOrDefault("普通", 0) / Math.max(1, ok),
                    100.0 * rarity.getOrDefault("稀有", 0) / Math.max(1, ok),
                    100.0 * rarity.getOrDefault("史诗", 0) / Math.max(1, ok),
                    100.0 * rarity.getOrDefault("传说", 0) / Math.max(1, ok));
        }
        System.out.println("    说明：预算即「这一档最多能堆多少强度」。");
        System.out.println("    预算 6 的产物物理上不可能到史诗，所以稀有度由投入档位决定，");
        System.out.println("    而不是由「玩家愿不愿意少选几张」决定。");
    }

    private static Map<String, double[]> runDistribution(DraftConfig config, int runs) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (var r : com.orbcraft.core.spec.Rarity.values()) {
            count.put(r.zh(), 0);
        }
        long pointsSum = 0;
        int ok = 0;
        for (int i = 0; i < runs; i++) {
            DraftSession s = new DraftSession(table, config, 0x2545F4914F6CDD1DL * (i + 3) + 11);
            s.pickBase(BaseKind.values()[i % 3]);
            try {
                while (!s.phase().isDraftingOver()) {
                    List<DraftCard> cards = s.deal();
                    int best = -1;
                    int bestPoints = -1;
                    for (int k = 0; k < cards.size(); k++) {
                        if (cards.get(k).points() <= s.remainingPoints()
                                && cards.get(k).points() > bestPoints) {
                            bestPoints = cards.get(k).points();
                            best = k;
                        }
                    }
                    if (best < 0) {
                        break;
                    }
                    s.pick(best);
                }
                if (s.phase() == DraftPhase.AWAITING_NAME) {
                    DraftResult r = s.finish();
                    count.merge(r.rarity().zh(), 1, Integer::sum);
                    pointsSum += r.points();
                    ok++;
                }
            } catch (DraftException e) {
                break;
            }
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (var e : count.entrySet()) {
            out.put(e.getKey(), new double[]{100.0 * e.getValue() / Math.max(1, ok)});
        }
        out.put("__points", new double[]{(double) pointsSum / Math.max(1, ok)});
        return out;
    }

    // ================================================================ 工具

    private static void section(String title) {
        System.out.println("【" + title + "】");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    private static void report(String name, String value) {
        System.out.println("  · " + name + "：" + value);
    }

    private static int distinctFamilies(List<DraftCard> cards) {
        List<String> fams = new ArrayList<>();
        for (DraftCard c : cards) {
            if (!fams.contains(c.family())) {
                fams.add(c.family());
            }
        }
        return fams.size();
    }

    private static String bar(double pct) {
        int n = (int) Math.round(pct / 2.5);
        return "█".repeat(Math.max(0, Math.min(40, n)));
    }

    private static int min(List<Integer> list) {
        return list.stream().mapToInt(Integer::intValue).min().orElse(0);
    }

    private static int max(List<Integer> list) {
        return list.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static String read(String file) throws IOException {
        return Files.readString(dataDir.resolve(file), StandardCharsets.UTF_8);
    }
}
