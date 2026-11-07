// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.main;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.geometry.Pos;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.ui.nbt.NBTEditorPage;
import org.jackhuang.hmcl.ui.nbt.NBTFileType;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.StringUtils;
import java.nio.file.Path;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Empty home canvas; the persistent Øchre shell owns navigation and launch controls.
@NotNullByDefault
public final class RootPage extends StackPane implements DecoratorPage {
    /// Stable home title and navigation state.
    private final ReadOnlyObjectWrapper<State> state;
    /// Compatibility model used by update and multiplayer integrations.
    private @Nullable MainPage mainPage;

    /// Creates the intentionally empty home canvas and compact application title.
    public RootPage() {
        ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-title.png"));
        Label name = new Label(Metadata.NAME);
        name.getStyleClass().add("ochre-brand");
        HBox title = new HBox(10, icon, name);
        title.setAlignment(Pos.CENTER_LEFT);
        state = new ReadOnlyObjectWrapper<>(new State(null, title, false, false, true));
        getStyleClass().add("ochre-home");
        installDropSupport();
    }

    /// Preserves upstream file-drop import and NBT inspection on the empty home canvas.
    private void installDropSupport() {
        FXUtils.applyDragListener(this,
                file -> ModpackHelper.isFileModpackByExtension(file) || NBTFileType.isNBTFileByExtension(file) || "json".equalsIgnoreCase(FileUtils.getExtension(file)),
                modpacks -> {
                    Path file = modpacks.get(0);
                    if (ModpackHelper.isFileModpackByExtension(file)) {
                        Controllers.getDecorator().startWizard(
                                new ModpackInstallWizardProvider(GameDirectoryManager.getSelectedRepository(), file),
                                i18n("install.modpack"));
                    } else if (NBTFileType.isNBTFileByExtension(file)) {
                        try {
                            Controllers.navigate(new NBTEditorPage(file));
                        } catch (Throwable e) {
                            LOG.warning("Fail to open nbt file", e);
                            Controllers.dialog(i18n("nbt.open.failed") + "\n\n" + StringUtils.getStackTrace(e),
                                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        }
                    } else if ("json".equalsIgnoreCase(FileUtils.getExtension(file))) {
                        Instances.installFromJson(GameDirectoryManager.getSelectedRepository(), file);
                    }
                });

    }

    /// Returns the application title state without adding content to the home canvas.
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Retains the upstream current-instance and update bindings for existing integrations.
    public MainPage getMainPage() {
        if (mainPage == null) {
            MainPage model = new MainPage(true);
            FXUtils.onChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), model::setCurrentGame);
            model.latestVersionProperty().bind(UpdateChecker.latestVersionProperty());
            mainPage = model;
        }
        return mainPage;
    }
}
