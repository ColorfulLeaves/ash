# ash

客户端侧的自动战斗（killaura）mod，Fabric 1.20.1。

## 构建与运行

- 用 IDEA 以 Gradle 项目打开本目录，等待 sync 完成。
- 运行 Gradle 任务 `build`，产物在 `build/libs/ash-1.0.0.jar`。
- 直接跑 `Minecraft Client` 运行配置也可以（Loom 已生成）。
- 本 mod **不依赖 Fabric API**，只需要 Fabric Loader。

## 使用

- 默认**按一下 R 开启，再按一下关闭**（`toggleMode=true`）。想改回长按生效就设 `toggleMode=false`。
- 改键在 `config/ash-client.properties` 里改 `keyCode`（GLFW key code，R=82）。
- 配置文件在首次启动时自动生成，之后游戏内不会热更新，**改完要重启客户端**。
- `debug=true` 时会在动作栏打印锁定/丢失目标的信息，用来验证目标筛选是否按预期工作。

## 行为设计（为什么要这样写）

| 项 | 做法 |
| --- | --- |
| 频率 | 每 tick 最多一次决策、最多一个攻击包；攻击间隔在 `attackGapMinMs~MaxMs` 间随机，再叠加 `attackGapJitterMs`，**毫秒级随机、不与 tick 对齐** |
| 武器冷却 | `waitForWeaponCooldown=true` 时等攻击条到 98% 才挥，避免"手速快但伤害低"的机器特征 |
| 转向 | `rotateToTarget=true`（默认）时每 tick 限幅（`maxYawStepDeg`/`maxPitchStepDeg`）平滑转到目标上身，叠加缓慢漂移的手抖（±`aimJitterDeg`）；准星进入 `aimToleranceDeg` 容差才挥 |
| **视角锁定：开还是关** | 见下方专节。默认 `true`（最保守）；设成 `false` 后本 mod **完全不动你的相机** |
| **击退后冻结视角** | 仅 `rotateToTarget=true` 时有效。每次挥完视角**完全不动** `aimFreezeMinMs~MaxMs`（默认 150~300ms），让怪物自己飞出准星，之后再重新获取。不做这条视角就会跟着击退轨迹走，等于锁头 |
| 包的先后顺序 | 转向写在 `Minecraft#tick` HEAD，被本 tick 的原版位置包带走；攻击包在**下一 tick** 才发，服务端永远先看到朝向再看到攻击 |
| 距离 | 从**眼睛**到目标 hitbox 最近点计算，上限硬钳死在 3.0（原版生存交互上限） |
| 视线 | 从眼睛到目标中心做 `ClipContext.Block.COLLIDER` 射线，被方块挡住就不打 |
| 目标 | 一 tick 只锁一个；丢失目标后等 `retargetDelayMs` 才重新锁；首次锁定后有 `reactionMin~MaxMs` 的人类反应延迟 |
| 换目标 | 锁定 `targetLockMs` 之后，只有当另一个目标**明显更近**（近 0.75 格以上）才切换，避免来回抖 |
| 过滤 | **只看名字白名单**；另外排除死亡/旁观/无敌/隐身/盔甲架/自己的坐骑/跨世界 |
| 状态 | 打开任何界面、死亡、旁观、正在使用物品（吃东西/举盾）时完全不动作 |
| brand | `BrandMixin` 把 `minecraft:brand` 恢复为 `vanilla`（Fabric Loader 默认改成 `fabric`） |

## 视角：转还是不转（`rotateToTarget`）

一个开关二选一，写在 `config/ash-client.properties`：

| `rotateToTarget` | 行为 | 代价 |
| --- | --- | --- |
| `true`（默认） | 攻击前把准星转到目标身上，攻击包永远有一条已经上报的朝向数据能解释它 | **和 `#goto` / `autoApproach` 抢 yaw**：移动每 tick 把视角掰向路径点，战斗每 tick 掰向怪，同一 tick 里互相覆盖，走位和输出都很难受 |
| `false` | **一次 `setYRot`/`setXRot` 都不写**。没人跟你抢视角，边走边打不再冲突 | 打不到不在视野方向上的东西。背后的怪够不着——这正是 `attackFovDeg` 要保证的边界 |

