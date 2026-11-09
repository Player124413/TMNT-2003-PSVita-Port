package org.tmnt.port;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Collects a shareable, app-local startup/crash report. Logcat is tag-filtered (Android also
 * restricts non-root reads to this app's UID); on API 30+ we additionally attach the system's
 * historical exit traces, which survive a native crash in the separate :game process.
 */
final class Diagnostics {
    static final String FILE_NAME = "tmnt-log.txt";
    private static final String[] TAGS = {
        "TMNT:V", "SDL:V", "SDL/APP:V", "AndroidRuntime:V",
        "DEBUG:V", "libc:V", "crash_dump32:V", "crash_dump64:V"
    };
    private static final long MAX_TRACE_BYTES = 1L << 20;
    private Diagnostics() {}

    static List<String> logcatCommand(String buffer) {
        List<String> command = new ArrayList<>();
        command.add("logcat"); command.add("-d"); command.add("-b"); command.add(buffer);
        command.add("-v"); command.add("threadtime"); command.add("-s");
        for (String tag : TAGS) command.add(tag);
        return command;
    }

    static File collect(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "diagnostics");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File target = new File(dir, FILE_NAME), temp = new File(dir, FILE_NAME + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp.toPath())) {
                header(context, out);
                // main carries SDL/TMNT startup logs; crash carries Java and native crash summaries.
                appendLogcat(out, dir, "main", true);
                appendLogcat(out, dir, "crash", false);
                if (Build.VERSION.SDK_INT >= 30) {
                    try { appendExitInfo(context, out); }
                    catch (Exception e) {
                        out.write(("historical exits unavailable: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            if (temp.exists() && !temp.delete()) temp.deleteOnExit();
        }
    }

    private static void header(Context context, OutputStream out) throws IOException {
        String text = String.format(Locale.US,
            "TMNT diagnostics%npackage=%s%nversion=%s (%d)%nabi=%s%nembedded=%s%n" +
            "device=%s %s / Android %s (API %d)%ncollected=%s%n%n",
            context.getPackageName(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            BuildConfig.PORT_ABI, BuildConfig.EMBEDDED_GAME,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            new Date());
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendLogcat(OutputStream out, File dir, String buffer, boolean required) throws Exception {
        out.write(("\n===== logcat -b " + buffer + " =====\n").getBytes(StandardCharsets.UTF_8));
        // Redirect to a file instead of a pipe so a full log cannot deadlock waitFor().
        File captured = new File(dir, "logcat-" + buffer + ".tmp");
        try {
            Process process = new ProcessBuilder(logcatCommand(buffer))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(captured))
                .start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("logcat -b " + buffer + " timed out");
                }
                if (process.exitValue() != 0 && required)
                    throw new IOException("logcat -b " + buffer + " exited with " + process.exitValue());
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
            if (captured.isFile()) Files.copy(captured.toPath(), out);
            if (process.exitValue() != 0)
                out.write(("logcat -b " + buffer + " unavailable on this device\n").getBytes(StandardCharsets.UTF_8));
        } finally {
            if (captured.exists() && !captured.delete()) captured.deleteOnExit();
        }
    }

    @SuppressLint("NewApi") // Caller guards this with Build.VERSION.SDK_INT >= 30.
    private static void appendExitInfo(Context context, OutputStream out) throws IOException {
        out.write("\n===== historical process exits (API 30+) =====\n".getBytes(StandardCharsets.UTF_8));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) { out.write("ActivityManager unavailable\n".getBytes(StandardCharsets.UTF_8)); return; }
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
        if (exits.isEmpty()) { out.write("No historical exits recorded\n".getBytes(StandardCharsets.UTF_8)); return; }
        for (ApplicationExitInfo info : exits) {
            String line = String.format(Locale.US, "process=%s reason=%d status=%d time=%s importance=%d description=%s%n",
                info.getProcessName(), info.getReason(), info.getStatus(), new Date(info.getTimestamp()),
                info.getImportance(), info.getDescription());
            out.write(line.getBytes(StandardCharsets.UTF_8));
            try (InputStream trace = info.getTraceInputStream()) {
                if (trace == null) { out.write("no trace available\n".getBytes(StandardCharsets.UTF_8)); continue; }
                byte[] block = new byte[8192]; long copied = 0; int n;
                while (copied < MAX_TRACE_BYTES && (n = trace.read(block, 0,
                        (int) Math.min(block.length, MAX_TRACE_BYTES - copied))) > 0) {
                    out.write(block, 0, n); copied += n;
                }
                if (copied == MAX_TRACE_BYTES) out.write("\n[trace truncated at 1 MiB]\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                out.write(("trace unavailable: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
