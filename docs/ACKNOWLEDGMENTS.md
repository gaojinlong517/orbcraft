# 第三方开源致谢与许可证声明（ACKNOWLEDGMENTS）

> 本文件是 orbcraft 对所有引入的外部开源组件的**唯一署名与合规记录**。
> 增删依赖、升级版本、或有人想「把某个库的代码抄进来」之前，先读本文相应条目。
> 配套选型分析见 `docs/开源复用与依赖选型.md`。

---

## 〇、怎么读这份文件

| 类别 | 是否进入构建 | 是否复制其代码 | 合规要点 |
|---|---|---|---|
| 一、随模组分发的依赖 | 是（modImplementation / include） | 否，仅调用 API | MIT 要求保留版权与许可声明：JIJ 打包的 jar 内自带 LICENSE，本文件同时列明版权行 |
| 二、可选依赖 | 编译期（modCompileOnly） | 否，仅调用 API | 不写入 `fabric.mod.json` 的 `depends`，缺席时功能降级而非崩溃 |
| 三、设计参考项目 | 否 | **否（一行都没抄）** | 仅借鉴架构思想与数据结构设计；版权法不保护思想，但为尊重与可追溯，仍在此署名 |
| 四、运行时推荐配套 | 否 | 否 | 服主/玩家自行安装，与 orbcraft 无编译期耦合 |

**违反本文任一红线的后果不是道德问题，是法律问题**：MIT 丢署名会违约；
GPL/ARR/LGPL 抄代码会直接改变整个项目的许可证归属或构成侵权。

---

## 一、随模组分发的依赖（Jar-in-Jar / 编译必需）

### 1. owo-lib —— 声明式 UI / 配置 / 网络库

