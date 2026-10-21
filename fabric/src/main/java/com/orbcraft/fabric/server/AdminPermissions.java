package com.orbcraft.fabric.server;

import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionSet;

/**
 * 管理类操作的权限判定 —— <b>用原版权限等级，不用玩家名白名单</b>。
 *
 * <p>为什么不能是白名单：白名单在「服务器开了正版验证但号被盗」时形同虚设，
 * 在「玩家改名」时静默失效，而且还要求每个调用点各维护一份名单。
 * 原版等级由服务器自己维护（{@code ops.json}、服务器控制台、RCON），
 * 我们只需要说清「这个操作要多高的等级」。
 *
 * <p><b>1.21.11 的坑</b>：{@code CommandSourceStack.hasPermission(int)} 已经不存在了，
 * 权限集合被重做成 {@code PermissionSet} + {@code PermissionCheck}
 * （{@code net.minecraft.server.permissions}），等级用
 * {@code PermissionLevel} 枚举表达。继续照抄 1.20 教程的 {@code hasPermission(2)}
 * 会直接编译不过。
 *
 * <p>本类公开出来，是给 S2 的命令层复用的 —— 管理类命令的判定只该有一份，
 * 否则会出现「发球命令查了权限、改配额命令忘了查」这种只能靠审计发现的问题。
 */
public final class AdminPermissions {

    /**
     * 管理操作要求的最低等级：{@code GAMEMASTERS}（等级 2，与 {@code /give}、{@code /gamemode} 同级）。
     *
     * <p>不取 {@code MODERATORS}（等级 1，只有 {@code /kick} 之类）：能凭空发球、能改配额，
     * 等于能绕过整条「定稿才扣额度」的经济设计，应该和创造模式同权。
     */
    public static final PermissionCheck REQUIRED = Commands.LEVEL_GAMEMASTERS;

    /** 权限不足时给玩家的原文。命令层直接用它，避免各处写出不同的措辞。 */
    public static final String DENY_MESSAGE = "权限不足：这个操作需要管理员权限（等级 2）";

    private AdminPermissions() {
    }

    /** 给 Brigadier 的 {@code .requires(...)} 用：命令在补全阶段就直接对普通玩家不可见。 */
    public static Predicate<CommandSourceStack> requirement() {
        return Commands.hasPermission(REQUIRED);
    }

    /** 命令体内部的二次判定（防止有人把子节点挂到没有 requires 的父节点上）。 */
    public static boolean isAdmin(CommandSourceStack source) {
        PermissionSet permissions = source.permissions();
        return permissions != null && REQUIRED.check(permissions);
    }
}
