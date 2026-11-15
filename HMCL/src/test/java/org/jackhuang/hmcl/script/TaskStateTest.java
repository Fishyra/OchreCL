package org.jackhuang.hmcl.script;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/// Verifies isolated checkpoint roundtrips and bounded serializable values.
@NotNullByDefault public final class TaskStateTest {
    /// Checkpoints are isolated by instance and reject external paths or host objects before persistence.
    @Test public void isolationAndBounds() throws Exception {
        String key="checkpoint-test-"+UUID.randomUUID();String other=key+"-other";
        try { assertEquals("absent",TaskState.load(key,"stage","absent"));var data=Map.of("stage",2.0,"items",List.of(true,"stone",OchreScript.Empty.NONE));TaskState.save(key,"stage",data);assertEquals(data,TaskState.load(key,"stage",false));assertEquals(false,TaskState.load(other,"stage",false));
            assertThrows(IllegalArgumentException.class,()->TaskState.save(key,"../escape",1.0));assertThrows(IllegalArgumentException.class,()->TaskState.save(key,"invalid",new OchreScript.Quantity(1,"time")));assertThrows(IllegalArgumentException.class,()->TaskState.save(key,"large","x".repeat(20000)));assertTrue(TaskState.clear(key,"stage"));assertFalse(TaskState.clear(key,"stage"));
        } finally { TaskState.clear(key,"stage"); }
    }
}