- **作者 / 来源**：[wisp-forest](https://github.com/wisp-forest)（主要维护者 gliscowo / Lily Mae）
- **仓库**：<https://github.com/wisp-forest/owo-lib>
- **许可证**：MIT — Copyright (c) 2021-2026 wisp-forest
- **引入版本**：`io.wispforest:owo-lib:0.13.0+1.21.11`（wispforest maven）
- **分发方式**：`modImplementation` + include 官方 **owo-sentinel**（玩家未装 owo 时提示并引导自动下载，而非把 2MB 本体塞进 orbcraft.jar）
- **用途**：抽卡确认界面（5 张牌 + 品质色 + 点数 + 灰化态）后续迁移到 owo-ui 声明式布局；owo-config 提供服务端-客户端同步的配置界面；网络层序列化备用。
- **借鉴的核心实现思路**：
  - **声明式 UI**：界面 = 布局树声明，而不是几百行绘制代码。改数值调样式是改几行声明。orbcraft 后续的 BuildScreens/BuildScreenGui 迁移方向。
  - **sentinel 分发模式**：库作者官方推荐的「轻哨兵 + 自动下载」，比直接 JIJ 大库更省玩家带宽，也保留用户选择权——值得任何可选依赖借鉴。

### 2. PAL（PlayerAnimationLibrary）—— 玩家模型动画库

- **作者 / 来源**：[ZigyTheBird](https://github.com/ZigyTheBird) 与 dima_dencep（Emotecraft 团队）；前身 PlayerAnimator 由 KosmX 创建
- **仓库**：<https://github.com/PlayerAnimationLibrary/PlayerAnimationLibrary>
- **文档**：<https://docs.zigythebird.com/pal/>
- **许可证**：MIT — Copyright (c) 2022 KosmX（PlayerAnimator 原始版权）；Copyright (c) 2025-2026 ZigyTheBird（PAL）
- **引入版本**：`com.zigythebird.playeranim:PlayerAnimationLibFabric:1.1.11+mc.1.21.11`（redlance maven）
- **分发方式**：`modImplementation` + include（客户端动画是硬需求，玩家不应手动装第二个 mod）
- **用途**：施法（OrbCasterItem）/ 投掷孵化球时的**玩家侧动画**——挥臂、蓄力、释放姿态；Blockbench 导出 JSON 动画直接可用，近完整 MoLang 支持。
- **借鉴的核心实现思路**：
  - **动画优先级栈**：多个 mod 同时操纵玩家模型时按优先级仲裁而不是互相覆盖——orbcraft 的施法动画与鞘翅/睡觉等原版状态共存全靠它。
  - **官方后继关系**：PlayerAnimator 已停更，PAL 是作者指定继任者且兼容旧动画格式。选型时认准「上游钦定后继」能避免接手弃坑库。
  - 与 BetterModel 分工明确：**PAL 管玩家模型，BetterModel 管实体外观**，两套系统不重叠。

### 3. fabric-permissions-api —— 命令权限检查 API

- **作者 / 来源**：[lucko](https://github.com/lucko)（LuckPerms 作者）
- **仓库**：<https://github.com/lucko/fabric-permissions-api>
- **许可证**：MIT — Copyright (c) 2021 lucko
- **引入版本**：`me.lucko:fabric-permissions-api:0.6.1`（Maven Central；官方版本矩阵 1.21.11 → 0.6.1）
- **分发方式**：`modImplementation` + include（仅约 12KB）
- **用途**：已**实际接入代码**——`AdminPermissions.requirement()` / `isAdmin()` 现在经由此库判定（权限节点 `orbcraft.admin`，回退原版等级 2）。装了 LuckPerms 的服务器可以授权「只能发球、不能改配额」的半管理员角色；没装的服务器行为与从前完全一致。
- **借鉴的核心实现思路**：
  - **事件桥接而非硬依赖**：它自己不实现权限存储，只定义 `PermissionCheckEvent` 契约，由 LuckPerms 等提供方注册监听——「接口与实现分离」在生态互操作上的教科书案例。
  - **回退等级**：`Permissions.check(source, node, fallbackLevel)` 让「无权限插件」场景零成本退化到原版 op 等级，可选依赖的正确姿势。

---

## 二、可选依赖（编译期可见，缺席时降级）

### 4. BetterModel —— 服务端驱动的自定义实体外观

- **作者 / 来源**：[toxicity188](https://github.com/toxicity188)
- **仓库**：<https://github.com/toxicity188/BetterModel>
- **许可证**：MIT — Copyright (c) 2023-2026 toxicity188
- **引入版本**：2.2.0（MC 1.21.11 适配版，经 Modrinth Maven file id 引入；Maven Central 上的 3.x 要求 Java 25，本模组锁定 Java 21 故不采用）
- **分发方式**：`modCompileOnly`，**不 include、不写 depends**——core 层的 `ModelBackends` 在它缺席时自动退化到兜底后端（原版实体 + 粒子 + 名称颜色），玩家照样进服
- **用途**：召唤物 / 孵化球的自定义外观全链路：`.bbmodel`（纯 JSON，可由 AI 产出）→ 服务端加载 → 自动生成资源包下发 → 客户端零安装 → item_display 组渲染；含动画、Molang、IK、自定义碰撞箱。
- **借鉴的核心实现思路**（orbcraft 的 `core/render` 契约直接受其启发，另有选型文档 §4 的三条硬约束）：
  - **外观是附加层**：模型缺失不应让玩家召不出怪——对应我们 `ModelSpawnResult` 三态（OK/DEGRADED/FAILED）与「spawn 绝不抛异常」。
  - **资源包变更是全服级事件**：运行时新增模型 = 全体客户端同时卡 1~2 秒——对应我们 `ModelCatalog` 硬闸门（清单外 modelId 直接拒绝）。
  - **`ModelBackend` 隔离**：业务代码禁止 `import kr.toxicity...`，换后端的成本是写一个类。

### 5. Flan —— 领地保护（兼容层）

- **作者 / 来源**：[Flemmli97](https://github.com/Flemmli97)
- **仓库**：<https://github.com/Flemmli97/Flan>
- **许可证**：**ARR（All-Rights-Reserved，仓库与 Modrinth 均未授予再分发/改编权）**
- **引入版本**：API artifact `io.github.flemmli97:flan:1.21.11-1.12.7-fabric:api`（blazing-coop maven）
- **分发方式**：`modCompileOnly` + 运行时探测（`FabricLoader.isModLoaded("flan")` 才注册桥接，不写 depends）
- **用途**：服主装了 Flan 时，把 orbcraft 的新手村 / 前哨站 / 建筑领地注册为 admin claim，避免两套圈地系统打架——**仅此一用**，不深度集成。
- **合规红线**：ARR 意味着**保留所有权利**。我们只调用其公开 API（`ClaimHandler#canInteract` 等），**绝不把它的任何源码搬进本仓库**，也不假设其将来不会变更许可。

---

## 三、设计参考项目（未使用任何代码，仅借鉴架构）

> 以下项目**没有**出现在 build.gradle 中。列出它们是因为 orbcraft 的多处架构决策
> 直接受其设计启发，署名既是对作者的尊重，也方便后来者按图索骥。
> 本组所有「借鉴」都停留在思想与数据结构层面——**不存在任何代码复制**。

### 6. Spell Engine（GPL-3.0，特别说明）

- **作者**：ZsoltMolnarrr ｜ <https://github.com/ZsoltMolnarrr/SpellEngine>
- **借鉴什么**：法术的 JSON schema 划分（active/passive/modifier 三类、targeting 与 delivery 与 impact 三层解耦）、**召唤物行为系统**（SummonedEntity 的阶段化生命周期：spawn→active→despawn、owner 感知的索敌与跟随、编队生成与出生特效）。
- **为什么不引入**：其版本停在 MC 1.21–1.21.1 与本项目 1.21.11 不兼容；且许可证为 **GPL-3.0（强 copyleft）**——若复制其任何源码，orbcraft 整体将被要求以 GPL 开源。orbcraft 的 core 自研实现（spec / hatch / summon 各包）是**独立编写**的，仅在设计层面参考其分层思想。版权法不保护架构思想，但我们仍在此明确署名并声明零代码复用，以杜绝争议。

### 7. Apotheosis 的 Affix 数据模型（MIT）

- **作者**：Shadows-of-Fire ｜ <https://github.com/Shadows-of-Fire/Apotheosis>
- **借鉴什么**：词条（词缀）的分层结构、属性 modifier 的注册方式、词条池按物品类型过滤。orbcraft 的词条表结构（core/spec）参考了这套分层。**抄结构，不抄代码**（它是 NeoForge，Fabric 侧 API 完全不同，物理上也抄不了）。

### 8. Cobblemon（MPL-2.0）

- **作者**：Cobblemon team ｜ <https://github.com/cobblemon>
- **借鉴什么**：「球 → 投掷 → 命中 → 捕获 → 召唤」的完整状态机、球物品携带实体数据的编码方式。orbcraft 的孵化球流程与之同构。MPL 为文件级 copyleft，未复制任何文件。

### 9. StructureTutorialMod（MIT）

- **作者**：Commoble（及后续贡献者）｜ <https://github.com/Commoble/StructureTutorialmod>
- **借鉴什么**：Jigsaw 结构的四个由浅入深教学示例。orbcraft 的模块化建筑拼装（后续 S4 任务）将对照其 Fabric 分支实现自研。

---

## 四、运行时推荐配套（非依赖，未使用其代码）

| 项目 | 作者 | 仓库 | 许可证 | 推荐理由 |
|---|---|---|---|---|
| Better Combat | ZsoltMolnarrr | <https://github.com/ZsoltMolnarrr/BetterCombat> | **ARR** | 3.0.1+1.21.11 与 orbcraft 共存良好（依赖 PAL，我们已 include）；提升近战手感。**ARR 限制其源码不可复制**，故仅作服主自选安装，不作编译依赖 |
| GeckoLib | bernie-g / Tslat | <https://github.com/bernie-g/geckolib> | MIT | 5.4.5 支持 1.21.11。实体外观在 orbcraft 已由 BetterModel 方案承接（服务端驱动、客户端零安装），故不引入以免双动画引擎并存；装了它的整合包与 orbcraft 无冲突 |
| LuckPerms | lucko | <https://github.com/LuckPerms/LuckPerms> | GPL-3.0（作为独立服务器插件运行，与 orbcraft 无代码耦合） | 与我们接入的 fabric-permissions-api 天然对接，服主可用 `orbcraft.admin` 节点授权 |

---

## 五、许可证合规备忘（工程铁律）

1. **MIT 系（owo-lib / PAL / fabric-permissions-api / BetterModel）**：以 JIJ 打包时依赖 jar 内自带 LICENSE，随 orbcraft.jar 一同分发即满足署名要求；本文件第二节至第一节的版权行是双保险。**任何人想从这些库拷代码进仓库：允许，但必须在该文件头部保留其版权与许可声明，并在提交说明中注明出处。**
2. **ARR 系（Flan / Better Combat）**：只能作为依赖调用 API，**任何源码复制均构成侵权**，无例外。
3. **GPL/LGPL/MPL 系（Spell Engine / Cobblemon / Polymer / Charta 等）**：抄任何一行代码都可能改变 orbcraft 的许可证归属（GPL）或触发文件级开源义务（MPL/LGPL）。需要它们的能力时：做依赖、走 API、或者干净-room 自研，**不要打开源码照着敲**。
4. **无 LICENSE 文件的项目**：法律上默认 ARR，按第 2 条对待。
5. 每次增删依赖或升版本，同步更新本文件与 `gradle.properties` 的注释——两者互为索引。
