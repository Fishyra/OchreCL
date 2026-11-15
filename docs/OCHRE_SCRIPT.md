# Øchre Script 开发版

入口：26.x 游戏运行时，主页 → 操作游戏 → 脚本。旧的文本草稿仍可打开；`.ochre` 是新的脚本后缀。导入、选择、新建和保存均不会执行脚本。脚本只在玩家检查源码、选择本次权限并点击“运行脚本”后执行。

## 语言

英文标识符、关键字与单位，大括号代码块，分号结束语句；字符串与注释可包含任意语言。支持 `int`、`float`、`bool`、`string`、`Player`、`Vec3`、`List`，严格类型赋值；声明但未赋值的变量不可读取。值 `none` 表示未找到目标或空物品槽。内置函数：`print`、`sleep`、`vec3`。支持 function/return、自定义函数与托管脚本库 import；task.* 提供实例隔离检查点。异步并发、任意文件/网络/系统 API 与外部语言执行不在本版中。

```javascript
int a = 3;
for (int i = 0; i < a; i++) {
    print(i);
    sleep(200ms);
}
if (player.health < 10) {
    print("Low health");
}
while (script.running) {
    print(player.position);
    sleep(1s);
}
```

动作默认等待完成，再执行下一条。时间使用 `s` / `ms`，距离使用 `blocks`，角度使用 `deg`（也接受无单位角度数值）；不同维度不能混用。`sleep` 必须有时间单位。`//` 与 `#` 开始行注释；字符串只使用双引号。

## 读取与设置

```javascript
print(game.language);
print(world.day_time);
print(world.game_time);
print(player.position.x);
print(player.rotation);

List slots = player.inventory.slots;
for (item in slots) {
    if (item != none) {
        print(item.slot, item.id, item.count);
    }
}
List nearby = world.nearby_players(radius=16);
print(nearby.length);

settings.audio.master = 0.6;
settings.video.render_distance = 12;
```

可写：`settings.audio.master/music/weather`，`settings.video.render_distance/max_fps/gamma/fov/fullscreen`。其中 fov 是 30..110 度，gamma 是 0..1。设置会通过游戏 API 保存；停止脚本不会撤销已保存设置。世界时间、血量、背包槽等只读，不提供生成物品或服务器操作员权限绕过。day_time 对应当前维度的默认世界时钟；具体能力依赖实际 26.x 公共 API。

附近玩家仅来自客户端当前实际加载的玩家列表，排除自己，按距离排序，最多返回 16 人，半径不超过 128 格；`world.find_player("BetaF1sh")` 返回玩家记录或 `none`。物品槽返回槽位、真实注册 ID 和数量，不编造 NBT、装备或服务器端不可见信息。

## 控制

```javascript
player.walk_forward(5s);
player.run_forward(3blocks, timeout=10s);
player.look(yaw=90deg, pitch=15deg, duration=500ms);

Player target = world.find_player("BetaF1sh");
if (target != none) {
    player.look_at(target, point=body.eyes, duration=500ms);
}
player.look_at(vec3(100, 65, 200));
player.hit(count=3, interval=3s);
```

移动使用普通前进与疾跑按键，不传送；距离是起点到当前点的水平距离，阻挡或超时明确失败。单次时间不超过 60 秒，距离不超过 128 格，默认距离移动超时 30 秒。一个游戏实例最多执行一个脚本；桥接同样拒绝并发输入所有者。菜单打开、暂停、离开世界时拒绝控制。

视角在玩家线程按有限帧数插值，目标通过 UUID 重新确认。支持 eyes / center / feet；**right_arm / left_arm 仅保留语法枚举，实际精确部位定位暂不可用，会报 `target.point.unsupported`，不以身体中心代替**。`player.walk_to(...)` 保留入口，寻路尚未接入，明确报能力不可用，不暗中使用传送。

攻击针对当前准星下的实体，检查旁观模式、真实交互距离与攻击冷却，走普通客户端攻击 API，最终结果仍由服务器规则决定；次数是尝试次数，不保证造成伤害。`interval` 表示尝试间隔，`duration` 表示总调度时长，两者不能同时指定。最多 100 次，最短间隔 100ms，总计划最长 120 秒。

## 权限与停止

在“设置 → 脚本”或脚本工作区中配置“自动授予全部所需权限”。默认关闭，点击运行后显示本次脚本需要的权限，在右侧勾选后运行；取消不执行。开启后直接授予解析出的启动器能力。完整解析后预先核对全部引用（包括未执行分支），执行具体操作时再次检查权限。自动授权不会授予服务器 OP、作弊或飞行能力。桥接安装同意与脚本授权分别处理。组件令牌只用于本机、当前实例通信，不对脚本暴露。

