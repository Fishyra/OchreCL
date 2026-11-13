package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import java.net.*;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/// Bounded local network checks and anomaly warnings, never a claim of upstream DDoS protection.
@NotNullByDefault
public final class ServerNetworkPane extends VBox {
    /// Consecutive local status probe failures, isolated by installation identity.
    private static final ConcurrentHashMap<ServerInstance, Integer> FAILURES = new ConcurrentHashMap<>();
    /// Last alert time suppresses repetitive notices during one incident.
    private static final ConcurrentHashMap<ServerInstance, Instant> ALERTS = new ConcurrentHashMap<>();
    /// Measured individual links; a configured tunnel is not proof of public reachability.
    @NotNullByDefault
    public record Report(boolean listening, boolean lan, boolean internet, String status, String detail) {}
    /// Opens the diagnostic path independently from emergency controls.
    public static void show(ServerInstance server) { Controllers.dialog(new ServerNetworkPane(server, false)); }
    /// Opens monitoring and deliberate intervention without conflating them with a network test.
    public static void showEmergency(ServerInstance server) { Controllers.dialog(new ServerNetworkPane(server, true)); }
    /// Creates one distinct network report or emergency-settings dialog.
    private ServerNetworkPane(ServerInstance server, boolean emergency) {
        super(14); setPadding(new Insets(24)); setPrefSize(720, 500); setMinSize(300, 280); setMaxSize(850, 640); getStyleClass().addAll("jfx-dialog-layout", "ochre-network-panel");
        Label title = new Label(emergency ? "异常监测与应急" : "网络诊断"); title.getStyleClass().add("ochre-instance-title"); title.setGraphic(SVG.OCHRE_CONNECT.createIcon(24));
        Label summary = new Label(emergency ? "多种故障都可能造成响应异常；本机监测无法确认或阻挡 DDoS。" : "检查服务器 → 本机 → 网络 → 互联网的连接情况。"); summary.setWrapText(true);
        TextArea result = new TextArea(); result.setEditable(false); result.setWrapText(true); result.setMinHeight(50); result.setPrefRowCount(5); result.getStyleClass().add("ochre-runtime-console"); VBox.setVgrow(result, Priority.ALWAYS);
        JFXButton close = new JFXButton("关闭"); close.setGraphic(SVG.CLOSE.createIcon(18)); close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        if (emergency) {
            JFXToggleButton enabled = new JFXToggleButton(); enabled.setText("持续异常监测（启动器开启期间）"); enabled.setSelected(Boolean.parseBoolean(server.settings.getProperty("network.monitor", "false")));
            enabled.setOnAction(event -> { server.settings.setProperty("network.monitor", Boolean.toString(enabled.isSelected())); try { server.save(); } catch (IOException e) { ServerManager.notice("保存失败：" + e.getMessage()); } });
            JFXButton save = new JFXButton("保存世界"); save.setGraphic(SVG.CHECK.createIcon(18)); save.setOnAction(event -> Schedulers.io().execute(() -> { try { ServerManager.command(server, "save-all flush"); ServerManager.notice("已发送保存世界命令"); } catch (IOException e) { ServerManager.notice(e.getMessage()); } }));
            JFXButton isolate = new JFXButton("暂停 OpenFrp 穿透"); isolate.setGraphic(SVG.OCHRE_STOP.createIcon(18)); isolate.setOnAction(event -> Controllers.confirm("暂停隧道会断开通过它连接的玩家。本地服务器保持运行。是否继续？", "暂停公网入口", () -> Schedulers.io().execute(() -> { try { ServerServices.stop(server, ServerToolInstaller.Tool.OPENFRP); ServerManager.notice("已暂停穿透"); } catch (Exception e) { ServerManager.notice(e.getMessage()); } }), null));
            Label limits = new Label("保存世界与暂停穿透是手动应急操作，无法抵御链路拥塞或上游攻击。需要时联系节点或网络供应商。请先检查负载、日志和网络诊断。"); limits.setWrapText(true);
            Region gap = new Region(); VBox.setVgrow(gap, Priority.ALWAYS); getChildren().setAll(title, summary, enabled, limits, gap, new FlowPane(10, 8, save, isolate, close)); return;
        }
        TilePane path = new TilePane(12, 12); path.setPrefColumns(4); path.setPrefTileWidth(135); path.widthProperty().addListener((observable, before, after) -> { int columns = after.doubleValue() >= 560 ? 4 : 2; path.setPrefColumns(columns); path.setPrefTileWidth(Math.max(60, (after.doubleValue() - 16 - (columns - 1) * 12) / columns)); }); path.setAlignment(javafx.geometry.Pos.CENTER); path.setPadding(new Insets(18, 8, 18, 8)); path.getStyleClass().add("ochre-server-controls");
        Label localMark = new Label("待检测"), lanMark = new Label("待检测"), internetMark = new Label("待检测");
        for (var link : java.util.List.of(pathNode("服务器", SVG.OCHRE_SERVER, localMark), pathNode("本机", SVG.LUCIDE_APP_WINDOW, new Label("✓ 启动器运行中")), pathNode("网络接口", SVG.OCHRE_CONNECT, lanMark), pathNode("互联网", SVG.GLOBE_BOOK, internetMark))) { HBox.setHgrow(link, Priority.ALWAYS); path.getChildren().add(link); }
        Label scope = new Label("互联网检测连接 sessionserver.mojang.com:443，不发送账号数据；成功仅代表该路径可连接。不会据此宣称公网入站或隧道已打通。"); scope.setWrapText(true); scope.getStyleClass().add("subtitle-label");
        JFXButton check = new JFXButton("开始检测"); check.setGraphic(SVG.SEARCH.createIcon(18)); check.setOnAction(event -> {
            check.setDisable(true); summary.setText("正在检测实际链路…");
            Schedulers.io().execute(() -> { Report report = inspect(server); Platform.runLater(() -> {
                localMark.setText(report.listening() ? "✓ 可连接" : "× 未连接"); lanMark.setText(report.lan() ? "✓ 有可用接口" : "× 无局域网接口"); internetMark.setText(report.internet() ? "✓ 可连接" : "× 不可连接");
                summary.setText(report.status()); summary.setGraphic((report.listening() && report.internet() ? SVG.CHECK_CIRCLE : SVG.INFO).createIcon(22)); result.setText(report.detail()); check.setDisable(false); check.setText("重新检测");
            }); });
        });
        JFXButton copy = new JFXButton("复制报告"); copy.setGraphic(SVG.CONTENT_COPY.createIcon(18)); copy.setOnAction(event -> { var content = new javafx.scene.input.ClipboardContent(); content.putString(summary.getText() + "\n" + result.getText()); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        JFXButton advanced = new JFXButton("异常应急 →"); advanced.setOnAction(event -> { fireEvent(new DialogCloseEvent()); ServerNetworkPane.showEmergency(server); });
        FlowPane actions = new FlowPane(10, 8, check, copy, close, advanced); getChildren().setAll(title, path, summary, scope, result, actions);
    }
    /// Builds one link node with a semantic icon and independently measured status.
    private static VBox pathNode(String name, SVG icon, Label state) { VBox node = new VBox(10, icon.createIcon(30), new Label(name), state); node.setAlignment(javafx.geometry.Pos.CENTER); node.setMinWidth(0); node.setMaxWidth(Double.MAX_VALUE); state.setWrapText(true); return node; }
    /// Checks bounded sockets to one owned listener and one fixed public service, never an arbitrary port scan.
    static Report inspect(ServerInstance server) {
        boolean local = false, internet = false; long start = System.nanoTime();
        String localTiming = "服务器未运行";
        if (server.session.isRunning()) try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(server.settings.getProperty("port", "25565"))), 1500); local = true; localTiming = String.format("%.1f ms", (System.nanoTime() - start) / 1000000.0); } catch (Exception error) { localTiming = "监听端口不可连接"; }
        boolean lan = ServerAddressPicker.endpoints(server).stream().anyMatch(endpoint -> endpoint.label().startsWith("局域网"));
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("sessionserver.mojang.com", 443), 2500); internet = true; } catch (IOException ignored) { /* The report keeps this path explicitly unverified. */ }
        String status = local && internet ? "已检测的本机服务与互联网路径可连接" : !local && internet ? "互联网可连接；请检查服务器是否启动及监听端口" : "部分链路未通过，请查看下方报告";
        String detail = "检测时间：" + java.time.ZonedDateTime.now() + "\n本地地址：" + server.address() + "\n本机监听：" + localTiming + "\n互联网 TCP 443：" + (internet ? "可连接" : "未连接（含 DNS、网络策略或远端故障）") + "\nOpenFrp 客户端：" + (ServerServices.running(server, ServerToolInstaller.Tool.OPENFRP) ? "运行中，公网入站尚未验证" : "未运行") + "\n\n可用地址：\n" + ServerAddressPicker.endpoints(server).stream().map(endpoint -> endpoint.label() + " · " + endpoint.address()).collect(java.util.stream.Collectors.joining("\n"));
        return new Report(local, lan, internet, status, detail);
    }
    /// Keeps plain-text diagnostics available to existing support integrations.
    static String diagnose(ServerInstance server) { return inspect(server).detail(); }
    /// Samples once per scheduler tick, alerting after three failures accompanied by recent log errors.
    public static void monitor(ServerInstance server) {
        if (!server.session.isRunning() || !Boolean.parseBoolean(server.settings.getProperty("network.monitor", "false"))) { FAILURES.remove(server); return; }
        Schedulers.io().execute(() -> {
            try { ServerStatusStrip.ping(Integer.parseInt(server.settings.getProperty("port", "25565"))); FAILURES.remove(server); }
            catch (Exception failure) {
                int count = FAILURES.merge(server, 1, Integer::sum);
                long errors = server.session.log().lines().skip(Math.max(0, server.session.log().lines().count() - 100)).filter(line -> line.contains("ERROR") || line.contains("Can't keep up") || line.contains("Timed out")).count();
                Instant now = Instant.now(), previous = ALERTS.getOrDefault(server, Instant.EPOCH);
                if (count >= 3 && errors >= 3 && java.time.Duration.between(previous, now).toMinutes() >= 5) { ALERTS.put(server, now); ServerManager.notice(ServerManager.NoticeKind.WARNING, server + "：持续响应异常并伴有日志错误。请打开网络检测；可能是卡服或网络异常，尚不能确认攻击。"); }
            }
        });
    }
}
