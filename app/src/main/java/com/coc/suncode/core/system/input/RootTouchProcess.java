package com.coc.suncode.core.system.input;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Root-only app_process entry point; stdout is reserved for the line protocol. */
public final class RootTouchProcess {
    private static final int MAX_POINTERS = 10;
    private static final int WAIT_FOR_RESULT = 1;

    private final Object inputManager;
    private final Method injectInputEvent;
    private final boolean targetedInjection;
    private final Object activityManager;
    private final Method focusedTask;
    private final Method setDisplayId;
    private final LinkedHashMap<Integer, Pointer> pointers = new LinkedHashMap<>();

    private String targetPackage;
    private int targetUid = -1;
    private int gestureUid = -1;
    private int gestureDisplayId;
    private long downTime;

    private RootTouchProcess() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                .invoke(null, "input");
        if (binder == null) throw new IllegalStateException("Input service is unavailable");
        Class<?> inputStub = Class.forName("android.hardware.input.IInputManager$Stub");
        inputManager = inputStub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> inputInterface = Class.forName("android.hardware.input.IInputManager");
        targetedInjection = Build.VERSION.SDK_INT >= 33;
        // New Android versions must never silently fall back to unrestricted injection.
        injectInputEvent = targetedInjection
                ? inputInterface.getMethod("injectInputEventToTarget",
                        InputEvent.class, int.class, int.class)
                : inputInterface.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayId = Build.VERSION.SDK_INT >= 29
                ? InputEvent.class.getMethod("setDisplayId", int.class) : null;

