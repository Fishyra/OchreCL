package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.net.NetworkInterface;
import java.util.*;

/// Selects real local interfaces and explicitly configured external endpoints without guessing NAT mappings.
@NotNullByDefault
public final class ServerAddressPicker extends HBox {
    /// Bound server metadata, never credentials.
    private final ServerInstance server;
    /// Endpoint options are rebuilt when opened to reflect changed network interfaces and tunnel settings.
    private final ComboBox<Endpoint> choices = new ComboBox<>();
    /// Selectable address matching the copy action.
    private final TextField address = new TextField();
    /// One human-readable source and its connectable address.
    @NotNullByDefault
    public record Endpoint(String label, String address) {
        /// Uses the label in the compact selector, keeping the full address in its own field.
        @Override public String toString() { return label; }
    }
    /// Builds a compact address source, full address and copy action.
    public ServerAddressPicker(ServerInstance server) {
        super(4); this.server = server; setAlignment(Pos.CENTER_LEFT); setMinWidth(0); getStyleClass().add("ochre-address-picker");
        choices.setMinWidth(54); choices.setPrefWidth(86); choices.setMaxWidth(130); choices.setAccessibleText("服务器地址来源");
        address.setEditable(false); address.setMinWidth(0); address.setPrefColumnCount(10); address.setAccessibleText("当前服务器连接地址"); HBox.setHgrow(address, Priority.ALWAYS);
        JFXButton copy = new JFXButton(); copy.setGraphic(SVG.CONTENT_COPY.createIcon(18)); copy.setAccessibleText("复制当前所选地址"); copy.setMinWidth(26); copy.setPrefWidth(28); copy.setMaxWidth(28); copy.setOnAction(event -> { var content = new javafx.scene.input.ClipboardContent(); content.putString(address.getText()); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); ServerManager.notice("已复制 " + address.getText()); }); copy.disableProperty().bind(address.textProperty().isEmpty());
        JFXButton external = new JFXButton(); external.setGraphic(SVG.EDIT.createIcon(16)); external.setAccessibleText("设置公网地址"); external.setMinWidth(26); external.setPrefWidth(28); external.setMaxWidth(28); external.setOnAction(event -> editExternal());
        choices.valueProperty().addListener((observable, before, value) -> { address.setText(value == null ? "" : value.address()); address.setTooltip(value == null ? null : new Tooltip(value.address())); }); choices.setOnShowing(event -> refresh());
        getChildren().setAll(choices, address, copy, external); refresh();
    }
    /// Re-enumerates only interfaces owned by this machine; no network scans or external lookups.
    public void refresh() {
        @Nullable Endpoint selected = choices.getValue(); choices.getItems().setAll(endpoints(server));
        choices.setValue(selected != null && choices.getItems().contains(selected) ? selected : choices.getItems().get(0));
    }
    /// Returns local, LAN, tunnel and manually configured public addresses in a stable order.
    public static @Unmodifiable List<Endpoint> endpoints(ServerInstance server) {
        var endpoints = new ArrayList<Endpoint>(); endpoints.add(new Endpoint("本地", server.address())); String port = server.settings.getProperty("port", "25565");
        try {
            for (var devices = NetworkInterface.getNetworkInterfaces(); devices.hasMoreElements();) {
                var device = devices.nextElement(); if (!device.isUp() || device.isLoopback()) continue;
                for (var addresses = device.getInetAddresses(); addresses.hasMoreElements();) { var ip = addresses.nextElement(); if (!ip.isLoopbackAddress() && ip.isSiteLocalAddress()) { String host = ip.getHostAddress(); endpoints.add(new Endpoint("局域网 · " + device.getName(), (host.contains(":") ? "[" + host + "]" : host) + ":" + port)); } }
            }
        } catch (java.net.SocketException ignored) { /* Local loopback remains usable. */ }
        String tunnel = server.settings.getProperty("service.openfrp.address", ""); if (!tunnel.isBlank()) endpoints.add(new Endpoint("OpenFrp", tunnel));
        String relay = server.settings.getProperty("service.frp.host", ""); if (!relay.isBlank()) endpoints.add(new Endpoint("FRP", relay + ":" + server.settings.getProperty("service.frp.remote", port)));
        String publicAddress = server.settings.getProperty("network.publicAddress", ""); if (!publicAddress.isBlank()) endpoints.add(new Endpoint("公网", publicAddress));
        return List.copyOf(endpoints);
    }
    /// Records an owner-provided external address; it does not claim the endpoint has been verified reachable.
    private void editExternal() {
        VBox pane = new VBox(12); pane.setPadding(new javafx.geometry.Insets(22)); pane.setPrefWidth(470); pane.setMaxWidth(560); pane.getStyleClass().add("jfx-dialog-layout");
        Label title = new Label("公网连接地址"); title.setGraphic(SVG.OCHRE_CONNECT.createIcon(22));
        Label hint = new Label("填写域名或公网 IP 与映射端口，例如 play.example.com:25565。请先完成端口映射；此处保存地址不会开放端口。"); hint.setWrapText(true);
        TextField input = new TextField(server.settings.getProperty("network.publicAddress", "")); input.setPromptText("域名:端口 / [IPv6]:端口"); Label error = new Label(); error.setWrapText(true);
        JFXButton save = new JFXButton("保存"), close = new JFXButton("关闭");
        save.setOnAction(event -> { String value = input.getText().strip(); if (value.length() > 260 || value.matches(".*[\\s/\\\\?#@].*")) { error.setText("请输入不含空格或协议前缀的主机地址与端口"); return; } server.settings.setProperty("network.publicAddress", value); try { server.save(); refresh(); if (!value.isBlank()) choices.getSelectionModel().selectLast(); pane.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()); } catch (java.io.IOException failure) { error.setText(failure.getMessage()); } });
        close.setOnAction(event -> pane.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent())); pane.getChildren().setAll(title, hint, input, error, new HBox(10, save, close)); Controllers.dialog(pane);
    }
}