“设置 → 脚本”也提供语句失败策略：停止（默认）、跳过、一直重试、重试 `script_repeat_count` 次。次数范围 0..100，表示首次失败后的额外尝试；耗尽后停止。每次重试间隔 250ms，持续重试仍受整体 10 分钟和指令预算限制，随时可停止。语法错误、权限拒绝、取消、预算耗尽不会被跳过或重试。设置保存后对下一次运行生效；`print(script_repeat_count);` 可读取当前运行的快照，不能覆盖该只读值。语句重试可能重复已产生的效果，例如已经走过部分距离或已经提交过命令。

停止按钮、关闭工作区、执行失败都会结束脚本并释放临时输入。游戏端另有 750ms 输入租约和 100ms 检查周期，启动器意外退出或连接中断后也会释放所占按键。已经由玩家按住的前进/疾跑键会拒绝接管；切换式疾跑与普通按住式均按幂等状态处理。游戏线程本身卡住时，释放会在游戏线程恢复后执行；不声称能在被冻结的线程上即时执行。

限制：源码 64 KiB 字符、20,000 token、80 层解析嵌套、每次运行 200,000 指令与 10 分钟。每个宿主最多约 50 个桥接请求/秒。输出最多 1000 行，界面保留最近 64 KiB。未捕获的执行错误附带原始行列；脚本不能访问 Java 反射、文件、进程或任意网络。

## 编辑器

括号/引号配对、选择包裹、重复闭合跳过、成对退格、回车继承缩进、大括号内换行；Tab / Shift+Tab 缩进；⌘/Ctrl+/ 注释；⌘/Ctrl+D 复制当前/选中行；Alt+↑/↓ 移动行；⌘/Ctrl+F 查找；F3 / Shift+F3 下一个/上一个；原生撤销重做。显示行号、当前位置，列表与编辑/输出区域采用主题圆角卡片。当前为纯文本代码编辑器，尚未提供语法着色、符号补全或断点调试。

## 验证范围

语言、权限、取消、单位、边界与编辑行为使用隔离单元测试；游戏输入租约、切换式疾跑、目标选择、背包和旋转使用独立假客户端契约测试。更新后的 Java agent 已在临时目录实际启动原版 26.2，验证协议认证、设置读写、未进入世界时拒绝动作。**尚未用真实游戏世界完成移动/攻击/背包的端到端验证，其他 26.x 版本和 Windows/Linux 也未原生实测**。本轮不发布或覆盖 GitHub 已发布安装包。


## 新增操作与游戏内入口

```javascript
player.jump();
print(player.effects); // id, duration (ticks), amplifier
print(player.states.flying);
print(player.states.may_fly);
player.set_state("crouching", true);
sleep(1s);
player.set_state("crouching", false);
player.select_hotbar(2); // 0..8
player.move_item(0, 12); // inventory slots 0..35; destination must be empty
print(player.recipes); // first 64 unlocked display_id records
// player.craft(42); // use a display_id from this game's recipe snapshot
print(game.command("time query daytime")); // submitted_to_server; server decides permission
player.look_at_block(vec3(100, 64, 200), face="north", duration=300ms);
print(world.nearest_player(radius=32));
print(world.nearest_entity(type="minecraft:zombie", radius=32));
print(world.nearest_block(type="minecraft:stone", radius=8));
```

新增权限为 `player.state`、`inventory.write`、`game.command`；跳跃沿用 `player.move`，方块视角沿用 `player.look`，效果、配方与目标查询使用 `game.read`。飞行必须由服务器授予 may_fly，潜水需要在水中；只读状态不会被假装修改。疾跑和下蹲使用短租约按键，脚本结束自动释放。移动背包物品只支持自身背包、空光标与空目标槽，拒绝外部容器。合成通过普通配方放置/结果取出请求完成，依赖解锁配方、材料与合成网格；最多等待 2.5 秒，不生成物品或承诺服务器最终接受。

最近方块查询限半径 16，玩家/实体查询限半径 64，仅查询已经加载的对象；没有目标返回 `none`。返回记录含 position、distance、yaw、pitch，实体另含 id、name、type。方块 position 是方块中心，需要取整后用于面的视角；north/south/east/west/top/bottom 均可用。

26.x 主页启动按钮右侧有扳手入口。脚本工作区占满启动器内容区域，可在新窗口继续编辑、导入、运行；移动窗口不会取消运行，明确关闭工作区会停止该实例脚本。游戏内悬浮球在菜单与世界画面上方显示，正常游玩时按住 Alt 释放鼠标以点击或拖动；点击展开声音/视频设置和脚本列表，运行中显示当前语句并可停止，完成后显示 5 秒对勾。关闭自动授权时，从游戏点运行会在启动器中请求权限。旧桥接需要通过“安装 / 修复组件”更新并重启游戏。

