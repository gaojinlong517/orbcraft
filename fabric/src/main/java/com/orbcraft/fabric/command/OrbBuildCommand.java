package com.orbcraft.fabric.command;

import java.util.function.Consumer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.orbcraft.fabric.ui.BuildFlow;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 构筑入口的命令树 —— 这一轮玩家唯一的入口（图形界面明确不做）。
 *
 * <h2>为什么用命令 + 可点击文本</h2>
 * <p>点击聊天栏里的 {@code [确认]} 等同于手敲 {@code /orb confirm}。
 * 于是「界面」和「快捷键」共用同一条执行路径，将来接图形界面时，
 * 图形界面只需要发同样的意图，不需要另写一套状态迁移。
 *
 * <h2>接线方式</h2>
 * <p>本类不碰 {@code OrbcraftMod}（那是主线的文件）。主线只需在
 * {@code OrbcraftMod.onInitialize()} 里加一行：{@code OrbBuildCommand.init();}
 *
 * <h2>命令表</h2>
 * <pre>
 * /orb build              开一局（四层确认）
 * /orb status             重画当前一屏
 * /orb card &lt;序号&gt;        选第 n 张（类型层也是它，内部按类型名选）
 * /orb confirm            确认本层并推进
 * /orb clear              取消本层已选
 * /orb reroll             形态层换一批
 * /orb cancel             放弃整局（不扣任何资源）
 * /orb target             看定向卡池与今日额度
 * /orb target &lt;卡id&gt;      用一张定向卡
 * /orb behavior &lt;模式&gt;    定稿后选召唤物行为：attack / guard / chaos
 * /orb material &lt;档位&gt;    定稿后选材料：trial / standard / refined / masterwork
 * /orb altar              定稿后切换祭坛加速
 * /orb preview            预览（只读，不扣配额）
 * /orb submit             确认造球（唯一扣配额的一步）
 * </pre>
 */
public final class OrbBuildCommand {

    private OrbBuildCommand() {
    }

    /** 主线在 {@code OrbcraftMod.onInitialize()} 里调这一行。 */
    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) ->
                dispatcher.register(tree()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("orb")
                .executes(ctx -> with(ctx, BuildFlow::status))
                .then(Commands.literal("build")
                        .executes(ctx -> with(ctx, BuildFlow::start)))
                .then(Commands.literal("status")
                        .executes(ctx -> with(ctx, BuildFlow::status)))
                .then(Commands.literal("card")
                        // 序号上限 9 与 BuildConfig.MAX_DEAL 对齐：发牌最多 9 张
                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 9))
                                .executes(ctx -> with(ctx, player -> BuildFlow.choose(
                                        player, IntegerArgumentType.getInteger(ctx, "index"))))))
                .then(Commands.literal("confirm")
                        .executes(ctx -> with(ctx, BuildFlow::confirm)))
                .then(Commands.literal("clear")
                        .executes(ctx -> with(ctx, BuildFlow::clearLayer)))
                .then(Commands.literal("reroll")
                        .executes(ctx -> with(ctx, BuildFlow::reroll)))
                .then(Commands.literal("cancel")
                        .executes(ctx -> with(ctx, BuildFlow::cancel)))
                .then(Commands.literal("target")
                        .executes(ctx -> with(ctx, BuildFlow::listTargets))
                        .then(Commands.argument("card", StringArgumentType.string())
                                .executes(ctx -> with(ctx, player -> BuildFlow.useTarget(
                                        player, StringArgumentType.getString(ctx, "card"))))))
                .then(Commands.literal("behavior")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(ctx -> with(ctx, player -> BuildFlow.setBehavior(
                                        player, StringArgumentType.getString(ctx, "mode"))))))
                .then(Commands.literal("material")
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .executes(ctx -> with(ctx, player -> BuildFlow.setMaterial(
                                        player, StringArgumentType.getString(ctx, "tier"))))))
                .then(Commands.literal("altar")
                        .executes(ctx -> with(ctx, BuildFlow::toggleAltar)))
                .then(Commands.literal("preview")
                        .executes(ctx -> with(ctx, BuildFlow::preview)))
                .then(Commands.literal("submit")
                        .executes(ctx -> with(ctx, BuildFlow::submit)));
    }

    /**
     * 把「命令源 → 玩家 → 动作」这一段收在一处。
     *
     * <p>非玩家来源（控制台、命令方块）直接回一条中文失败并返回 0，
     * 不抛 {@code CommandSyntaxException}：这条命令没有「语法错误」可言，
     * 缺的是执行者类型，用 0 表达比让 brigadier 打一段英文红字更清楚。
     */
    private static int with(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个命令只能由玩家执行"));
            return 0;
        }
        action.accept(player);
        return 1;
    }
}