**为什么 `false` 也不会被原版/Paper 判死**：服务端校验 `ServerboundInteractPacket(ATTACK)` 只查**距离**（vanilla 的兜底是「离得太远」的异常处理），**不查命中射线**。所以头没转过去身位够得着的攻击，原版和 Paper 是放行的。

**替代防护：`attackFovDeg`**（默认 `120`，表示完整锥形角，即前方 ±60°）。只有落在锥内的实体才能成为目标：

- 判据是**你当前真实的朝向**（vanilla 已经在位置包里上报的那条视线向量）到目标上身的夹角，跟转不转视角无关
- 目标绕到你背后 → 夹角超界 → **丢弃目标不再挥**，而不是一拳打向背后
- 设成 `360` = 取消限制，背后的怪也打。按 R 时会先警告你一句

`rotateToTarget=false` 时，`#near` 会额外输出每个实体的偏角，标 `OUT` 的就是被锥形挡掉的：

```
[ash] HIT   "僵尸" len=2  minecraft:zombie  2.1m  Zombie  off= 18deg
[ash] HIT   "僵尸" len=2  minecraft:zombie  2.6m  Zombie  off= 94deg OUT
```

**和距离/频率的关系**：关掉转视角**不影响**任何节奏上的防护——每 tick 至多一包、240~480ms 随机间隔、武器冷却、单目标锁定 `targetLockMs`、眼到 hitbox 3.0 上限、`ClipContext` 墙壁射线全部照常生效。丢弃的只有「朝向解释」这一条。

> 配套建议：逛 RPG 副本时把 `autoApproach=true` 和 `rotateToTarget=false` 一起开。这个组合里 yaw 只有一个主人（移动），战斗完全不碰它，两者不可能打架。

## 聊天栏命令（# 开头）

在原版聊天栏里输入 `#` 开头的命令，**会被 `ClientPacketListener#sendChat` 的 HEAD 拦截并取消**，一个包都不会发到服务端。以 `/` 开头的是原版命令，走 `sendCommand`，完全不受影响。

| 命令 | 作用 |
| --- | --- |
| `#reload` | 重读配置 **并重置一切**（见下） |
| `#goto x y z` | 寻路走到目标点，支持 `~` 和 `~n`（如 `#goto ~10 ~ ~-5`） |
| `#goto` / `#stop` | 停止移动、清空脚本队列 |
| `#debug` | 切换 debug 开关并写回配置文件 |
| `#run D:\script.txt` | 逐行执行脚本文件；空行和 `//` 注释跳过 |
| `#wait 3000` | 脚本内延时（毫秒），配合 `#run` 用 |
| `#where` | 打印当前坐标和维度 |
| `#fish [on\|off]` | 开关自动钓鱼（详见下方专节） |
| `#filters` | 显示当前过滤模式与名单（用来确认配置真的读进来了） |
| `#near [半径]` | **列出周围的活体实体、它们的真实名字、以及是否命中名单**（默认 8 格） |
| `#scan clear` | 清空 `ash-entity-scan.txt` |
| `#help` | 列出命令 |

脚本示例（`D:\script.txt`）：

```
#goto 100 64 200
#wait 4000
#goto 120 64 200
```

脚本按**队列**执行，每 tick 取一行，`#wait` 期间挂起，所以能真正串起一段路线。

**反馈信息是纯客户端的。** `LocalPlayer#displayClientMessage` 的字节码只有一句 `Minecraft#getChatListener().handleSystemMessage(component, actionBar)`——走本地 `ChatListener`，没有任何 `send(Packet)`，服务端收不到 debug 输出。`#debug` 的动作栏提示、`[ash] locked XXX` 全部同理。

## 配置只在启动时读一次

**没有热重载，也没有文件轮询。** 配置在 Fabric 加载 mod 时读一次，之后唯一的触发方式是手动输入 `#reload`。原来那两个键（`configHotReload`、`configPollMs`）已经删掉。

`#reload` 不只是重读文件，而是**全量重置**。中途还在跑的东西都是按旧参数规划的，全部丢掉：

