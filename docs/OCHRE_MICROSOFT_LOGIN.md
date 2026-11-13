# ØchreCL 微软登录配置

当前发布构建没有配置开发者的 Microsoft Client ID，因此此前无法登录。应用 ID 不是密码；不要在启动器输入 Client Secret。

1. Fishyra 开发者在 https://entra.microsoft.com/ 的“应用注册”中新建 ØchreCL 应用，选择支持个人 Microsoft 账号的类型。
2. 按 Microsoft 的桌面公共客户端文档配置身份验证与允许的公共客户端流程。先使用设备码登录进行验证；浏览器回调必须与启动器实际回调 URI 一致。
3. 复制“应用（客户端）ID”。测试构建可在账户 → 微软登录中的配置入口保存。正式构建使用 `MICROSOFT_AUTH_ID` 环境变量打包，让普通玩家无需配置。
4. Entra 应用注册成功不代表自动获得 Minecraft Services / Xbox 的所有权限。若报 403、Invalid app registration，保留错误类型，向对应开发者支持确认准入；不能靠更换网络或借用上游 Client ID 修复。
5. 依次验证：个人账号授权、Xbox/XSTS、Minecraft Services、正版所有权、角色资料、刷新令牌。未走通全部步骤前不宣称“微软登录已修复”。

配置保存在启动器本地目录的 microsoft-client-id.txt；可删除后重新输入。当前入口用于开发者测试。不要让普通玩家自行申请应用来登录。

官方注册说明：https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app
公共客户端配置：https://learn.microsoft.com/en-us/entra/identity-platform/msal-client-application-configuration
