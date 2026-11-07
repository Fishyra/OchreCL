package org.jackhuang.hmcl.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Ensures upstream launcher updates remain disabled even through legacy entry points.
@NotNullByDefault
public final class OchreUpdatePolicyTest {
    /// These calls are safe without a JavaFX event loop, network or filesystem mutation.
    @Test
    public void upstreamUpdateEntryPointsAreInert() {
        UpdateChecker.init();
        UpdateChecker.requestCheckUpdate(UpdateChannel.STABLE, true);
        assertFalse(UpdateChecker.isCheckingUpdate());
        assertFalse(UpdateChecker.isOutdated());
        assertNull(UpdateChecker.getLatestVersion());
        assertNull(UpdateChecker.errorProperty().get());
        assertFalse(UpdateHandler.processArguments(new String[0]));
        assertTrue(UpdateHandler.processArguments(new String[]{"--apply-to", "/does-not-exist"}));
    }
}
