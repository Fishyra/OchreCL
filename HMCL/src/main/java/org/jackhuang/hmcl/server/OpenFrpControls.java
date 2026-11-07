package org.jackhuang.hmcl.server;

import javafx.event.ActionEvent;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/// Shared OpenFrp login-before-install flow for deployment and existing server settings.
@NotNullByDefault
public final class OpenFrpControls {
    /// Static UI factory.
    private OpenFrpControls() {}
    /// Worker-thread service actions may fail without falsely enabling the switch.
    @NotNullByDefault
    public interface Start {
        /// Starts or plans one explicitly selected OpenFrp tunnel.
        void run(OpenFrpAccount.Tunnel tunnel) throws Exception;
    }
    /// Creates a service switch that requests official account authorization before tool installation.
    public static ServerToolSwitch create(IntSupplier port, Start start, Runnable stop) {
        AtomicReference<OpenFrpAccount.@Nullable Tunnel> selection = new AtomicReference<>();
        var toggle = new ServerToolSwitch("内网穿透 · OpenFrp", new ServerToolSwitch.Tool() {
            /// Detects only the managed OpenFrp binary, never the incompatible upstream frpc.
            public boolean available() { return ServerToolInstaller.available(ServerToolInstaller.Tool.OPENFRP); }
            /// Downloads the official OpenFrp client after explicit installation consent.
            public void install(Consumer<String> progress) throws Exception { ServerToolInstaller.install(ServerToolInstaller.Tool.OPENFRP, progress); }
            /// Consumes the in-memory access token selected in the account dialog.
            public void enable() throws Exception { @Nullable var tunnel = selection.getAndSet(null); if (tunnel == null) throw new java.io.IOException("请先登录并选择隧道"); start.run(tunnel); }
            /// Stops only this server's owned helper or cancels its deployment plan.
            public void disable() { selection.set(null); stop.run(); }
        });
        var serviceAction = toggle.getOnAction();
        toggle.setOnAction(event -> {
            if (!toggle.isSelected()) { serviceAction.handle(event); return; }
            toggle.setSelected(false); toggle.setDisable(true);
            try {
                int local = port.getAsInt(); ServerServices.checkPort(local);
                OpenFrpLoginDialog.show(local, tunnel -> { selection.set(tunnel); toggle.setDisable(false); toggle.setSelected(true); serviceAction.handle(new ActionEvent(toggle, toggle)); }, () -> { selection.set(null); toggle.setSelected(false); toggle.setDisable(false); });
            } catch (Exception exception) { toggle.setDisable(false); Controllers.dialog("请先填写有效的服务器端口", "OpenFrp"); }
        });
        return toggle;
    }
}
