package org.mapdb.nativetests;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingOptimizedArrayListTest {
    @Test void optimizedRouteAndFallbackControls() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(executable, "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "-cp", classpath, UniqueGroupingOptimizedArrayListProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "optimized grouping child timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("OPTIMIZED_GROUPING_RESULT passed=19 failed=0"), output);
        } finally { process.destroyForcibly(); }
    }
}