新版桥接已在 macOS 原版 26.2 临时目录验证真实渲染、设置读写和协议认证；新增输入/权限逻辑通过隔离契约测试。真实世界中的合成、物品移动和定位尚未完成端到端验证，其他 26.x 仍需逐版本适配。


## Floating-ball speech

```javascript
say("Hello!");
say("Task completed!", duration=5s);
// Positional duration also works:
say("Next step", 1500ms);
```

`say(text, duration=3s)` displays a local speech bubble beside the in-game floating surface. It does not send chat or a server `/say` command, does not require a game-control grant, and waits for the requested display duration before executing the next script statement. The wait is cancellable, so consecutive say calls remain visible in order without an extra sleep. Direct UI messages replace the old bubble. Text may contain Unicode, up to 256 UTF-16 code units, and explicit newlines. Duration must be greater than zero and no more than 30 seconds. The bubble follows the surface, wraps using the game font, fades in/out and fits within the game window; long text is shortened with an ellipsis when the available space is insufficient. Requires the updated bridge and a restarted game.

## Foundation actions (26.x bridge; native smoke verified on 26.2)

The English language now also accepts `Record` variables. A block record contains `position`, `world`, `id`, `state`, `air`, `solid`, `replaceable`, `loaded`, and `distance`. `solid` is a movement-blocking hint, not a complete collision-shape/pathfinding model.

| Call | Behavior |
| --- | --- |
| `player.snapshot()` | Position, rotation, selected slot/item/count and current player states; `in_world=false` outside a world. |
| `player.events()` | New observations since this script's last call: `world_enter`, `world_leave`, `move`, `hotbar`, `held_item`, `state`; fields depend on event type. |
| `world.block(vec3(x,y,z))` | Inspect one loaded block within 64 blocks; fractional coordinates are floored. |
| `world.target_block()` | Current reachable crosshair block record, including its face, or `none`. |
| `player.lock_block(block, face="up", duration=2s)` | Keep looking at an unchanged block during a bounded synchronous interval; accepts a block record or Vec3. |
| `player.mine(block, face="up", timeout=30s)` | Normal mining, with tool/reach/visibility checks, ownership, cancellation and readback. |
| `player.place_block(support, face="up", timeout=2s)` | Use the selected main-hand block item against the named support face; the destination is the adjacent cell. |
| `game.logs(limit=40)` | Last 1..100 retained lines from this instance's tracked game output; common token forms are redacted. |
| `script.logs()` | Up to 200 previous output/error lines in this run; no external-file access. |
| `input.key("W", duration=250ms)` | Game-only key press and automatic release. Numeric GLFW codes also work. |
| `input.key_down("SHIFT")`, `input.key_up("SHIFT")` | Explicit holds; this run releases its own keys on cancellation, menu transition, or lease expiry. |
| `input.send_key(65, action="press", modifiers=0)` | Direct game key event; actions `press`, `release`, `repeat`. In a world this acts through real key mappings; it does not inject OS keys or implement private F3 shortcuts. |
| `input.text("Hello")` | Insert into the current input; opens a prefilled chat box when in a world with no UI. Does not submit. |
| `input.send_text("Hello")` | Send character events to the current game UI only. No input target returns `not_inserted`, without an error or opening chat. A full/rejecting field may return `partially_inserted`. |
| `chat.send("Hello")` | Actually submit a plain chat message under normal server rules. Leading slash commands require the existing `game.command()` API instead. |

Key aliases include letters, digits, `SPACE`, `SHIFT`, `CONTROL`, `ALT`, `ENTER`, `ESCAPE`, `TAB`, `BACKSPACE`, `DELETE`, `LEFT`, `RIGHT`, `UP`, `DOWN`, `HOME`, `END`. `modifiers` is the GLFW mask (Shift=1, Control=2, Alt=4, Super=8). A raw key that the game cannot handle raises an error. Raw input permissions can interact with menus and chat; they do not grant server command privileges or desktop control.

Observations sample state at up to **10 Hz**, even between script polls, once the bridge is connected. They cannot reconstruct every short-lived action between samples. Movement distances are sampled displacements, including teleports, not a guaranteed walked route. Each script has its own cursor. The game retains 256 observations and returns up to 64 per call (fewer when the protocol field budget is reached); an explicit `{type: "overflow"}` record reports lost history. Poll regularly; a large scan or slow client can delay sampling.

Target records bind actions to a specific client world session and block type. Unloaded, stale, obscured or unreachable targets fail explicitly. Mining and placement use vanilla interaction methods, not arbitrary block replacement, item creation, teleportation, or bypassing server permissions. Completion reports `client_observed_break` or `client_observed_placement`; a multiplayer server may still correct client predictions. These foundations are not a complete pathfinder or unattended survival bot.