| 被重置的 | 原因 |
| --- | --- |
| 移动目标 + A* 路径 + 按住的移动键 | 旧路径是按旧参数算的 |
| `#run` 脚本队列 | 后续行假设还能移动 |
| 锁定的战斗目标 + 瞄准状态 + 所有计时器 | 旧目标是按旧 reach/冷却锁的 |
| **自动攻击开关**（回到关闭） | 重载后引擎处于确定状态，不会用半套新参数继续挥刀 |
| **自动钓鱼**（回到关闭） | 同上；正在水里的线也一并放弃 |

所以流程是：改配置 → `#reload` → 按 R 重新开启（钓鱼则重新 `#fish`）。

## 自动钓鱼（`#fish`）

聊天栏打 `#fish` 开关（`#fish on` / `#fish off` 也能显式指定）。**开启条件是主手必须是钓竿**——只查主手，不去翻快捷栏，因为真人也只按手上的那一根。

循环是 4 个状态：

```
IDLE ──抛竿──> CASTING ──浮漂实体出现──> WAITING ──咬钩──> REELING ──收杆──> IDLE
                  │                        │
                  └─ 4s 没等到浮漂 → 退避 ─┘   └─ 线自己没了 → 重抛
```

| 环节 | 实现 |
| --- | --- |
| 抛竿 / 收杆 | 走 `MultiPlayerGameMode#useItem(player, MAIN_HAND)`，就是原版右键那条路径，发的是玩家的包 |
| 有没有线在水里 | 直接读 `Player#fishing`（public 字段）。客户端侧由 `FishingHook#setOwner → updateOwnerInfo` 赋值，可靠 |
| **咬钩判定** | 读浮漂的同步数据 `FishingHook#DATA_BITING`。服务端 `catchingFish()` 写它，客户端 `onSyncedDataUpdated()` 把它镜像进私有 `biting` 字段——所以这是服务端权威信号，不是靠浮漂运动猜的。该字段是私有的，用 `mixin/FishingHookAccessor` 的 `@Accessor` 读（已确认 remap 成 `field_23234`） |
| 反应延迟 | 咬钩后等 `fishReactionMinMs~MaxMs`（默认 180~450ms）再收杆 |
| 走神 | `fishSlowReactionChance`（默认 8%）用 `fishSlowReactionMin~MaxMs`（900~2600ms）的**迟钝反应**替代。有这个长尾分布比"永远同一个反应时间"重要得多 |
| 重抛间隔 | `fishRecastMinMs~MaxMs`（默认 800~1800ms） |
| 长歇 | `fishPauseEvery`（默认 25 条）之后停 `fishPauseMinMs~MaxMs`（4~10s）。连拉几百条不中断本身就是特征 |
| 死等 | `fishGiveUpMs`（默认 120s）没咬钩也收竿重抛 |

**会自动停下来的情况**（动作栏会给原因）：

- 主手不再是钓竿（换武器/背包整理/耐久耗尽）
- 移动控制触发了硬停（服务端传送、路径不可达、掉进水里等）
- 切换世界/退出存档、以及 `#reload`

打开任何界面、死亡、旁观只是**暂停**，回到游戏会自动续上，不算停。

抛竿时如果 4 秒内没等到浮漂实体（多半是没对着水，或者服务端拒了这次 use item），会退避 1.5 秒再试，而不是疯狂右键。

**和战斗不冲突**：钓鱼用右键，战斗用左键，两者可以同时开着。但注意 R 键开着时如果怪靠得近，你手里是钓竿，伤害会很低——建议钓鱼时把 R 关掉。

### 配置文件编码

`Properties.load(InputStream)` 是按 **ISO-8859-1** 解码的，任何中文都会变成乱码并且**静默失效**（白名单匹配不上、日志里全是 `å` 之类的方块字）。这已经修掉：

- **读**：先按 UTF-8 严格解码，失败则回退 GBK。所以文件存成 UTF-8 或 Windows ANSI 都能正确读。
- **写**：`Properties.store(Writer)` 会把所有非 ASCII 字符转义成 `\uXXXX`，落盘文件是**纯 ASCII**。任何编辑器、任何区域设置都改不坏它。
- 想确认名单在游戏里的真实内容，用 `#filters`（游戏内渲染，不受控制台编码影响）。

