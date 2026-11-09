package org.tmnt.port;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DiagnosticsTest {
    @Test public void capturesJavaAndNativeCrashSources() {
        assertEquals("tmnt-log.txt", Diagnostics.FILE_NAME);
        for (String buffer : new String[]{"main", "crash"}) {
            List<String> command = Diagnostics.logcatCommand(buffer);
            assertEquals("logcat", command.get(0));
            assertTrue(command.contains("-d"));
            assertTrue(command.contains("-b"));
            assertEquals(buffer, command.get(command.indexOf("-b") + 1));
            assertTrue(command.contains("-s"));
            for (String tag : new String[]{"TMNT:V", "SDL:V", "SDL/APP:V", "AndroidRuntime:V",
                    "DEBUG:V", "libc:V", "crash_dump32:V", "crash_dump64:V"})
                assertTrue("missing " + tag, command.contains(tag));
        }
    }
}
