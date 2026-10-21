# orbcraft · 平台层（Fabric）

Minecraft **1.21.11** / Java **21** 的 Fabric 模组。算法与数据在 `../core`，这一层只负责
把它接到游戏里。

> **S0 = 工程骨架**。这里目前只有一个「初始化时打一行日志」的空模组。
> 玩法代码属于 S1–S5，各占一个子包，见文末的分工表。

## 版本锁

**唯一版本来源是根目录 `gradle.properties`**。不要在别处写死版本号 —— 六路并行时
最容易出的问题就是四个人四个 loader 版本，合并后 `runServer` 起不来。

| 组件 | 版本 | 来源 |
|---|---|---|
| Minecraft | `1.21.11` | — |
| Fabric Loader | `0.17.3` | Fabric API 1.21.11 分支实测版本 |
| 映射 | Mojang official（`loom.officialMojangMappings()`） | 同上；Yarn 不用 |
| Fabric API | `0.141.6+1.21.11` | `api.modrinth.com/v2/project/fabric-api` |
| Loom | `1.13.3` | `maven.fabricmc.net/net/fabricmc/fabric-loom` |
| Gradle | `9.8.1` | `gradle/wrapper/gradle-wrapper.properties` |
| Java | `21` | toolchain |

升级 Minecraft 版本时按这个顺序：先查 Fabric API 有没有对应分支 → 抄它 `gradle.properties`
里的 `loader_version` 与 `build.gradle` 里的 Loom 版本 → 再改本项目的三行 →
`./gradlew build`。**不要只改 `minecraft_version`**，Loom 与 loader 必须同步换。

### 为什么 Loom 停在 1.13.3

不是没升，是升不上去。1.21.11 对 Loom 版本的容忍区间很窄：

| Loom | 结果 |
|---|---|
| `1.18` / `1.19` | ❌ module metadata 要求 `org.gradle.jvm.version = 25`，即构建 JVM 必须是 25，而 MC 1.21.11 与团队环境都是 21 |
| `1.17` | ❌ 取消 `mappings` configuration（`Could not find method mappings()`），且把 1.21.11 判成 non-obfuscated，Fabric API 的 access widener 报 `expected official, found intermediary` |
| `1.16.2` | ❌ 同样没有 `mappings` configuration |
| **`1.13.3`** | ✅ jvm 21、pluginApi 8.14、支持 mojmap，Fabric API 1.21.11 自己就用这个版本 |

要升 Loom 得先升构建 JVM（`org.gradle.java.home` 指到 JDK 25，toolchain 仍用 21）。
那是环境改动，不是配置改动，别在派单时顺手做。

## 跑起来

```bash
./gradlew build        # 编译 + remapJar，产物在 build/libs/
./gradlew runServer    # 专用服务端 → run/server/
./gradlew runClient    # 客户端    → run/client/
```

首次运行会下载 Minecraft 与依赖（约 400 MB）并 remap，**第一次约 15 分钟**，
之后走缓存（改代码后重建约 5–30 秒）。

两个 run 配置的目录是分开的（`run/server` / `run/client`），否则 client 的
`options.txt` 与 server 的 `server.properties` 挤在同一个 `run/` 里，
排查「配置没生效」时很难看出是哪一边写的。

**服务端首次启动会因 EULA 退出**，这是 Mojang 的要求，构建脚本故意不代签。
手动建 `run/server/eula.txt`：

```
eula=true
```

验证骨架是否真的通了（以下为实测输出）：

```
[FabricLoader] Loading 43 mods:            ← 其余 42 个是 fabric-api 的各模块
	- orbcraft 0.1.0-SNAPSHOT
[orbcraft] 初始化完成 · 环境 SERVER · Java 21
[Server thread] Done (6.168s)! For help, type "help"
```

客户端则会在启动时打印 `[orbcraft] 客户端初始化完成`。

## 源码布局

```
build.gradle                 根构建脚本（S0 独占，其他人只读）
settings.gradle              pluginManagement 仓库（Fabric maven）
gradle.properties            版本锁（唯一版本来源）
gradle/wrapper/              Gradle 9.8.1 wrapper
fabric/src/main/java/com/orbcraft/fabric/
    OrbcraftMod.java         main 入口（双端）
    client/OrbcraftClient.java   client 入口（仅客户端）
fabric/src/main/resources/
    fabric.mod.json          模组元数据（version 由构建期展开）
```

`core/src/main/java` 与 `core/src/main/resources` 被**直接并入 main sourceSet**
（见 `build.gradle`）。core 零 MC 依赖，做成独立 Gradle 子项目只多一层 project 依赖，
收益为零。等 core 要被别的加载器复用时再拆。

## 包的所有权（五路并行时按这个切）

| 子包 | 主人 | 内容 |
|---|---|---|
| `fabric/`（根 Gradle 文件、`fabric.mod.json`） | `skeleton` | 版本锁、entrypoint、run 配置 |
| `.../item/`、`.../entity/orb/` | `orb-item` | 球物品、投掷物、命中判定、耐久条 |
| `.../command/`、`.../ui/` | `build-ui` | 构筑命令树、可点击文本、快照渲染 |
| `.../spawn/`、`.../mapping/` | `spawner` | Spec → 实体/属性映射 |
| `.../store/` | `persistence` | 配额账本、在途票据、产物存档 |
| `.../server/` | `server-rules` | 事件优先级、按人配额、权限 |

**`fabric.mod.json` 与 `OrbcraftMod.java` 归 `skeleton` 独占。** 这两个文件是单点，
六路同时改必冲突。要加 entrypoint，提给主线，不自己动。

## 已知取舍

- **不用 `splitEnvironmentSourceSets()`**。它要多一个 `client` sourceSet 和一整套 run 配置，
  收益只有「编译期拦住客户端类混进服务端」。用 `client` entrypoint 划分已经拿到大半，
  等真的出现误用再拆。
- **不装 Mixin**。S0 不需要，少一个失败点。真要注入时再加 `orbcraft.mixins.json`,
  记得在 `fabric.mod.json` 里登记 `mixins` 字段 —— 忘了这一条会静默不生效。
- **wrapper 的 distributionUrl 指向腾讯镜像**。官方 `services.gradle.org` 在部分网络下
  握手失败。换回官方只改 `gradle/wrapper/gradle-wrapper.properties` 一行。
