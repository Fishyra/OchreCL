package org.jackhuang.hmcl.server;

import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/// Compact live status cards using Minecraft's status protocol without adding commands to the console.
@NotNullByDefault
public final class ServerStatusStrip extends FlowPane {
    /// Actual online player count; unavailable stays explicit.
    private final Label players = new Label("—");
    /// Avoids overlapping status probes when a server is slow.
    private boolean probing;
    /// Creates adaptive cards and suspends polling when the page leaves the scene.
    public ServerStatusStrip(ServerInstance server) {
        super(10, 10); setPadding(new Insets(0, 0, 12, 0));
        Label state = new Label(); state.textProperty().bind(server.session.stateProperty());
        var address = new com.jfoenix.controls.JFXButton(server.address()); address.setAccessibleText("复制服务器连接地址"); address.setOnAction(event -> { var copy = new javafx.scene.input.ClipboardContent(); copy.putString(server.address()); javafx.scene.input.Clipboard.getSystemClipboard().setContent(copy); ServerManager.notice("已复制 " + server.address()); });
        VBox connection = new VBox(5, new Label("连接地址 · 点击复制"), address); connection.setPadding(new Insets(12)); connection.setPrefWidth(220); connection.getStyleClass().add("ochre-server-controls");
        getChildren().addAll(card("服务器状态", state), connection, card("在线玩家", players), card("游戏版本", new Label(server.settings.getProperty("core", "") + " " + server.settings.getProperty("version", ""))));
        javafx.animation.Timeline timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(10), event -> probe(server))); timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty().addListener((observable, previous, scene) -> { if (scene == null) timer.stop(); else { timer.play(); probe(server); } });
        server.session.stateProperty().addListener(new javafx.beans.value.WeakChangeListener<String>((observable, before, after) -> probe(server)));
    }
    /// Queries a locally owned server off the UI thread; disabled status responses are displayed as unavailable.
    private void probe(ServerInstance server) {
        if (getScene() == null || probing) return;
        if (!server.session.isRunning()) { players.setText("未运行"); return; }
        probing = true; int port = Integer.parseInt(server.settings.getProperty("port", "25565"));
        Schedulers.io().execute(() -> {
            String value;
            try { value = ping(port); } catch (Exception ignored) { value = "不可用"; }
            String result = value; Platform.runLater(() -> { probing = false; if (getScene() != null) players.setText(server.session.isRunning() ? result : "未运行"); });
        });
    }
    /// Reads a bounded Minecraft Java status response over loopback only.
    static String ping(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); socket.setSoTimeout(1500);
            var bytes = new ByteArrayOutputStream(); var packet = new DataOutputStream(bytes);
            writeInt(packet, 0); writeInt(packet, -1); byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8); writeInt(packet, host.length); packet.write(host); packet.writeShort(port); writeInt(packet, 1);
            var output = new DataOutputStream(socket.getOutputStream()); writeInt(output, bytes.size()); bytes.writeTo(output); output.write(new byte[]{1, 0}); output.flush();
            var input = new DataInputStream(socket.getInputStream()); int length = readInt(input); if (length < 2 || length > 1048576) throw new IOException("状态响应大小无效");
            byte[] response = input.readNBytes(length); if (response.length != length) throw new EOFException(); var body = new DataInputStream(new ByteArrayInputStream(response)); if (readInt(body) != 0) throw new IOException("状态响应类型无效"); int size = readInt(body); if (size < 0 || size > body.available()) throw new IOException("状态数据不完整");
            var status = JsonParser.parseString(new String(body.readNBytes(size), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("players"); return status.get("online").getAsInt() + " / " + status.get("max").getAsInt() + " 人";
        }
    }
    /// Encodes protocol VarInt values, including negative handshake protocol versions.
    private static void writeInt(DataOutputStream output, int value) throws IOException { do { int bits = value & 127; value >>>= 7; output.writeByte(value == 0 ? bits : bits | 128); } while (value != 0); }
    /// Decodes a bounded five-byte protocol VarInt.
    private static int readInt(DataInputStream input) throws IOException { int result = 0; for (int index = 0; index < 5; index++) { int value = input.readUnsignedByte(); result |= (value & 127) << index * 7; if ((value & 128) == 0) return result; } throw new IOException("状态响应 VarInt 无效"); }
    /// One rounded status surface sized for both narrow and wide windows.
    private static VBox card(String title, Label value) { VBox card = new VBox(5, new Label(title), value); card.setPadding(new Insets(12)); card.setPrefWidth(190); card.getStyleClass().add("ochre-server-controls"); return card; }
}
