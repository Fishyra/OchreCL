package org.jackhuang.hmcl.ui.construct;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.script.OchreScript;
import org.jackhuang.hmcl.script.ScriptPreferences;
import org.jetbrains.annotations.NotNullByDefault;

/// Launcher-wide script execution defaults with explicit, bounded failure handling.
@NotNullByDefault
public final class ScriptSettingsPane extends ScrollPane {
    /// Builds persistent script controls without starting any script.
    public ScriptSettingsPane() {
        VBox content = new VBox(16); content.setPadding(new Insets(22)); content.getStyleClass().addAll("ochre-script-workspace", "ochre-script-settings"); setContent(content); setFitToWidth(true); setHbarPolicy(ScrollBarPolicy.NEVER);
        Label title = new Label("脚本设置"); title.getStyleClass().add("ochre-instance-title");
        ComboBox<OchreScript.FailurePolicy> policy = new ComboBox<>(); policy.getItems().setAll(OchreScript.FailurePolicy.values()); policy.setValue(ScriptPreferences.policy());
        policy.setConverter(new javafx.util.StringConverter<>() {
            /// Maps enum values to stable user-facing labels.
            @Override public String toString(OchreScript.FailurePolicy value) { return switch (value) { case STOP -> "停止脚本（默认）"; case RETRY -> "一直重试当前语句"; case SKIP -> "跳过失败语句"; case RETRY_COUNT -> "重试 script_repeat_count 次"; }; }
            /// The control is not editable; display strings never become executable policy names.
            @Override public OchreScript.FailurePolicy fromString(String value) { return policy.getValue(); }
        });
        Spinner<Integer> count = new Spinner<>(0, 100, ScriptPreferences.repeatCount()); count.setEditable(false); count.disableProperty().bind(policy.valueProperty().isNotEqualTo(OchreScript.FailurePolicy.RETRY_COUNT));
        CheckBox automatic = new com.jfoenix.controls.JFXCheckBox("自动授予运行脚本所需的全部启动器权限"); automatic.setSelected(ScriptPreferences.autoGrant());
        Label description = new Label("关闭自动授权时，点击运行后选择该脚本需要的权限。自动授权不授予服务器 OP、飞行或作弊权限。\n重试次数不含第一次执行；耗尽后停止。重试可能再次触发该语句的动作。权限拒绝和手动停止不会被重试或跳过。\n一直重试每次间隔 250 ms，仍受运行时限和指令预算限制。更改从下一次脚本运行生效。"); description.setWrapText(true);
        Label status = new Label(); Runnable save = () -> { try { ScriptPreferences.save(policy.getValue(), count.getValue(), automatic.isSelected()); status.setText("已保存，下一次运行生效"); } catch (Exception error) { status.setText("保存失败：" + error.getMessage()); } };
        policy.valueProperty().addListener((observable, before, after) -> save.run()); count.valueProperty().addListener((observable, before, after) -> save.run()); automatic.selectedProperty().addListener((observable, before, after) -> save.run());
        content.getChildren().setAll(title, new Label("语句执行失败时"), policy, new Label("script_repeat_count（额外重试次数）"), count, automatic, description, status);
    }
}