        Object manager = null;
        Method focus = null;
        Exception lastError = null;
        String[][] providers = {
                {"android.app.ActivityTaskManager", "getService", "android.app.IActivityTaskManager"},
                {"android.app.ActivityManager", "getService", "android.app.IActivityManager"},
                {"android.app.ActivityManagerNative", "getDefault", "android.app.IActivityManager"}
        };
        for (String[] provider : providers) {
            try {
                Object candidate = Class.forName(provider[0]).getMethod(provider[1]).invoke(null);
                Class<?> managerInterface = Class.forName(provider[2]);
                for (String methodName : new String[]{"getFocusedRootTaskInfo", "getFocusedStackInfo"}) {
                    try {
                        // Public interfaces avoid reflective access to private Binder proxy classes.
                        focus = managerInterface.getMethod(methodName);
                        manager = candidate;
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // The focused-task API was renamed across Android releases.
                    }
                }
                if (focus != null) break;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (focus == null) {
            throw new IllegalStateException("Focused task API is unavailable", lastError);
        }
        activityManager = manager;
        focusedTask = focus;
    }

    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        RootTouchProcess worker = null;
        try {
            worker = new RootTouchProcess();
            if (args.length == 1 && "--probe".equals(args[0])) {
                output.println("SUN_TOUCH_PROBE\t" + Build.VERSION.SDK_INT
                        + "\tTARGETED=" + worker.targetedInjection
                        + "\tFOREGROUND=" + worker.focusedTask.getName());
                return;
            }
            output.println("SUN_TOUCH_READY\t1\t" + Build.VERSION.SDK_INT);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    boolean quit = worker.handle(line);
                    output.println("OK");
                    if (quit) break;
                } catch (Exception error) {
                    worker.cancelQuietly();
                    output.println("ERR\t" + errorMessage(error));
                }
            }
        } catch (Exception error) {
            output.println("ERR\t" + errorMessage(error));
        } finally {
            if (worker != null) worker.cancelQuietly();
        }
    }

    private boolean handle(String line) throws Exception {
        String[] fields = line.split("\t", -1);
        switch (fields[0]) {
            case "TARGET":
                requireFields(fields, 3);
                String packageName = fields[1];
                int uid = Integer.parseInt(fields[2]);
                if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || uid < 0) {
                    throw new IllegalArgumentException("Invalid target package or UID");
                }
                cancel();
                targetPackage = packageName;
                targetUid = uid;
                return false;
            case "DOWN":
                requireFields(fields, 4);
                down(Integer.parseInt(fields[1]), coordinate(fields[2]), coordinate(fields[3]));
                return false;
            case "MOVE":
                requireFields(fields, 4);
                move(Integer.parseInt(fields[1]), coordinate(fields[2]), coordinate(fields[3]));
                return false;
            case "UP":
                requireFields(fields, 2);
                up(Integer.parseInt(fields[1]));
                return false;
            case "CANCEL":
                requireFields(fields, 1);
                cancel();
                return false;
            case "QUIT":
                requireFields(fields, 1);
                cancel();
                return true;
            default:
                throw new IllegalArgumentException("Unknown touch command");
        }
    }

    private void down(int id, float x, float y) throws Exception {
        if (id < 0 || pointers.containsKey(id)) {
            throw new IllegalArgumentException("Pointer is already down or has an invalid ID");
        }
        if (pointers.size() >= MAX_POINTERS) {
            throw new IllegalStateException("Too many active pointers");
        }
        int displayId = verifyForeground();
        if (pointers.isEmpty()) {
            downTime = SystemClock.uptimeMillis();
            gestureUid = targetUid;
            gestureDisplayId = displayId;
        } else if (displayId != gestureDisplayId || gestureUid != targetUid) {
            throw new IllegalStateException("Target changed during the gesture");
        }
        Pointer pointer = new Pointer(allocateLocalId(), x, y);
        pointers.put(id, pointer);
        int action = pointers.size() == 1 ? MotionEvent.ACTION_DOWN
                : MotionEvent.ACTION_POINTER_DOWN
                | ((pointers.size() - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        inject(action);
    }

    private void move(int id, float x, float y) throws Exception {
        Pointer pointer = requirePointer(id);
        verifyGestureForeground();
        pointer.x = x;
        pointer.y = y;
        inject(MotionEvent.ACTION_MOVE);
    }

    private void up(int id) throws Exception {
        Pointer pointer = requirePointer(id);
        pointer.pressure = 0f;
        int action = pointers.size() == 1 ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_POINTER_UP
                | (indexOf(id) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        // By the time UP runs, DOWN already landed on the target window, so the tap's intended
        // effect (e.g. opening a new screen) has already happened. If that same tap caused the
        // window to be replaced before UP is delivered -- the foreground check fails, or the
        // system still rejects injection after retrying through the transition -- there is no
        // longer a live window to release the pointer on. Treat that as gesture-already-done
        // rather than a failure: surfacing it as an error would pause automation after a tap that
        // actually succeeded, which previously made attacking the next opponent and depositing
        // troops after it fail outright on screens that transition on DOWN rather than on UP.
        try {
            verifyGestureForeground();
            inject(action);
        } catch (IllegalStateException notForegroundOrRejected) {
            System.err.println("UP for pointer " + id + " skipped after its target window changed: "
                    + notForegroundOrRejected.getMessage());
        }
        pointers.remove(id);
        if (pointers.isEmpty()) clearGesture();
    }

    private int verifyForeground() throws Exception {
        if (targetPackage == null || targetUid < 0) {
            throw new IllegalStateException("Target application is not configured");
        }
        Object info = focusedTask.invoke(activityManager);
        if (info == null) throw new IllegalStateException("No focused task");
        Object component = readField(info, "topActivity");
        if (!(component instanceof ComponentName)
                || !targetPackage.equals(((ComponentName) component).getPackageName())) {
            throw new IllegalStateException("Target application is not in the foreground");
        }
        // Some vendor dispatchers ignore the requested UID; verify ownership before injection.
        if (foregroundUid(info) != targetUid) {
            throw new IllegalStateException("Target UID does not match the foreground application");
        }
        int displayId = ((Number) readField(info, "displayId")).intValue();
        if (displayId != 0) {
            throw new IllegalStateException("Only the default display is supported");
        }
        return displayId;
    }

    private int foregroundUid(Object focusedInfo) throws Exception {
        try {
            Object activity = readField(focusedInfo, "topActivityInfo");
            if (activity instanceof ActivityInfo) {
                ActivityInfo activityInfo = (ActivityInfo) activity;
                if (activityInfo.applicationInfo != null) {
                    return activityInfo.applicationInfo.uid;
                }
            }
        } catch (NoSuchFieldException ignored) {
            // Older StackInfo exposes the package and user but has no top ActivityInfo.
        }

        int userId = targetUid / 100000;
        try {
            int focusedUserId = ((Number) readField(focusedInfo, "userId")).intValue();
            if (focusedUserId != userId) {
                throw new IllegalStateException("Target user does not own the foreground task");
            }
            userId = focusedUserId;
        } catch (NoSuchFieldException ignored) {
            // Legacy task info requires resolving the explicitly selected target user.
        }
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                .invoke(null, "package");
        if (binder == null) throw new IllegalStateException("Package service is unavailable");
        Class<?> packageStub = Class.forName("android.content.pm.IPackageManager$Stub");
        Object packageManager = packageStub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> packageInterface = Class.forName("android.content.pm.IPackageManager");
        Object uid;
        try {
            Method getPackageUid = packageInterface.getMethod("getPackageUid",
                    String.class, long.class, int.class);
            uid = getPackageUid.invoke(packageManager, targetPackage, 0L, userId);
        } catch (NoSuchMethodException ignored) {
            Method getPackageUid = packageInterface.getMethod("getPackageUid",
                    String.class, int.class, int.class);
            uid = getPackageUid.invoke(packageManager, targetPackage, 0, userId);
        }
        int resolvedUid = ((Number) uid).intValue();
        if (resolvedUid < 0) throw new IllegalStateException("Cannot resolve the foreground UID");
        return resolvedUid;
    }

    private void verifyGestureForeground() throws Exception {
        if (gestureUid != targetUid || verifyForeground() != gestureDisplayId) {
            throw new IllegalStateException("Gesture target is no longer active");
        }
    }

    // Rejection is a transient focus race: the target window briefly loses/regains focus during
    // a screen transition (e.g. right after a tap that opens a new page), and the underlying
    // transition animation can run for several hundred ms, not just a few. Retrying with
    // increasing backoff rides out that window; it's still capped so a genuinely stuck
    // foreground surfaces as a failure instead of hanging here indefinitely.
    private static final long[] INJECT_RETRY_DELAYS_MS = {50, 100, 200, 400};

    private void inject(int action) throws Exception {
        List<Pointer> active = new ArrayList<>(pointers.values());
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[active.size()];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[active.size()];
        for (int index = 0; index < active.size(); index++) {
            Pointer pointer = active.get(index);
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = pointer.localId;
            property.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[index] = property;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = pointer.x;
            coords.y = pointer.y;
            coords.pressure = pointer.pressure;
            coords.size = 1f;
            coordinates[index] = coords;
        }

        long startNanos = System.nanoTime();
        int totalAttempts = INJECT_RETRY_DELAYS_MS.length + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    active.size(), properties, coordinates, 0, 0, 1f, 1f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            boolean accepted;
            try {
                if (setDisplayId != null) setDisplayId.invoke(event, gestureDisplayId);
                Object result = targetedInjection
                        ? injectInputEvent.invoke(inputManager, event, WAIT_FOR_RESULT, gestureUid)
                        : injectInputEvent.invoke(inputManager, event, WAIT_FOR_RESULT);
                accepted = Boolean.TRUE.equals(result);
            } finally {
                event.recycle();
            }
            if (accepted) {
                if (attempt > 1) {
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                    System.err.println("Input injection accepted after " + attempt
                            + " attempts (" + elapsedMs + "ms)");
                }
                return;
            }
            if (attempt == totalAttempts) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                throw new IllegalStateException("Input injection was rejected by the target window after "
                        + attempt + " attempts over " + elapsedMs + "ms");
            }
            Thread.sleep(INJECT_RETRY_DELAYS_MS[attempt - 1]);
        }
    }

    private void cancel() throws Exception {
        try {
            // A cancellation always uses the UID pinned when the first pointer went down.
            if (!pointers.isEmpty()) inject(MotionEvent.ACTION_CANCEL);
        } finally {
            clearGesture();
        }
    }

    private void cancelQuietly() {
        try {
            cancel();
        } catch (Exception ignored) {
            // The dispatcher may already have cancelled a gesture after focus changed.
        }
    }

    private void clearGesture() {
        pointers.clear();
        gestureUid = -1;
        gestureDisplayId = 0;
        downTime = 0;
    }

    private Pointer requirePointer(int id) {
        Pointer pointer = pointers.get(id);
        if (pointer == null) throw new IllegalArgumentException("Pointer is not down");
        return pointer;
    }

    private int allocateLocalId() {
        for (int id = 0; id < MAX_POINTERS; id++) {
            boolean used = false;
            for (Pointer pointer : pointers.values()) {
                if (pointer.localId == id) {
                    used = true;
                    break;
                }
            }
            if (!used) return id;
        }
        throw new IllegalStateException("Too many active pointers");
    }

    private int indexOf(int id) {
        int index = 0;
        for (Map.Entry<Integer, Pointer> entry : pointers.entrySet()) {
            if (entry.getKey() == id) return index;
            index++;
        }
        throw new IllegalArgumentException("Pointer is not down");
    }

    private static Object readField(Object object, String name) throws Exception {
        Field field = object.getClass().getField(name);
        return field.get(object);
    }

    private static float coordinate(String value) {
        float result = Float.parseFloat(value);
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Coordinate is not finite");
        return result;
    }

    private static void requireFields(String[] fields, int count) {
        if (fields.length != count) throw new IllegalArgumentException("Invalid command arguments");
    }

    private static String errorMessage(Throwable error) {
        while (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getTargetException() != null) {
            error = ((InvocationTargetException) error).getTargetException();
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return message.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static final class Pointer {
        final int localId;
        float x;
        float y;
        float pressure = 1f;

        Pointer(int localId, float x, float y) {
            this.localId = localId;
            this.x = x;
            this.y = y;
        }
    }
}