## 移动与寻路（客户端内实现）

**为什么不继续在 bot 里做**：bot 要自己复刻碰撞箱、台阶高度（0.6）、水/岩浆、梯子、冰面、击退和自定义方块的碰撞形状，任何一处和服务器算的不一致就是可检测的特征——你那边被踢的几次基本都出自这一类。放进客户端之后，这些全部由 `LocalPlayer#aiStep → Input#tick → travel` 用真实 BlockState 算出来，服务端收到的就是一个普通玩家的输入流。

**铁律：绝不自己发位置包。** 只改按键状态：

```
Minecraft#tick HEAD  →  设置 KeyMapping.setDown(...)
                     →  (同 tick 稍后) LocalPlayer#aiStep → Input#tick 读 options → travel 算物理
                     →  vanilla 自己发 ServerboundMovePlayer 包
```

注意 `LocalPlayer#input` 实际上是一个 `KeyboardInput`，它的 `tick()` 每 tick 都从 `mc.options` 重新推导 `forwardImpulse/leftImpulse`，**直接写 impulse 字段会被覆盖**，所以只能走 `KeyMapping#setDown`。这也是最贴近真人的做法——和真按 W 走的是同一条代码路径。

| 项 | 说明 |
| --- | --- |
| `autoApproach` | 默认 **false**。开启后，射程内没有目标时会走向最近的目标，进入 `approachStopDistance` 就停下开打 |
| 寻路 | A*（8 向 + 上 1 格 + 下 3 格），对角要求两侧都通（不切角），跳过流体与伤害方块，未加载区块当墙 |
| 预算 | `pathMaxNodes`（默认 2500），搜索在预算内没到达目标就算**不可达** |
| 重规划 | 每 `pathRepathMs` 或目标移动超过 1.5 格；2 秒没进展就跳一下重新规划 |

### 硬性中止（Safety Stops）

下面任一条件触发就**全部停下来**：松开按键、丢弃路径、清空目标，并在 3 秒内拒绝新目标（包括 `autoApproach` 重新下达的），同时清空 `#run` 脚本队列。提示走 actionbar + stderr 日志。

| 条件 | 提示 | 说明 |
| --- | --- | --- |
| 路径不可达 | `goal unreachable, closest reachable point is …` | A* 在预算内没拓展到目标块就停，**不再继续走 partial path**——你想的是"到不了就别走" |
| 目标附近无处站立 | `nowhere to stand near the goal` | `standableNear` 半径 3 内找不到可站立方块 |
| 服务器坐标重置包 | `server reset our position` | 注入 `ClientPacketListener#handleMovePlayer`（intermediary `method_11157`）。`/tp`、插件传送、以及**反作弊判定位移非法后的拉回**都走这个包。收到之后继续按键，等于在跟服务端争论"我到底在哪儿"— 那场争论本身就是移动检测要抓的东西 |
| 陷入液体 | `entered water` / `entered lava` | 游泳/气泡柱物理没建模，走下去只会产出没人能做出的运动轨迹。需**连续 0.5 秒**在液体里才触发，涉过一格小溪不会误伤 |
| 长时间无进展 | `no progress for 8s` | 原来每 2 秒只会跳一下再规划，可以无限循环；现在给了一个总上限 |
| 跌出世界底部 | `below the world floor` | `player.getY() < level.getMinBuildHeight()` |
| 切换世界 / 退出 | （无提示） | `Minecraft#setLevel` / `clearLevel` 时清空目标与路径，避免拿旧路径去查已经不存在的方块 |

开关：`abortOnUnreachable`、`stopOnServerTeleport`、`stopOnFluid`（均默认 **true**）、`abortStuckMs`（默认 8000）。一般不需要动。

