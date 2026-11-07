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

## 平台与测试状态

| 平台 | 状态 |
| --- | --- |
| macOS Apple Silicon | 主要开发平台；DMG 预览包内置 Java/JavaFX，未进行 Apple 开发者签名与公证 |
| Windows x64 | 便携测试包；已补充字体回退，仍需真实 Windows 10/11 的完整验证 |
| Linux / Intel Mac / Windows ARM | 尚未提供经过验证的独立发行包 |

Whisky 测试曾出现字体崩溃，用户也报告过整机卡顿。不要把兼容层中的初步启动当作真实 Windows 验证，也不要在相同环境中反复启动测试进程。重要游戏与服务器数据请自行备份。

启动器不检查或安装上游 HMCL 的自动更新。发行说明与官网信息可能先于本仓库的 Release 资产更新，请核对版本与校验值。

## 从源码构建

需要 JDK 17 和网络连接。在仓库根目录运行：

```sh
./gradlew makeExecutables
```

Windows PowerShell：

```powershell
.\gradlew.bat makeExecutables
```

输出位于 `HMCL/build/libs/`。Gradle 生成的 EXE/JAR 本身不等于内置 Java 的最终便携包；独立打包脚本位于 `packaging/`。

## 反馈与贡献

请在本仓库提交 Issue，说明版本、系统、复现步骤和经过脱敏的日志。不要上传整个 `data`、`.hmcl` 或 `.minecraft` 文件夹，也不要公开账号令牌、密码和穿透认证信息。

## 来源与许可

本项目保留 HMCL 上游历史和版权声明；开发起点为 `8e16127845a3994fce2f08edc5fb345f81284931`。上游文档保留在 `docs/README*.md`，其中平台和下载说明描述的是 HMCL，并非 ØchreCL 的验证结论。

请阅读 [LICENSE](LICENSE) 中的完整许可证及附加条款。第三方字体、图标和依赖的来源见 [致谢](docs/OCHRE_CREDITS.md)。
