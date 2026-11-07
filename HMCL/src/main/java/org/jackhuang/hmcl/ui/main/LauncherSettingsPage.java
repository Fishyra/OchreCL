// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.main;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.game.GameSettingsPage;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class LauncherSettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("settings")));
    /// Manages tab selection and content transitions independently of the sidebar.
    private final TabController tabController;
    /// The tab for editing game settings presets.
    private final TabItem<GameSettingsPage<GameSettings.Preset>> gameTab;
    /// The tab whose content manages installed Java runtimes.
    private final TabItem<JavaManagementPage> javaManagementTab;
    /// The tab for general launcher settings.
    private final TabItem<SettingsPage> settingsTab;
    /// The tab for launcher appearance settings.
    private final TabItem<PersonalizationPage> personalizationTab;
    /// The tab for download settings.
    private final TabItem<DownloadSettingsPage> downloadTab;
    /// The tab displaying launcher help.
    private final TabItem<HelpPage> helpTab;
    /// The tab displaying launcher information.
    private final TabItem<AboutPage> aboutTab;
    /// The tab displaying feedback and contact information.
    private final TabItem<FeedbackPage> feedbackTab;
    private final TransitionPane transitionPane = new TransitionPane();

    public LauncherSettingsPage() {
        gameTab = new TabItem<>(() -> new GameSettingsPage<>(GameSettings.Preset.class, null));
        javaManagementTab = new TabItem<>(JavaManagementPage::new);
        settingsTab = new TabItem<>(SettingsPage::new);
        personalizationTab = new TabItem<>(PersonalizationPage::new);
        downloadTab = new TabItem<>(DownloadSettingsPage::new);
        helpTab = new TabItem<>(HelpPage::new);
        feedbackTab = new TabItem<>(FeedbackPage::new);
        aboutTab = new TabItem<>(AboutPage::new);
        tabController = new TabController(transitionPane,
                gameTab,
                javaManagementTab,
                settingsTab,
                personalizationTab,
                downloadTab,
                helpTab,
                feedbackTab,
                aboutTab);

        tabController.selectTab(gameTab);
        addEventHandler(Navigator.NavigationEvent.NAVIGATED, event -> gameTab.getNode().loadInstance(HMCLGameInstance.Optional.empty(GameDirectoryManager.getSelectedRepository())));

        javafx.scene.layout.FlowPane tabs = new javafx.scene.layout.FlowPane(8, 8);
        tabs.getStyleClass().add("ochre-section-tabs");
        addSection(tabs, gameTab, i18n("settings.type.global.manage"));
        addSection(tabs, javaManagementTab, i18n("java.management"));
        addSection(tabs, settingsTab, i18n("settings.launcher.general"));
        addSection(tabs, personalizationTab, i18n("settings.launcher.appearance"));
        addSection(tabs, downloadTab, i18n("download"));
        getLeft().setVisible(false);
        getLeft().setManaged(false);
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(12, tabs, transitionPane);
        content.getStyleClass().add("ochre-settings-content");
        javafx.scene.layout.VBox.setVgrow(transitionPane, javafx.scene.layout.Priority.ALWAYS);
        setCenter(content);
    }

    /// Keeps compact wrapping section buttons synchronized with the existing page controller.
    private void addSection(javafx.scene.layout.FlowPane tabs, TabItem<?> tab, String title) {
        com.jfoenix.controls.JFXButton button = new com.jfoenix.controls.JFXButton(title);
        button.getStyleClass().add("ochre-section-tab");
        javafx.css.PseudoClass selected = javafx.css.PseudoClass.getPseudoClass("selected");
        button.pseudoClassStateChanged(selected, tabController.selectedTabProperty().get() == tab);
        tabController.selectedTabProperty().addListener((observable, oldValue, value) ->
                button.pseudoClassStateChanged(selected, value == tab));
        button.setOnAction(event -> tabController.selectTab(tab));
        tabs.getChildren().add(button);
    }

    @Override
    public void onPageShown() {
        tabController.onPageShown();
    }

    @Override
    public void onPageHidden() {
        tabController.onPageHidden();
    }

    public void showGameSettings(HMCLGameRepository repository) {
        gameTab.getNode().loadInstance(HMCLGameInstance.Optional.empty(repository));
        tabController.selectTab(gameTab, false);
    }

    /// Selects the upstream about page, preserving all original attribution and license information.
    public void showAbout() {
        tabController.selectTab(aboutTab, false);
    }

    public void showFeedback() {
        tabController.selectTab(feedbackTab, false);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