When a world-control action encounters a menu/chat/other UI, the launcher pauses and asks whether to close that interface and continue. Consent is tied to the exact screen and world; a changed screen requires a new decision. Normal `onClose()` behavior saves/exits its parent menus. Declining, closing the prompt, or stopping cancels the script. Waiting for confirmation does not consume action timeouts, and temporary controls are released. Deliberate `input.*` actions, reads, option changes and crafting UI actions keep their intended UI context. A missing text field is a normal `not_inserted` result.

The script library now offers Rename and Delete both as visible buttons and a context menu. Renaming saves the current buffer and refuses collisions. Deleting retains it under the library's `.trash` folder. Imports and file selection never execute scripts. macOS full packages register `.ochre` as the owned text type with the folded-paper/scroll document icon; an update JAR alone does not contain a Mac application's Info.plist registration.


## Expanded observations and automation building blocks (2026-11-15)

The complete Chinese reference is at https://fishyra.github.io/ochrecl/docs/script/ ; generated command coverage is checked against the interpreter allowlist (55 builtins).

New reads: `player.vitals` (health/food/saturation/armor/air/XP/using-item/fire/water/lava/freezing/fall distance), `player.container` (current menu identity token and slot count), `game.screen` (type/open/paused/in_world), `game.observation_status` (matched hook names and sampling limits). `player.snapshot()` includes nested `inputs`, `vitals`, `container`, `screen`.

Events now cover look, bound-input changes, focused physical input press/release, aggregated mouse movement/scroll, UI/container transitions, vital changes, item use start/stop/result/completion and eating, attack attempts, server-notified damage/hits, block interaction/break/place results and observed placement. State transitions are sampled at up to 10 Hz; passive interaction hooks preserve the original game method results/control flow. Event evidence distinguishes `sampled_state`, `sampled_input`, `client_request`, `client_result`, `client_prediction`, `client_use_completion`, `client_observed_change`, and `server_damage_event`. Attack-hit packets identify target/source/type but do not supply a fabricated damage amount. Client predictions and observations can be corrected by a server. Histories retain 256 events with explicit overflow and pages bounded by both 64 events and protocol size.

`world.target()` reports the rendered crosshair block/entity/miss; menu-time cached hits may be stale. `world.collision(position)` moves the current player bounding box to a candidate feet position, returning clear/supported/can_stand. `world.can_stand(position)` returns that conjunction. `world.path_clear(position)` checks a straight sweep at quarter-block spacing, no farther than 16 blocks. These queries require loaded chunks, do not generate terrain, and do not implement pathfinding, support along the path, or hazard avoidance.

`player.item_tags()` and `player.item_is("minecraft:swords")` inspect current main-hand registry tags, including modded tags. `player.container_slots(offset=0,limit=24)` returns token/total/slots; slot records include menu index, inventory index, item/count/empty/max_count/may_pickup/accepts_held. `container_move(from,to,count=0,token=...)`, `container_swap(from,to,token=...)`, `container_quick_move(from,token=...)` require a live world/menu token and empty cursor, obey normal slot rules, and return submitted_to_server. Move supports a bounded split; merge-compatible same-item swaps are rejected in favor of move. Re-read slots to confirm results. Wire menu_token is separate from bridge authentication.

`script.wait_event(type,timeout=5s)` waits at most 60 seconds, supports cancellation and returns none on timeout. It shares the event cursor with player.events and consumes polled pages, including nonmatching events; poll explicitly if every event needs processing.

```javascript
function report(value) { print(value); return value; }
print(report(3));
// Put declarations only in a separate managed common.ochre module:
// import "common.ochre";  // imports must precede all main declarations/statements
int stage = task.load("stage", default=0);
print(stage);
task.save("stage", stage + 1);
```

Functions have isolated locals, up to 16 required untyped positional/named parameters, return through loops, global access, and up to 16 active calls. Imports are resolved and fully permission-checked before execution, including unused functions. Only managed library direct regular .ochre files with bounded ASCII basenames are accepted; cycles, duplicate definitions and executable module top-level statements are rejected. Limits: 8 import levels, 16 modules, merged 64 KiB characters. No arbitrary code loading.

`task.load(name,default=none)`, `task.save(name,value)`, `task.clear(name)` use a managed per-instance JSON store and distinct storage.read/storage.write permissions. Keys are 1..64 ASCII letters/digits/underscore/hyphen. Values: finite numbers, strings, bools, none, Lists and Records; 16 KiB, 8 nesting levels, 128 members/items, 64 checkpoints per instance. Checkpoints do not resume instruction pointers or make old world/menu tokens valid.

Native validation covers macOS Minecraft 26.2 vanilla/Fabric, authenticated snapshots, Unicode UI text, mining/placement readback and corresponding events, geometry/tags, container token rejection and one-item split/merge. Other versions/platforms require separate adaptation. The reflection-based native fixtures are a separate test agent, never packaged with the production bridge.
