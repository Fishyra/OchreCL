# ØchreCL 构建与发行

## 源码

使用 JDK 17，在根目录运行 `./gradlew makeExecutables`（Windows 使用 `gradlew.bat`）。首次构建会下载 Gradle 与依赖。额外的 Bouncy Castle 依赖保留在 lib 中，供 OpenFrp 加密流程使用。

Microsoft 登录和 CurseForge 接入的构建凭据从 `MICROSOFT_AUTH_ID` 与 `CURSEFORGE_API_KEY` 环境变量读取。仓库不包含这些凭据；普通源码构建默认留空。不要提交自己的环境文件、私钥或服务密码。

## 原生包

`packaging/prepare-windows.py` 根据锁定的官方来源获取 Windows JRE、JavaFX、Noto 字体与许可证，使用 SHA-256 校验。`package-windows.py` 使用 Windows JDK 的 jpackage 制作便携目录；请优先在真实 Windows 上执行。脚本中的旧预览构建仍需以发布说明为准。

`package-release.py` 是预览版发行辅助脚本，包含 macOS ARM64 DMG 和基于先前 app-image 的 Windows 离线重打包流程。需准备 JDK、JavaFX 缓存及前置便携构建；不是开箱即用的跨平台构建流水线。macOS 可用 `OCHRE_MAC_RUNTIME` 指定受维护的 ARM64 JRE。它不执行 Whisky 测试。

打包产物中禁止包含开发者的 `data`、测试配置、账户令牌、游戏存档或服务器世界。macOS 发行版数据位于用户的 Library/Application Support/OchreCL；Windows 便携版数据位于程序旁的 data。

## 自动化

上游工作流移到 `.github/upstream-workflows/` 保存参考，没有启用自动发布、镜像或外部平台同步。当前仓库暂不承诺自动生成安装包。
