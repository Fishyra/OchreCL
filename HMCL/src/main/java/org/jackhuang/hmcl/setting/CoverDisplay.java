package org.jackhuang.hmcl.setting;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import java.io.IOException;
import java.nio.file.*;

/// Shared persisted home artwork choice, used by onboarding and the inspector context menu.
@NotNullByDefault
public final class CoverDisplay {
    /// Read-only outside this class so failed persistence cannot leave the UI with a fictitious choice.
    private static final ReadOnlyBooleanWrapper CLOCK = new ReadOnlyBooleanWrapper(load());
    /// Static preference adapter.
    private CoverDisplay() { }
    /// Missing preferences preserve the existing cover default.
    private static boolean load(){try{return Files.readString(Metadata.HMCL_LOCAL_HOME.resolve("cover-display.txt")).strip().equals("clock");}catch(IOException ignored){return false;}}
    /// Current artwork mode.
    public static boolean isClock(){return CLOCK.get();}
    /// Observers update immediately when either entry point changes the saved choice.
    public static ReadOnlyBooleanProperty clockProperty(){return CLOCK.getReadOnlyProperty();}
    /// Saves atomically before notifying UI listeners; errors preserve the old choice.
    public static void setClock(boolean clock)throws IOException {Path file=Metadata.HMCL_LOCAL_HOME.resolve("cover-display.txt");Files.createDirectories(file.getParent());Path pending=Files.createTempFile(file.getParent(),"cover-display-",".tmp");try{Files.writeString(pending,clock?"clock":"cover");Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);CLOCK.set(clock);}finally{Files.deleteIfExists(pending);}}
}