> 为什么要有那 3 秒拒绝窗口：中止后如果不设冷却，战斗引擎下一 tick 就会重新下达同一个不可达目标，然后又重新规划同一条路径，形成"每 tick 一次失败 A*"的循环——日志刷屏，行为上还是一个卡住的 bot。手动 `#goto` 不受此限制，会立即解除。
| 转向 | 只控制 yaw，每 tick 限幅 `moveMaxYawStepDeg`；**pitch 不碰**，交给玩家 |
| 按键归属 | 记录"这个键是不是我们按下的"，只释放自己按下的，不会抢走你物理按住的 W |

## 索敌名单：白名单制（唯一的目标策略）

**已经删掉了 `includeHostile` / `includePassive` / `includePlayers`。** 可攻击目标**只**由名字名单决定，没有第二条路径：

- `nameFilterMode` 默认 **`WHITELIST`**
- 名单里的每一项是**大小写不敏感的子串**，同时匹配显示名和实体类型 id，所以 `slime` 和 `minecraft:slime` 都行
- **空的 `WHITELIST` = 什么都不打**（fail-closed）。配置刚生成时就是空的，按 R 会直接提示 `nameFilters is empty - nothing will be attacked`，不会误伤任何东西
- 切换成 `BLACKLIST` 时名单语义反转（用来排除 NPC / 宠物 / 召唤物）；`OFF` 表示不过滤（等于原来的"打所有"）

任何时候想知道当前生效的是什么，聊天栏打 **`#filters`**：会打印模式 + 条目数，并把名单按 60 字符一行分页列出来。

### 名单对不上怎么办：`#near`

白名单"不生效"几乎总是同一个原因——配置文件里的名字和实体上的名字**不是同一串字符**。子串匹配对大小写不敏感，但对字符本身是严格的：多一个全角空格、混进一个零宽字符、`§` 颜色码没剥干净、或者编码坏了，都匹配不上，而且**从配置文件里完全看不出来**。

`#near` 直接读活体实体的名字：

```
[ash] within 8 blocks (3):
[ash] HIT   "僵尸" len=2  minecraft:zombie  4.2m  Zombie
[ash] miss  "骷髅 弓手" len=5  minecraft:skeleton  6.1m  Skeleton
[ash] 1 of 3 match the current filter
```

`len` 是故意打印的：名字看起来对但长度不对，就是有隐藏字符。显示在游戏内，所以不受控制台编码影响。**照抄这里引号里的内容**，别照抄扫描文件。

### 怎么把 RPG 服的怪物收进名单

RPG 服的怪物通常什么类型都有，`Enemy` 接口、实体类、`class=` 全都靠不住——**按显示名字匹配最保险**。

1. `#debug` 打开 debug（`scanManualAttacks` 默认 true）。
2. 进游戏，**潜行 + 空手**，左键去打你想收录的怪物。
3. 每次命中写进 `config/ash-entity-scan.txt`：

   ```
   type=minecraft:zombie | name=Dungeon Slime | class=Zombie | hostile=true | hp=20.0
   | box=0.6x1.95 | custom=true | tags=[dungeon, elite]
   ```

   文件末尾汇总成一行"所有出现过的名字"，直接复制。
4. 粘进 `nameFilters`（逗号分隔），然后 `#reload`。
5. 回游戏用 `#near` 复核一遍——**这一步别省**，直接看 `HIT` / `miss`。

引擎自己打的不会进记录（用 `isEngineSwinging` 标志排除），所以开着自动攻击采样也不会污染数据。

> 注意 `attackInvisible` 默认 `false`：隐身实体即使名字命中也会被跳过。如果你的目标怪带隐身，单独开这个。

## 已知取舍

- 没有配置界面，也不打算加（个人使用，参数定下来就固定了）。
- 快捷键通过 GLFW 键回调边沿触发，**不注册 KeyMapping、不出现在控制菜单里**（避免截图取证）。
- 目标判定只看名字名单，不看实体类别：自定义 RPG 怪物不需要实现 `Enemy` 接口，也不需要额外的"包含被动生物"开关。
- 装载方式只解决"名字/brand"层面的可见性：Fabric 不向服务端上报 mod 列表，服务端能看到的只有 brand 与行为，**行为节奏仍然是唯一的判定面**。

## License

CC0-1.0
