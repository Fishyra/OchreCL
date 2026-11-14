# Øchre Script 开发版

入口：26.x 游戏运行时，主页 → 操作游戏 → 脚本。旧的文本草稿仍可打开；`.ochre` 是新的脚本后缀。导入、选择、新建和保存均不会执行脚本。脚本只在玩家检查源码、选择本次权限并点击“运行脚本”后执行。

## 语言

英文标识符、关键字与单位，大括号代码块，分号结束语句；字符串与注释可包含任意语言。支持 `int`、`float`、`bool`、`string`、`Player`、`Vec3`、`List`，严格类型赋值；声明但未赋值的变量不可读取。值 `none` 表示未找到目标或空物品槽。内置函数：`print`、`sleep`、`vec3`。自定义函数、异步并发、文件/网络/系统 API 与外部语言执行不在本版中。

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

每次运行显式勾选 `game.read`、`settings.write`、`player.look`、`player.move`、`player.attack`。完整解析后预先核对全部引用（包括未执行分支），执行具体操作时再次检查权限。勾选在运行开始后清空，授权不写入磁盘。桥接安装同意不等于自动授予脚本操作权限。组件令牌只用于本机、当前实例通信，不对脚本暴露。

停止按钮、关闭工作区、执行失败都会结束脚本并释放临时输入。游戏端另有 750ms 输入租约和 100ms 检查周期，启动器意外退出或连接中断后也会释放所占按键。已经由玩家按住的前进/疾跑键会拒绝接管；切换式疾跑与普通按住式均按幂等状态处理。游戏线程本身卡住时，释放会在游戏线程恢复后执行；不声称能在被冻结的线程上即时执行。

限制：源码 64 KiB 字符、20,000 token、80 层解析嵌套、每次运行 200,000 指令与 10 分钟。每个宿主最多约 50 个桥接请求/秒。输出最多 1000 行，界面保留最近 64 KiB。未捕获的执行错误附带原始行列；脚本不能访问 Java 反射、文件、进程或任意网络。

## 编辑器

括号/引号配对、选择包裹、重复闭合跳过、成对退格、回车继承缩进、大括号内换行；Tab / Shift+Tab 缩进；⌘/Ctrl+/ 注释；⌘/Ctrl+D 复制当前/选中行；Alt+↑/↓ 移动行；⌘/Ctrl+F 查找；F3 / Shift+F3 下一个/上一个；原生撤销重做。显示行号、当前位置，列表与编辑/输出区域采用主题圆角卡片。当前为纯文本代码编辑器，尚未提供语法着色、符号补全或断点调试。

## 验证范围

语言、权限、取消、单位、边界与编辑行为使用隔离单元测试；游戏输入租约、切换式疾跑、目标选择、背包和旋转使用独立假客户端契约测试。更新后的 Java agent 已在临时目录实际启动原版 26.2，验证协议认证、设置读写、未进入世界时拒绝动作。**尚未用真实游戏世界完成移动/攻击/背包的端到端验证，其他 26.x 版本和 Windows/Linux 也未原生实测**。本轮不发布或覆盖 GitHub 已发布安装包。
