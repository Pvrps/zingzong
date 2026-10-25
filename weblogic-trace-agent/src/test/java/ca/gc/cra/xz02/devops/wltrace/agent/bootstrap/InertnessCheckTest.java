package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import ca.gc.cra.xz02.devops.wltrace.agent.instrument.AdviceBinding;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.Hook;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the agent stays inert outside WebLogic and when a required hook's targets are missing, and records when they are all present. */
class InertnessCheckTest {

    private static final Hook PRESENT = hook("present", false, "java.lang.String");
    private static final Hook MISSING = hook("missing", false, "weblogic.nowhere.Absent");
    private static final Hook MISSING_OPTIONAL = hook("missing optional", true, "weblogic.nowhere.Absent");

    @Test
    void inertOutsideWebLogic() {
        Optional<String> reason = InertnessCheck.reason(Collections.singletonList(PRESENT));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().startsWith("not a WebLogic server"), reason.get());
    }

    @Test
    void activeOnWebLogicWithEveryRequiredTargetPresent() {
        withWebLogicHome(() -> assertFalse(InertnessCheck.reason(Arrays.asList(PRESENT, MISSING_OPTIONAL)).isPresent(),
                "an optional hook's missing targets do not make the agent inert"));
    }

    @Test
    void inertOnWebLogicWhenARequiredTargetIsMissing() {
        withWebLogicHome(() -> {
            Optional<String> reason = InertnessCheck.reason(Arrays.asList(PRESENT, MISSING));
            assertTrue(reason.isPresent());
            assertEquals("WebLogic without the expected container classes [weblogic.nowhere.Absent]", reason.get());
        });
    }

    private static void withWebLogicHome(Runnable check) {
        System.setProperty("weblogic.home", "/opt/wls");
        try {
            check.run();
        } finally {
            System.clearProperty("weblogic.home");
        }
    }

    private static Hook hook(String name, boolean optional, String... targets) {
        return new Hook(name, null) {
            @Override
            public List<String> targetClassNames() {
                return Arrays.asList(targets);
            }

            @Override
            public List<AdviceBinding> bindings() {
                return Collections.emptyList();
            }

            @Override
            public boolean optional() {
                return optional;
            }
        };
    }
}
