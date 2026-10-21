#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""契约冻结检查 —— 只允许加方法，不允许删方法或改签名。

用法:
    python tools/contract-freeze.py            # 检查，有差异退出 1
    python tools/contract-freeze.py --write    # 主线评审后重新生成锁文件

为什么需要它：fabric 这一层的并行度最高（五路同时改），而 `core` 已有 1124 条词条
托底、fabric 什么都没有。契约一旦被某一路顺手改个参数，另外四路的编译会同时断，
但断在别人的分支上 —— 最难定位的一类故障。

只比方法名 + 参数表，不比值类型里的包名简写：`ItemStack` 与
`net.minecraft.world.item.ItemStack` 是同一个东西，比全名只会产生噪声。

退出码:
    0 与锁文件一致
    1 有删除 / 改签名 / 新增未登记，或方法数超限
    2 找不到契约目录（路径错了，不要当成通过）
"""

import re
import sys
from pathlib import Path

MAX_METHODS = 8
CONTRACT_DIR = Path("fabric/src/main/java/com/orbcraft/fabric/contract")
LOCK = Path("tools/contracts.lock")

# 这些文件不是契约本身：Services 是主线所有的取用点，package-info 只有注释
SKIP_FILES = {"Services.java", "package-info.java"}

# 方法声明：允许 default / static / abstract 前缀，参数表在后
DECL = re.compile(
    r"^\s*(?:default|static|abstract|public|private|protected|\s)*"
    r"(?:<[^>]+>\s*)?"
    r"([\w.$]+(?:<[^;{]*?>)?(?:\[\])?)"   # 返回类型
    r"\s+(\w+)\s*\(([^)]*)\)\s*[;{]"      # 名字 + 参数
)
KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "throw"}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def normalize_params(raw):
    raw = " ".join(raw.split())
    if not raw:
        return ""
    parts = []
    for p in raw.split(","):
        bits = p.strip().split()
        # 去掉 final 与注解，只留类型与名字
        bits = [b for b in bits if b != "final" and not b.startswith("@")]
        if not bits:
            continue
        # 只保留类型，参数名对调用方无意义，改名不该触发冻结告警
        parts.append(bits[0])
    return ", ".join(parts)


def methods_of(path):
    """返回 [(方法签名, 是否静态, 缩进)]，按出现顺序。

    按缩进 + 声明形态判断，不用作用域栈：契约文件里的成员声明一律缩进 4（接口方法）
    或 8（嵌套接口 / record 的静态工厂）。作用域栈在 `{` 出现在字符串或泛型里时会算错，
    而这里要防的是「有人顺手删了个方法」，不是「有人恶意混淆」，不值得为它写词法器。
    """
    found = []
    for raw in strip_comments(path.read_text(encoding="utf-8")).splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped:
            continue
        indent = len(line) - len(line.lstrip())
        if indent < 4:
            continue
        # 类型声明本身（record / class / enum / interface）不是方法
        if re.match(r"^(public\s+|protected\s+|private\s+)?(record|class|enum|interface)\b", stripped):
            continue
        m = DECL.match(line)
        if not m:
            continue
        name = m.group(2)
        if name in KEYWORDS:
            continue
        sig = "%s(%s)" % (name, normalize_params(m.group(3)))
        found.append((sig, stripped.startswith("static"), indent))
    return found


def collect():
    if not CONTRACT_DIR.is_dir():
        print("找不到契约目录：%s（在仓库根目录运行）" % CONTRACT_DIR)
        sys.exit(2)
    out = {}
    for f in sorted(CONTRACT_DIR.glob("*.java")):
        if f.name in SKIP_FILES:
            continue
        ms = methods_of(f)
        if ms:
            out[f.stem] = ms
    return out


def render(surface):
    lines = []
    for name in sorted(surface):
        for sig, _static, _indent in sorted(surface[name]):
            lines.append("%s#%s" % (name, sig))
    return lines


def main():
    write = "--write" in sys.argv
    surface = collect()

    print("契约面（只统计接口方法，缩进 8 的静态工厂是记录类型自带的辅助，不计入上限）：")
    for name in sorted(surface):
        api = [x for x in surface[name] if x[2] == 4]
        helpers = [x for x in surface[name] if x[2] != 4]
        print("  %-18s %d 个接口方法%s" % (
            name, len(api),
            ("（另 %d 个静态工厂）" % len(helpers)) if helpers else ""))
        for sig, _s, _i in surface[name]:
            print("      %s" % sig)

    current = [l.strip() for l in render(surface)]
    over = [n for n in surface if len([x for x in surface[n] if x[2] == 4]) > MAX_METHODS]

    if write:
        # 显式 LF：仓库 .gitattributes 规定 eol=lf，Windows 下 write_text 默认会写 CRLF，
        # 换台机器再跑就会因为行尾不同而误报「契约有变」。
        with open(LOCK, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(current) + "\n")
        print("\n已写入 %s（%d 条）" % (LOCK, len(current)))
        if over:
            print("警告：以下接口方法数超过 %d：%s" % (MAX_METHODS, ", ".join(over)))
        return 0

    if not LOCK.is_file():
        print("\n缺少锁文件 %s，先跑 --write 并提交" % LOCK)
        return 1
    locked = [l.strip() for l in LOCK.read_text(encoding="utf-8").splitlines() if l.strip()]

    added = [l for l in current if l not in locked]
    removed = [l for l in locked if l not in current]

    if not added and not removed and not over:
        print("\n契约未变，检查通过")
        return 0

    if removed:
        print("\n✗ 删除或改签名 %d 条（必须先经主线批准）：" % len(removed))
        for l in removed:
            print("    - %s" % l)
    if added:
        print("\n! 新增 %d 条（评审后跑 --write 更新锁文件）：" % len(added))
        for l in added:
            print("    + %s" % l)
    if over:
        print("\n✗ 方法数超过 %d：%s —— 拆接口，不要继续加" % (MAX_METHODS, ", ".join(over)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
