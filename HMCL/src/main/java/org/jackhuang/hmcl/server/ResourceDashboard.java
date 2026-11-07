package org.jackhuang.hmcl.server;

import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.ArrayDeque;

/// Four theme-aware measured resource cards with a bounded thirty-second history and clear unavailable states.
@NotNullByDefault
public final class ResourceDashboard extends VBox {
    /// System-wide CPU chart.
    private final MetricCard cpu = new MetricCard("系统 CPU");
    /// Physical system memory chart.
    private final MetricCard memory = new MetricCard("系统内存");
    /// Managed child-process CPU chart.
    private final MetricCard processCpu = new MetricCard("进程 CPU");
    /// Actual process resident memory chart.
    private final MetricCard resident = new MetricCard("进程内存");
    /// Lifecycle and duration remain visible above the metric cards.
    private final Label duration = new Label("等待进程启动");
    /// Responsive metric grid, four columns on broad panels and two on compact panels.
    private final GridPane grid = new GridPane();
    /// Current column count; width animations must not rebuild the grid on every frame.
    private int gridColumns;
    /// Last displayed process identity, used to separate different runs' history.
    private long processId;

    /// Creates a two-column responsive grid that does not force a wide parent minimum.
    public ResourceDashboard() {
        super(8);
        getStyleClass().add("ochre-resource-dashboard");
        grid.setHgap(10); grid.setVgap(10);
        widthProperty().addListener((observable, previous, width) -> arrange(width.doubleValue()));
        arrange(0);
        getChildren().setAll(duration, grid);
    }
    /// Reflows cards without changing their data or starting new samplers.
    private void arrange(double width) {
        int columns = width >= 680 ? 4 : 2;
        if (gridColumns == columns) return;
        gridColumns = columns;
        grid.getColumnConstraints().clear(); grid.getChildren().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(100.0 / columns); column.setMinWidth(0); grid.getColumnConstraints().add(column);
        }
        grid.add(cpu, 0, 0); grid.add(memory, 1, 0); grid.add(processCpu, 2 % columns, 2 / columns); grid.add(resident, 3 % columns, 3 / columns);
    }
    /// Displays an actual sample, never interpolating a missing measurement as zero.
    public void update(RuntimeSession.Snapshot sample) {
        if (sample.processId() != processId) {
            processId = sample.processId();
            cpu.chart.clear(); memory.chart.clear(); processCpu.chart.clear(); resident.chart.clear();
        }
        duration.setText((sample.running() ? "运行中  ·  " : "已停止  ·  上次运行 ") + RuntimeSession.elapsed(sample.uptimeSeconds()));
        cpu.update(RuntimeSession.percent(sample.systemCpu()), "整台电脑 · 最近 30 秒", sample.systemCpu() / 100);
        double ratio = sample.totalMemory() > 0 && sample.usedMemory() >= 0 ? sample.usedMemory() * 1.0 / sample.totalMemory() : Double.NaN;
        memory.update(RuntimeSession.bytes(sample.usedMemory()), "共 " + RuntimeSession.bytes(sample.totalMemory())
                + (Double.isFinite(ratio) ? String.format(" · %.0f%%", ratio * 100) : ""), ratio);
        processCpu.update(sample.running() ? RuntimeSession.percent(sample.processCpu()) : "未运行", "单核 100% · 图表按整机",
                sample.processCpu() / (100 * Runtime.getRuntime().availableProcessors()));
        resident.update(sample.running() ? RuntimeSession.bytes(sample.residentBytes()) : "未运行", "驻留物理内存 · 非 Java 堆上限",
                sample.residentBytes() >= 0 && sample.totalMemory() > 0 ? sample.residentBytes() * 1.0 / sample.totalMemory() : Double.NaN);
    }
    /// One metric's heading, measured value, explanatory unit and local history.
    @NotNullByDefault
    private static final class MetricCard extends VBox {
        /// Primary selectable-by-copy exported value.
        private final Label value = new Label("—");
        /// Unit and measurement scope.
        private final Label detail = new Label();
        /// Lightweight trend path.
        private final Sparkline chart = new Sparkline();
        /// Builds a rounded product surface with no chart library or animation timer.
        private MetricCard(String title) {
            super(3); setMinWidth(0); getStyleClass().add("ochre-metric-card");
            Label heading = new Label(title); heading.getStyleClass().add("ochre-metric-heading");
            value.getStyleClass().add("ochre-metric-value"); detail.getStyleClass().add("ochre-metric-detail");
            value.setMinWidth(0); detail.setMinWidth(0); detail.setWrapText(true);
            getChildren().setAll(heading, value, detail, chart);
            Tooltip.install(chart, new Tooltip("每 2 秒采样，最多显示最近 30 秒；缺失数据留空"));
        }
        /// Replaces text and appends one measured history value.
        private void update(String text, String subtitle, double fraction) { value.setText(text); detail.setText(subtitle); chart.add(fraction); }
    }
    /// A bounded vector history whose size never influences its parent's preferred width.
    @NotNullByDefault
    private static final class Sparkline extends Region {
        /// Thirty seconds at the shared two-second sampling cadence.
        private final ArrayDeque<Double> history = new ArrayDeque<>();
        /// Low-emphasis graph baseline.
        private final Line baseline = new Line();
        /// Theme-colored path; gaps in measurements split the line.
        private final Path path = new Path();
        /// Shows the most recent valid sample even before the history contains a line segment.
        private final Circle point = new Circle(2);
        /// Installs unmanaged vectors so resizing cannot create a layout feedback loop.
        private Sparkline() {
            setMinSize(0, 25); setPrefSize(100, 25); setMaxWidth(Double.MAX_VALUE);
            path.setManaged(false); baseline.setManaged(false); point.setManaged(false);
            point.getStyleClass().add("ochre-metric-point");
            path.getStyleClass().add("ochre-metric-line"); baseline.getStyleClass().add("ochre-metric-baseline");
            getChildren().setAll(baseline, path, point);
        }
        /// Appends at most one real sample, retaining no more than sixteen points.
        private void add(double value) { history.addLast(value); while (history.size() > 16) history.removeFirst(); requestLayout(); }
        /// Starts a fresh history when the observed child process changes.
        private void clear() { history.clear(); path.getElements().clear(); point.setVisible(false); }
        /// Reflows existing points to the available width without resampling or distorting the time axis.
        @Override protected void layoutChildren() {
            double width = getWidth(), height = Math.max(1, getHeight() - 4);
            baseline.setStartX(0); baseline.setEndX(width); baseline.setStartY(height); baseline.setEndY(height);
            path.getElements().clear();
            int index = 16 - history.size(); boolean segment = false; point.setVisible(false);
            for (double value : history) {
                double x = width * index++ / 15;
                if (!Double.isFinite(value)) { segment = false; point.setVisible(false); continue; }
                double y = height - Math.max(0, Math.min(1, value)) * (height - 2);
                path.getElements().add(segment ? new LineTo(x, y) : new MoveTo(x, y)); segment = true;
                point.setCenterX(x); point.setCenterY(y); point.setVisible(true);
            }
        }
    }
}
