<p align="center"><img src="HMCL/src/main/resources/assets/img/icon@8x.png" width="88" alt="ØchreCL"></p>
<h1 align="center">ØchreCL</h1>
<p align="center">Fishyra 的 Minecraft Java 版启动器与本地服务器管理工具</p>

[官网](https://fishyra.github.io/ochrecl/) · [问题反馈](https://github.com/Fishyra/OchreCL/issues) · [构建说明](docs/OCHRE_BUILD.md) · [上游 HMCL](https://github.com/HMCL-dev/HMCL)

ØchreCL（Øchre 启动器）基于 Hello Minecraft! Launcher（HMCL）开发，重新设计了米白色桌面界面，并加入本地服务器部署与维护功能。本项目处于开发测试阶段，不是 Mojang 或 Microsoft 官方产品，也不是 HMCL 官方发行版。

## 功能

- 游戏实例与账户管理，游戏及模组、整合包、资源包、光影等内容下载。
- 可展开导航、实例封面、浅色/深色主题、运行日志与资源面板。
- 原版、Paper、Purpur、Fabric、Forge 等核心下载与部署，社区核心搜索。
- 服务器控制台、配置、玩家管理、附加项安装与备份。
- SFTP 与 OpenFrp 接入；工具安装需要玩家明确选择，穿透服务需要自行配置账号/节点。

## 0.3.0-alpha.2

[下载完整安装包](https://fishyra.github.io/ochrecl/#download) · [发布说明](https://github.com/Fishyra/OchreCL/releases/tag/v0.3.0-alpha.2)

新增可选的 26.x 游戏桥接、GitHub 更新器、四步首次启动引导、服务器导入、网络检测与地址选择。修复任务悬浮球、实例 Java 选择、沉浸式背景与附加项筛选布局。[详细变化与验证](docs/OCHRE_ALPHA2.md)

| 平台 | 状态 |
| --- | --- |
| macOS Apple Silicon | DMG 内置 Java/JavaFX；macOS 与 26.2 原版游戏桥接实测；未进行 Apple 开发者签名与公证 |
| Windows x64 | ZIP 便携测试包；完成包结构、校验检查，尚未原生实测 |
| macOS Intel x86_64 | 新增交叉装配 DMG，完成架构/结构检查，尚未 Intel 原生实测 |
| Linux x64 glibc | TAR.GZ 桌面便携测试包；需 GTK 3，尚未原生实测 |

26.x 是桥接目标家族，目前只原生验证了 26.2；其他版本按实际能力返回结果。安装桥接需玩家逐实例授权并重启游戏。脚本运行器现支持英文大括号语法、条件循环、按次授权和停止；编辑器支持配对、缩进、注释及查找。[脚本指南](docs/OCHRE_SCRIPT.md)。移动/攻击等仍需世界内端到端验证，寻路与精确手臂定位尚不可用。微软登录需要配置项目自己的有效 Client ID，参见[注册说明](docs/OCHRE_MICROSOFT_LOGIN.md)。

关于 → 启动器更新使用本仓库的 GitHub Releases 和 SHA-256 校验。旧版需先安装完整 0.3 包，之后可通过更新 JAR 升级；Java/JavaFX 变化仍需完整安装包。[桥接说明](docs/OCHRE_BRIDGE.md)

## 从源码构建

需要 JDK 17 和网络连接。在仓库根目录运行：

```sh
python3 packaging/build-bridge.py
./gradlew makeExecutables
```

Windows PowerShell：

```powershell
python packaging/build-bridge.py
.\gradlew.bat makeExecutables
```

输出位于 `HMCL/build/libs/`。Gradle 生成的 EXE/JAR 本身不等于内置 Java 的最终便携包；独立打包脚本位于 `packaging/`。

## 反馈与贡献

请在本仓库提交 Issue，说明版本、系统、复现步骤和经过脱敏的日志。不要上传整个 `data`、`.hmcl` 或 `.minecraft` 文件夹，也不要公开账号令牌、密码和穿透认证信息。

## 来源与许可

本项目保留 HMCL 上游历史和版权声明；开发起点为 `8e16127845a3994fce2f08edc5fb345f81284931`。上游文档保留在 `docs/README*.md`，其中平台和下载说明描述的是 HMCL，并非 ØchreCL 的验证结论。

请阅读 [LICENSE](LICENSE) 中的完整许可证及附加条款。第三方字体、图标和依赖的来源见 [致谢](docs/OCHRE_CREDITS.md)。
