package org.jackhuang.hmcl.bridge;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.server.RuntimeSession;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/// Per-instance consent, loader-free attachment and bounded local bridge requests for Minecraft 26.x.
@NotNullByDefault
public final class BridgeManager {
    /// Bundled bridge bytes are never taken from an arbitrary remote source or a player's script.
    private static final String RESOURCE = "/assets/bridge/OchreBridge.jar";
    /// Utility class.
    private BridgeManager() {}
    /// Uses the actual resolved game version, not the user-editable instance name.
    public static boolean supported(HMCLGameInstance instance) { return is26(instance.getVersion().toString()); }
    /// Defines the requested version family; individual capabilities are still negotiated with the real client API.
    public static boolean is26(String version) { return version.matches("26\\.[0-9]+(?:\\.[0-9]+)?(?:[-+ ].*)?"); }
    /// Derives a private bridge folder without moving the user's game files or mods.
    private static Path root(String key) { return Path.of(key).resolve(".ochre-bridge"); }
    /// Reads consent without treating missing configuration as permission.
    public static boolean installed(String key) { try { Properties settings = read(root(key).resolve("settings.properties")); return Boolean.parseBoolean(settings.getProperty("enabled")) && Files.isRegularFile(root(key).resolve(settings.getProperty("agent", "missing"))); } catch (IOException error) { return false; } }
    /// Copies the bundled optional component after explicit in-app consent; it is not loaded into an already running JVM.
    public static void install(String key) throws Exception {
        Path folder = root(key); Files.createDirectories(folder); restrict(folder, true);
        byte[] image; try (var input = Objects.requireNonNull(BridgeManager.class.getResourceAsStream(RESOURCE))) { image = input.readAllBytes(); }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); String name = "bridge-" + hash + ".jar"; Path file = folder.resolve(name);
        { Path pending = folder.resolve(name + ".new"); Files.write(pending, image); restrict(pending, false); Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
        Properties settings = new Properties(); settings.setProperty("enabled", "true"); settings.setProperty("agent", name); settings.setProperty("sha256", hash); settings.setProperty("protocol", "1"); store(folder.resolve("settings.properties"), settings);
    }
    /// Disables future attachment without deleting or patching any original Minecraft archive.
    public static void uninstall(String key) throws IOException { Properties settings = read(root(key).resolve("settings.properties")); settings.setProperty("enabled", "false"); store(root(key).resolve("settings.properties"), settings); }
    /// Validates the component and prepares a fresh launch token; no token is embedded in process arguments.
    public static @Nullable String prepare(HMCLGameInstance instance) throws Exception {
        String key = instance.getInstanceRoot().toAbsolutePath().normalize().toString(); if (!supported(instance) || !installed(key)) return null;
        Path folder = root(key); Properties settings = read(folder.resolve("settings.properties")); String name = settings.getProperty("agent", ""); if (!name.matches("bridge-[a-f0-9]{64}\\.jar")) throw new IOException("桥接组件路径无效");
        Path image = folder.resolve(name); byte[] bytes = Files.readAllBytes(image); String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); if (!hash.equals(settings.getProperty("sha256"))) throw new IOException("桥接组件校验失败，请重新安装");
        byte[] token = new byte[32]; new java.security.SecureRandom().nextBytes(token); Properties launch = new Properties(); launch.setProperty("enabled", "true"); launch.setProperty("token", HexFormat.of().formatHex(token)); launch.setProperty("version", instance.getVersion().toString());
        Path config = folder.resolve("launch.properties"); store(config, launch); Files.deleteIfExists(folder.resolve("connection.properties"));
        return image + "=" + Base64.getUrlEncoder().withoutPadding().encodeToString(config.toString().getBytes(StandardCharsets.UTF_8));
    }
    /// Sends one token-authorized loopback request; no scripts, URLs or arbitrary files can be executed by this API.
    public static @Unmodifiable Map<String, String> request(String key, String command, Map<String, String> arguments) throws IOException {
        if (!installed(key)) throw new IOException("桥接组件未安装或已停用"); Path folder = root(key); Properties launch = read(folder.resolve("launch.properties")), endpoint = read(folder.resolve("connection.properties"));
        int port; long pid; try { port = Integer.parseInt(endpoint.getProperty("port", "0")); pid = Long.parseLong(endpoint.getProperty("pid", "0")); } catch (NumberFormatException error) { throw new IOException("桥接连接记录无效"); }
        if (port < 1024 || port > 65535 || ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) throw new IOException("游戏桥接尚未连接，请重新启动该实例");
        Map<String, String> message = new LinkedHashMap<>(arguments); message.put("protocol", "1"); message.put("command", command); message.put("token", launch.getProperty("token", ""));
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); socket.setSoTimeout(2500); BridgeWire.write(socket.getOutputStream(), message); var response = BridgeWire.read(socket.getInputStream()); if (!"true".equals(response.get("ok"))) throw new IOException("游戏桥接：" + response.getOrDefault("error", "请求失败")); if (!is26(response.getOrDefault("gameVersion", ""))) throw new IOException("桥接游戏版本不受支持"); return response; }
    }
    /// Shows the requested installation prompt; skip keeps the launcher usable with deferred file settings.
    public static void offer(String key, Runnable opened) { offer(key, opened, false); }
    /// Explicit repair may replace a corrupt local image after repeating the consent disclosure.
    private static void offer(String key, Runnable opened, boolean repair) {
        if (!repair && installed(key)) { opened.run(); return; }
        VBox pane = new VBox(14); pane.setPadding(new Insets(24)); pane.setPrefWidth(550); pane.setMaxWidth(650); pane.getStyleClass().add("jfx-dialog-layout");
        Label title = new Label("安装 ØchreCL 游戏桥接组件？"); title.setGraphic(SVG.SETTINGS.createIcon(24)); title.getStyleClass().add("ochre-instance-title");
        Label explanation = new Label("组件让启动器读取此实例的语言、位置与运行状态，并实时修改声音和视频选项。无需手动安装 Fabric 或 Forge；通过启动器挂载，不改写原版游戏文件。\n\n仅开放本机、单实例授权的连接。26.x 接口仍需逐版本验证，当前已对照 26.2 公共 API；不兼容功能会返回不可用。安装后需要重新启动游戏才能连接。"); explanation.setWrapText(true);
        CheckBox permission = new com.jfoenix.controls.JFXCheckBox("允许该实例连接启动器并修改上述设置"); permission.setWrapText(true);
        Label status = new Label(); status.setWrapText(true); JFXButton install = new JFXButton("安装并授权"), skip = new JFXButton("暂不安装"), close = new JFXButton("取消"); install.setGraphic(SVG.DOWNLOAD.createIcon(18)); install.disableProperty().bind(permission.selectedProperty().not());
        install.setOnAction(event -> { permission.setDisable(true); status.setText("正在安装本地桥接组件…"); Schedulers.io().execute(() -> { try { install(key); Platform.runLater(() -> { pane.fireEvent(new DialogCloseEvent()); org.jackhuang.hmcl.server.ServerManager.notice("桥接组件已安装，重新启动该游戏后启用"); opened.run(); }); } catch (Exception error) { Platform.runLater(() -> { permission.setDisable(false); status.setText("安装失败：" + error.getMessage()); }); } }); });
        skip.setOnAction(event -> { pane.fireEvent(new DialogCloseEvent()); opened.run(); }); close.setOnAction(event -> pane.fireEvent(new DialogCloseEvent())); pane.getChildren().setAll(title, explanation, permission, status, new FlowPane(10, 8, install, skip, close)); Controllers.dialog(pane);
    }
    /// Adds bridge installation, real handshake and uninstall controls to the operation workspace.
    public static VBox controls(String key) {
        Label status = new Label(installed(key) ? "已安装 · 等待连接当前游戏" : "未安装桥接组件 · 设置将等待游戏退出后应用"); status.setWrapText(true);
        JFXButton connect = new JFXButton("检查游戏连接"), install = new JFXButton("安装 / 修复组件"), remove = new JFXButton("停用组件"); connect.setGraphic(SVG.OCHRE_CONNECT.createIcon(18));
        connect.setOnAction(event -> { connect.setDisable(true); Schedulers.io().execute(() -> { String text; try { var hello = request(key, "hello", Map.of()); text = "已连接 Minecraft " + hello.get("gameVersion") + " · 协议 " + hello.get("protocol") + "\n可用能力：" + hello.get("capabilities"); } catch (Exception error) { text = "尚未连接：" + error.getMessage(); } String message = text; Platform.runLater(() -> { status.setText(message); connect.setDisable(false); }); }); });
        install.setOnAction(event -> offer(key, () -> status.setText(installed(key) ? "已安装；若游戏已在运行，需重启游戏连接" : "未安装"), true));
        remove.setOnAction(event -> Controllers.confirm("停用后下次不再挂载。当前游戏需退出后完全解除组件；原版文件保留。", "停用游戏桥接", () -> { try { uninstall(key); status.setText("已停用，下次启动生效"); } catch (IOException error) { status.setText(error.getMessage()); } }, null));
        VBox box = new VBox(8, status, new FlowPane(8, 8, connect, install, remove)); box.setPadding(new Insets(12)); box.getStyleClass().add("ochre-server-controls"); return box;
    }
    /// Loads only the small owned metadata files, never account credentials.
    private static Properties read(Path file) throws IOException { if (!Files.isRegularFile(file)) throw new IOException("尚未生成桥接配置，请安装并启动此游戏"); if (Files.size(file) > 16384) throw new IOException("桥接配置异常"); Properties values = new Properties(); try (var input = Files.newInputStream(file)) { values.load(input); } return values; }
    /// Atomically replaces a private bridge metadata file.
    private static void store(Path file, Properties values) throws IOException { Files.createDirectories(file.getParent()); restrict(file.getParent(), true); Path pending = file.resolveSibling(file.getFileName() + ".new"); try (var output = Files.newOutputStream(pending)) { values.store(output, "Per-instance local bridge authorization"); } restrict(pending, false); Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
    /// Uses owner-only permissions where supported, retaining normal per-user protection on Windows.
    private static void restrict(Path path, boolean directory) throws IOException { try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (UnsupportedOperationException ignored) { } }
}
