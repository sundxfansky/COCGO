package com.coc.suncode.core.system.local;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-owned root worker; stdin and stdout are a private, serial JSON protocol. */
public final class RootActionProcess {
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_SHELL_BYTES = 1024 * 1024;
    private static final long MAX_SHELL_MILLIS = 25_000;

    private final Object lifecycleLock = new Object();
    private final Object outputLock = new Object();
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "local-root-action");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private ShellSession activeShell;
    private List<String> sessionLauncher;

    private RootActionProcess() {}

    public static void main(String[] args) {
        RootActionProcess worker = new RootActionProcess();
        try {
            // Root authorization belongs to the host app, never to an externally reachable socket.
            if (Os.getuid() != 0) {
                throw new ActionFailure("ROOT_REQUIRED", "Local actions require root authorization");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "local-root-shutdown"));
            System.out.write("SUN_ACTIONS_READY\t1\n".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            BufferedInputStream input = new BufferedInputStream(System.in, 16 * 1024);
            String line;
            // Reading remains independent of execution so EOF immediately cancels a running shell.
            while (!worker.closed && (line = readLine(input)) != null) {
                worker.dispatch(line);
            }
        } catch (Exception error) {
            try {
                worker.reply(failure(error));
            } catch (Exception ignored) {
                // A closed host pipe must not prevent child process cleanup.
            }
        } finally {
            worker.close();
        }
    }

    private void dispatch(String line) throws IOException {
        commands.execute(() -> {
            JSONObject response;
            try {
                checkOpen();
                response = handle(new JSONObject(line));
            } catch (Exception error) {
                response = failure(error);
            }
            try {
                reply(response);
            } catch (IOException error) {
                close();
            }
        });
    }

    private JSONObject handle(JSONObject request) throws Exception {
        String action = requiredString(request, "actionType");
        switch (action) {
            case "ping":
                return success("0").put("uid", Os.getuid());
            case "file_action":
                return fileAction(request);
            case "shell_action":
                return shellAction(request);
            default:
                throw new ActionFailure("INVALID_REQUEST", "Unknown actionType: " + action);
        }
    }

    private JSONObject fileAction(JSONObject request) throws Exception {
        String action = requiredString(request, "subAction");
        File file = absoluteFile(requiredString(request, "path"));
        switch (action) {
            case "read":
                requireRegular(file);
                try (InputStream input = new FileInputStream(file)) {
                    return success(new String(readLimited(input, MAX_FILE_BYTES), StandardCharsets.UTF_8));
                }
            case "write":
                byte[] content = requiredString(request, "content").getBytes(StandardCharsets.UTF_8);
                if (content.length > MAX_FILE_BYTES) {
                    throw new ActionFailure("FILE_LIMIT", "File content exceeded 8 MiB");
                }
                atomicReplace(file, output -> output.write(content));
                return success(Integer.toString(content.length));
            case "create":
                ensureParent(file);
                checkOpen();
                if (!file.createNewFile()) throw new IOException("File already exists: " + file);
                return success("File created successfully");
            case "delete":
                requireExisting(file);
                checkOpen();
                // File.delete also permits empty directories, matching the previous file API.
                if (!file.delete()) throw new IOException("Cannot delete file: " + file);
                return success("File deleted successfully");
            case "check_exists":
                return success(Boolean.toString(statIfExists(file) != null));
            case "copy":
                requireRegular(file);
                File destination = absoluteFile(requiredString(request, "destPath"));
                atomicReplace(destination, output -> {
                    try (InputStream input = new FileInputStream(file)) {
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            checkOpen();
                            output.write(buffer, 0, count);
                        }
                    }
                });
                return success("true");
            case "rename":
                requireExisting(file);
                File renamed = absoluteFile(requiredString(request, "newPath"));
                if (statIfExists(renamed) != null) throw new IOException("Destination file already exists: " + renamed);
                ensureParent(renamed);
                checkOpen();
                if (!file.renameTo(renamed)) throw new IOException("Cannot rename file: " + file);
                return success("true");
            default:
                throw new ActionFailure("INVALID_REQUEST", "Unknown file sub-action: " + action);
        }
    }

    private void atomicReplace(File target, FileWriter writer) throws Exception {
        ensureParent(target);
        if (target.isDirectory()) throw new IOException("Destination is a directory: " + target);
        StructStat existing = statIfExists(target);
        File temporary = File.createTempFile("sun-local-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                writer.write(output);
                checkOpen();
                // Preserve existing ownership and permissions when replacing another app's file.
                if (existing != null) {
                    Os.fchown(output.getFD(), existing.st_uid, existing.st_gid);
                    Os.fchmod(output.getFD(), existing.st_mode & 07777);
                }
                output.getFD().sync();
            }
            checkOpen();
            // Same-directory rename commits complete content and preserves the old target on failure.
            Os.rename(temporary.getPath(), target.getPath());
        } finally {
            temporary.delete();
        }
    }

    private JSONObject shellAction(JSONObject request) throws Exception {
        String command = requiredString(request, "command");
        long timeoutMillis = request.has("timeoutMs") ? request.getLong("timeoutMs") : MAX_SHELL_MILLIS;
        if (timeoutMillis <= 0 || timeoutMillis > MAX_SHELL_MILLIS) {
            throw new ActionFailure("INVALID_REQUEST", "timeoutMs must be between 1 and 25000");
        }
        List<String> arguments = new ArrayList<>(findSessionLauncher());
        arguments.add("sh");
        arguments.add("-c");
        arguments.add(command);
        checkOpen();
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ShellSession session;
        try {
            session = new ShellSession(process, childPid(process));
        } catch (Exception error) {
            process.destroy();
            throw error;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                session.terminate();
                throw new ActionFailure("CANCELLED", "Local action worker is closed");
            }
            activeShell = session;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            InputStream input = process.getInputStream();
            while (true) {
                checkOpen();
                if (System.nanoTime() >= deadline) {
                    throw new ActionFailure("SHELL_TIMEOUT", "Command timed out");
                }
                int ready = input.available();
                if (ready > 0) {
                    int count = input.read(buffer, 0, Math.min(ready, buffer.length));
                    if (count < 0) break;
                    if (output.size() + count > MAX_SHELL_BYTES) {
                        throw new ActionFailure("SHELL_OUTPUT_LIMIT", "Command output exceeded 1 MiB");
                    }
                    output.write(buffer, 0, count);
                    continue;
                }
                // Recheck after observing exit: its final write may race the earlier available call.
                if (hasExited(process) && input.available() == 0) break;
                Thread.sleep(5);
            }
            return success(new String(output.toByteArray(), StandardCharsets.UTF_8))
                    .put("exitCode", process.waitFor());
        } finally {
            // This also kills background descendants that keep stdout open after the shell exits.
            session.terminate();
            synchronized (lifecycleLock) {
                if (activeShell == session) activeShell = null;
            }
        }
    }

    private List<String> findSessionLauncher() throws Exception {
        if (sessionLauncher != null) return sessionLauncher;
        for (String path : new String[]{"/system/bin/setsid", "/system/xbin/setsid", "/sbin/setsid"}) {
            if (new File(path).canExecute()) {
                sessionLauncher = new ArrayList<>();
                sessionLauncher.add(path);
                return sessionLauncher;
            }
        }
        // Some Android builds ship the applet without its standalone symlink.
        File toybox = new File("/system/bin/toybox");
        if (toybox.canExecute()) {
            Process probe = new ProcessBuilder(toybox.getPath(), "setsid", "--help")
                    .redirectErrorStream(true).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            try {
                while (!hasExited(probe) && System.nanoTime() < deadline) {
                    checkOpen();
                    if (probe.getInputStream().available() > 0) probe.getInputStream().read(new byte[4096]);
                    Thread.sleep(5);
                }
                if (hasExited(probe) && probe.exitValue() == 0) {
                    sessionLauncher = new ArrayList<>();
                    sessionLauncher.add(toybox.getPath());
                    sessionLauncher.add("setsid");
                    return sessionLauncher;
                }
            } finally {
                probe.destroy();
            }
        }
        throw new ActionFailure("SHELL_UNAVAILABLE", "Android setsid is unavailable");
    }

    private void checkOpen() throws ActionFailure {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new ActionFailure("CANCELLED", "Local action was cancelled");
        }
    }

    private void close() {
        ShellSession shell;
        synchronized (lifecycleLock) {
            closed = true;
            shell = activeShell;
            activeShell = null;
        }
        if (shell != null) shell.terminate();
        commands.shutdownNow();
    }

    private void reply(JSONObject response) throws IOException {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LINE_BYTES) {
            bytes = failure(new ActionFailure("RESPONSE_LIMIT", "Response exceeded the IPC limit"))
                    .toString().getBytes(StandardCharsets.UTF_8);
        }
        synchronized (outputLock) {
            if (closed) return;
            System.out.write(bytes);
            System.out.write('\n');
            System.out.flush();
            if (System.out.checkError()) throw new IOException("Host response pipe is closed");
        }
    }

    private byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkOpen();
            if (output.size() + count > limit) {
                throw new ActionFailure("FILE_LIMIT", "File content exceeded 8 MiB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') break;
            if (line.size() >= MAX_LINE_BYTES) {
                throw new ActionFailure("REQUEST_LIMIT", "Request exceeded the IPC limit");
            }
            line.write(next);
        }
        if (next == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private static File absoluteFile(String path) throws ActionFailure {
        File file = new File(path);
        if (!file.isAbsolute() || path.indexOf('\0') >= 0) {
            throw new ActionFailure("INVALID_REQUEST", "An absolute file path is required");
        }
        return file;
    }

    private static void requireExisting(File file) throws Exception {
        if (statIfExists(file) == null) throw new ActionFailure("NOT_FOUND", "File not found: " + file);
    }

    private static void requireRegular(File file) throws Exception {
        StructStat stat = statIfExists(file);
        if (stat == null) throw new ActionFailure("NOT_FOUND", "File not found: " + file);
        if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Not a regular file: " + file);
    }

    private static StructStat statIfExists(File file) throws ErrnoException {
        try {
            return Os.stat(file.getPath());
        } catch (ErrnoException error) {
            // Permission and storage failures must never be mistaken for a missing configuration.
            if (error.errno == OsConstants.ENOENT) return null;
            throw error;
        }
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Cannot create parent directory: " + parent);
        }
    }

    private static String requiredString(JSONObject request, String key) throws ActionFailure {
        Object value = request.opt(key);
        if (!(value instanceof String)) {
            throw new ActionFailure("INVALID_REQUEST", "Missing or invalid " + key);
        }
        return (String) value;
    }

    private static boolean hasExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException running) {
            return false;
        }
    }

    private static int childPid(Process process) throws Exception {
        try {
            Method pid = Process.class.getMethod("pid");
            return ((Number) pid.invoke(process)).intValue();
        } catch (NoSuchMethodException unavailable) {
            // Android versions before Process.pid expose the native child identifier as a field.
            for (Class<?> type = process.getClass(); type != null; type = type.getSuperclass()) {
                for (String name : new String[]{"pid", "mPid"}) {
                    try {
                        Field field = type.getDeclaredField(name);
                        field.setAccessible(true);
                        return ((Number) field.get(process)).intValue();
                    } catch (NoSuchFieldException ignored) {
                        // Vendor process implementations use different field names.
                    }
                }
            }
            throw new IOException("Cannot identify shell process for cancellation");
        }
    }

    private static JSONObject success(String data) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "success");
            response.put("data", data);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return response;
    }

    private static JSONObject failure(Exception error) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "error");
            response.put("code", error instanceof ActionFailure
                    ? ((ActionFailure) error).code
                    : error instanceof JSONException ? "INVALID_REQUEST" : "FILE_ERROR");
            String message = error.getMessage();
            response.put("message", message == null ? error.getClass().getSimpleName() : message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return response;
    }

    private interface FileWriter {
        void write(OutputStream output) throws Exception;
    }

    private static final class ActionFailure extends IOException {
        final String code;

        ActionFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class ShellSession {
        final Process process;
        final int pid;
        private final AtomicBoolean terminated = new AtomicBoolean();

        ShellSession(Process process, int pid) throws IOException {
            if (pid <= 1 || pid == Os.getpid() || pid == workerProcessGroup()) {
                throw new IOException("Invalid shell process identifier");
            }
            this.process = process;
            this.pid = pid;
        }

        void terminate() {
            if (!terminated.compareAndSet(false, true)) return;
            // setsid makes the child PID its process-group ID. Never signal the worker's own group.
            kill(-pid);
            kill(pid);
            // Repeat after killing the launcher to cover cancellation before its setsid call.
            kill(-pid);
            process.destroy();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }

        private static void kill(int pid) {
            try {
                Os.kill(pid, OsConstants.SIGKILL);
            } catch (ErrnoException ignored) {
                // An exited process or an empty process group already satisfies cancellation.
            }
        }

        private static int workerProcessGroup() throws IOException {
            try (InputStream input = new FileInputStream("/proc/self/stat")) {
                byte[] bytes = new byte[8192];
                int count = input.read(bytes);
                if (count <= 0) throw new IOException("Cannot inspect worker process group");
                String stat = new String(bytes, 0, count, StandardCharsets.UTF_8);
                // The comm field can contain spaces and parentheses; fields after its final ')' are fixed.
                String[] fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+");
                if (fields.length < 3) throw new IOException("Invalid worker process stat");
                return Integer.parseInt(fields[2]);
            }
        }
    }
}
