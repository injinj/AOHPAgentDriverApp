package org.aohp.agentdriver.executor.ws;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;

import org.aohp.agentdriver.executor.AohpAgentViewClient;
import org.aohp.agentdriver.executor.AohpContainerClient;
import org.aohp.agentdriver.executor.AohpEventStreamClient;
import org.aohp.agentdriver.executor.AohpSecurityBridgeClient;
import org.aohp.agentdriver.executor.AohpVdClient;
import org.aohp.agentdriver.executor.MyAccessibilityService;
import org.aohp.agentdriver.executor.SensorCameraCapture;
import org.aohp.agentdriver.executor.ShellExecutor;
import org.aohp.agentdriver.executor.file.FileBridgeManager;
import org.aohp.agentdriver.overlay.AgentOverlayManager;
import org.aohp.agentdriver.overlay.TapHighlightManager;
import org.aohp.agentdriver.overlay.TapHighlightManager;
import org.aohp.agentdriver.uda.UdaManager;

import org.java_websocket.WebSocket;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.aohp.agentdriver.secret.SecretStore;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * JSON-RPC-style commands over WebSocket ({@code method} + {@code params}), parallel to
 * legacy comma-separated text commands.
 */
public final class JsonCommandHandler {
    private static final String TAG = "JsonCommandHandler";

    /** Boolean JSON fields accepted by {@code act.key} (at most one may be true). */
    private static final String[] ACT_KEY_SHORTCUTS = {
            "back",
            "home",
            "enter",
            "menu",
            "recents",
            "volumeUp",
            "volumeDown",
            "power",
            "tab",
            "del",
            "forwardDel",
            "escape",
            "dpadUp",
            "dpadDown",
            "dpadLeft",
            "dpadRight",
            "dpadCenter",
    };

    /** Delay after tap-before-type so the target field can take focus (dialogs, ViewPager tabs). */
    private static final int INPUT_FOCUS_SETTLE_MS = 250;

    /** {@code act.input} / {@code act.input_node}: {@code replace} clears then types (default). */
    private static final int INPUT_MODE_REPLACE = 0;
    /** Move cursor to end, then commit text (append to existing). */
    private static final int INPUT_MODE_APPEND = 1;
    /** Move cursor to start, then commit text (prepend to existing). */
    private static final int INPUT_MODE_PREPEND = 2;

    private final Context mContext;
    private final SecretStore mSecrets;
    private final ShellExecutor mShell;
    private final AohpVdClient mVd;
    private final AohpAgentViewClient mAgentView;
    private final AohpContainerClient mContainer;
    private final AohpEventStreamClient mEventStream;
    private final AohpSecurityBridgeClient mSecurityBridge;
    private final FileBridgeManager mFileBridge;
    private final UdaManager mUda;

    public JsonCommandHandler(Context context) {
        mContext = context.getApplicationContext();
        mShell = ShellExecutor.getInstance();
        mShell.bindHostContext(mContext);
        mVd = AohpVdClient.getInstance(mContext);
        mAgentView = new AohpAgentViewClient(mContext);
        mContainer = new AohpContainerClient(mContext);
        mSecrets = new SecretStore(mContext);
        mEventStream = new AohpEventStreamClient(mContext);
        mSecurityBridge = new AohpSecurityBridgeClient();
        mFileBridge = new FileBridgeManager(mContext);
        mUda = UdaManager.getInstance(mContext);
    }

    public void dispatch(WebSocket conn, String message) {
        try {
            JSONObject req = new JSONObject(message);
            String id = req.optString("id", "");
            String method = req.optString("method", "");
            JSONObject params = req.optJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }

            CompletableFuture<JSONObject> future;
            try {
                future = handleAsync(method, params);
            } catch (Throwable t) {
                send(conn, id, false, null, err("exception", t.getMessage()));
                return;
            }
            future.thenAccept(result -> {
                        if (result != null && result.optBoolean("error", false)) {
                            send(conn, id, false, null,
                                    err(result.optString("code", "error"),
                                            result.optString("message", "failed")));
                        } else {
                            send(conn, id, true, result, null);
                        }
                    })
                    .exceptionally(ex -> {
                        send(conn, id, false, null, err("exception", ex != null ? ex.getMessage() : "error"));
                        return null;
                    });
        } catch (JSONException e) {
            send(conn, "", false, null, err("parse", e.getMessage()));
        }
    }

    private CompletableFuture<JSONObject> handleAsync(String method, JSONObject p) {
        switch (method) {
            case "meta.version":
                return completedJson(this::metaVersion);
            case "display.list":
                return completedJson(this::displayList);
            case "display.create":
                return completedJson(() -> displayCreate(p));
            case "display.destroy":
                return completedJson(() -> displayDestroy(p));
            case "display.launcher":
                return completedJson(() -> displayLauncher(p));
            case "display.focus":
                return completedJson(() -> displayFocus(p));
            case "act.tap":
                return mFileBridge.withFilePathReport(p, actTap(p));
            case "act.long_tap":
                return mFileBridge.withFilePathReport(p, actLongTap(p));
            case "act.swipe":
            case "act.drag":
                return mFileBridge.withFilePathReport(p, actSwipe(p));
            case "act.input":
                return mFileBridge.withFilePathReport(p, actInput(p));
            case "act.key":
                return mFileBridge.withFilePathReport(p, actKey(p));
            case "act.clear_text":
            case "act.clear":
                return actClearText(p);
            case "act.clear_node":
                return mFileBridge.withFilePathReport(p, actClearNode(p));
            case "act.tap_node":
                return mFileBridge.withFilePathReport(p, actTapNode(p));
            case "act.long_tap_node":
                return mFileBridge.withFilePathReport(p, actLongTapNode(p));
            case "act.input_node":
                return mFileBridge.withFilePathReport(p, actInputNode(p));
            case "act.scroll_to_node":
                return mFileBridge.withFilePathReport(p, actScrollToNode(p));
            case "act.set_node_progress":
                return mFileBridge.withFilePathReport(p, actSetNodeProgress(p));
            case "ui.tree":
                return uiTree(p);
            case "ui.find":
                return uiFind(p);
            case "ui.focused":
                return uiFocused(p);
            case "ui.input_text":
                return uiInputText(p);
            case "shot.full":
                return shotFull(p);
            case "shot.region":
                return shotRegion(p);
            case "shot.node":
                return shotNode(p);
            case "sensor.camera.capture":
                return sensorCameraCapture(p);
            case "app.list":
                return appList(p);
            case "app.info":
                return appInfo(p);
            case "app.start":
                return appStart(p);
            case "app.kill":
                return appKill(p);
            case "app.foreground":
                return appForeground(p);
            case "app.running":
                return completedJson(this::appRunning);
            case "sys.clipboard":
                return sysClipboard(p);
            case "sys.notifications":
                return sysNotifications(p);
            case "sys.device_info":
                return completedJson(() -> crToJson(mShell.getDeviceInfoSync()));
            case "sys.battery":
                return completedJson(() -> crToJson(mShell.getBatteryInfoSync()));
            case "sys.network":
                return completedJson(() -> crToJson(mShell.getNetworkInfoSync()));
            case "sys.screen_info":
                return completedJson(() -> crToJson(mShell.getScreenInfoSync()));
            case "event.register":
                return completedJson(() -> eventRegister(p));
            case "event.drain":
                return completedJson(() -> eventDrain(p));
            case "event.unregister":
                return completedJson(() -> eventUnregister(p));
            case "event.status":
                return completedJson(this::eventStatus);
            case "sys.wake":
                return wrapFuture(mShell.wakeUpScreen());
            case "sys.sleep":
                return wrapFuture(mShell.sleepScreen());
            case "sys.unlock":
                return wrapFuture(mShell.unlockScreen());
            case "sms.send":
                return completedJson(() -> smsSend(p));
            case "sandbox.list":
                return completedJson(this::sandboxList);
            case "sandbox.create":
                return completedJson(() -> sandboxCreate(p));
            case "sandbox.destroy":
                return completedJson(() -> sandboxDestroy(p));
            case "sandbox.reset":
                return completedJson(() -> sandboxReset(p));
            case "sandbox.exec":
                return completedJson(() -> sandboxExec(p));
            case "sandbox.svc_start":
                return completedJson(() -> sandboxSvcStart(p));
            case "sandbox.svc_stop":
                return completedJson(() -> sandboxSvcStop(p));
            case "sandbox.svc_list":
                return completedJson(() -> sandboxSvcList(p));
            case "sandbox.svc_log":
                return completedJson(() -> sandboxSvcLog(p));
            case "sandbox.diag":
                return completedJson(() -> sandboxDiag(p));
            case "secret.get":
                return completedJson(() -> secretGet(p));
            case "secret.set":
                return completedJson(() -> secretSet(p));
            case "secret.delete":
                return completedJson(() -> secretDelete(p));
            case "secret.list":
                return completedJson(this::secretList);
            case "file.stat":
                return completedJson(() -> mFileBridge.stat(p));
            case "file.list":
                return completedJson(() -> mFileBridge.list(p));
            case "file.recent":
                return completedJson(() -> mFileBridge.recent(p));
            case "file.snapshot":
                return completedJson(() -> mFileBridge.snapshot(p));
            case "file.diff":
                return completedJson(() -> mFileBridge.diff(p));
            case "file.show_in_folder":
                return completedJson(() -> mFileBridge.showInFolder(p));
            case "file.reveal":
                return completedJson(() -> mFileBridge.reveal(p));
            case "file.share":
                return completedJson(() -> mFileBridge.share(p));
            case "uda.config.get":
                return completedJson(this::udaConfigGet);
            case "uda.config.set":
                return completedJson(() -> udaConfigSet(p));
            case "uda.generate":
                return completedJson(() -> udaGenerate(p));
            case "uda.input.init":
                return completedJson(() -> udaInputInit(p));
            case "uda.input.write":
                return completedJson(() -> udaInputWrite(p));
            case "uda.status":
                return completedJson(() -> udaStatus(p));
            case "uda.list":
                return completedJson(this::udaList);
            case "uda.delete":
                return completedJson(() -> udaDelete(p));
            case "uda.preview":
                return completedJson(() -> udaPreview(p));
            case "uda.launch":
                return completedJson(() -> udaLaunch(p));
            case "uda.install":
                return completedJson(() -> udaInstall(p));
            case "uda.uninstall":
                return completedJson(() -> udaUninstall(p));
            case "uda.unpin":
                return completedJson(() -> udaUnpin(p));
            case "uda.pin":
                return completedJson(() -> udaPin(p));
            case "overlay.task.start":
                return completedJson(() -> overlayTaskStart(p));
            case "overlay.event.push":
                return completedJson(() -> overlayEventPush(p));
            case "overlay.state":
                return completedJson(() -> overlayState(p));
            case "overlay.task.finish":
                return completedJson(() -> overlayTaskFinish(p));
            case "overlay.hide":
                return completedJson(() -> overlayHide(p));
            case "overlay.tap.show":
                return completedJson(() -> overlayTapShow(p));
            case "overlay.tap.hide":
                return completedJson(() -> overlayTapHide(p));
            default:
                return CompletableFuture.completedFuture(errObj("unknown_method", method));
        }
    }

    /** Wraps JSON builders that throw {@link JSONException} into a completed future. */
    private CompletableFuture<JSONObject> completedJson(Callable<JSONObject> c) {
        try {
            return CompletableFuture.completedFuture(c.call());
        } catch (JSONException e) {
            return CompletableFuture.completedFuture(errObj("json", e.getMessage()));
        } catch (Exception e) {
            CompletableFuture<JSONObject> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    private JSONObject metaVersion() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("cliProtocol", 1);
        o.put("app", mContext.getPackageName());
        JSONArray features = new JSONArray();
        features.put("secrets");
        o.put("features", features);
        return o;
    }

    private JSONObject displayList() throws JSONException {
        ShellExecutor.CommandResult r = mVd.getDisplayRuntimeSnapshotJson(null);
        JSONObject o = new JSONObject();
        o.put("ok", r.success);
        if (r.success) {
            try {
                o.put("snapshot", new JSONObject(r.output));
            } catch (JSONException e) {
                o.put("snapshotText", r.output);
            }
        } else {
            o.put("error", r.error);
        }
        return o;
    }

    private JSONObject displayCreate(JSONObject p) throws JSONException {
        String name = p.optString("name", "aohp-vd");
        int[] builtin = mShell.getBuiltinDisplayRealMetricsPx();
        int defW = builtin != null ? builtin[0] : 1080;
        int defH = builtin != null ? builtin[1] : 1920;
        int defD = builtin != null ? builtin[2] : 320;
        int w = defW;
        if (p.has("width")) {
            int pw = p.optInt("width", 0);
            if (pw > 0) {
                w = pw;
            }
        }
        int h = defH;
        if (p.has("height")) {
            int ph = p.optInt("height", 0);
            if (ph > 0) {
                h = ph;
            }
        }
        int d = defD;
        if (p.has("density")) {
            int pd = p.optInt("density", 0);
            if (pd > 0) {
                d = pd;
            }
        }
        int flags = p.optInt("flags", 0);
        ShellExecutor.CommandResult r = mVd.createVirtualDisplay(name, w, h, d, flags);
        JSONObject o = new JSONObject();
        o.put("success", r.success);
        if (r.success) {
            o.put("displayId", Integer.parseInt(r.output.trim()));
        } else {
            o.put("error", r.error);
        }
        return o;
    }

    private JSONObject displayDestroy(JSONObject p) throws JSONException {
        int id = p.getInt("displayId");
        ShellExecutor.CommandResult r = mVd.destroyVirtualDisplay(id);
        JSONObject o = new JSONObject();
        o.put("success", r.success);
        if (!r.success) {
            o.put("error", r.error);
        }
        return o;
    }

    private JSONObject displayLauncher(JSONObject p) throws JSONException {
        int displayId = p.getInt("displayId");
        String pkg = p.getString("packageName");
        ShellExecutor.CommandResult r = mVd.startLauncherOnDisplay(displayId, pkg);
        JSONObject o = new JSONObject();
        o.put("success", r.success);
        if (!r.success) {
            o.put("error", r.error);
        }
        return o;
    }

    private JSONObject displayFocus(JSONObject p) throws JSONException {
        String pkg = p.getString("packageName");
        ShellExecutor.CommandResult r = mVd.setFocusPackage(pkg);
        JSONObject o = new JSONObject();
        o.put("success", r.success);
        if (!r.success) {
            o.put("error", r.error);
        }
        return o;
    }

    private int resolveDisplayId(JSONObject p) {
        return p.optInt("displayId", Display.DEFAULT_DISPLAY);
    }

    private CompletableFuture<JSONObject> actTap(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int x = p.optInt("x");
        int y = p.optInt("y");
        if (mVd.useAohpForDisplayOps()) {
            // duration 由 act.long_tap 表达；注入走 aohp_virtual_display（非无障碍手势）
            return mShell.tapOnDisplay(displayId, x, y)
                    .thenApply(this::crToJson);
        }
        return CompletableFuture.completedFuture(errObj("aohp_required",
                "aohp_virtual_display not available; act.tap requires AOHP Binder inject"));
    }

    private CompletableFuture<JSONObject> actLongTap(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int x = p.optInt("x");
        int y = p.optInt("y");
        int dur = p.optInt("duration", 1000);
        if (mVd.useAohpForDisplayOps()) {
            return mShell.longPressOnDisplay(displayId, x, y, dur).thenApply(this::crToJson);
        }
        return CompletableFuture.completedFuture(errObj("aohp_required",
                "aohp_virtual_display not available; act.long_tap requires AOHP"));
    }

    private CompletableFuture<JSONObject> actSwipe(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int x1 = p.optInt("x1");
        int y1 = p.optInt("y1");
        int x2 = p.optInt("x2");
        int y2 = p.optInt("y2");
        int dur = p.optInt("duration", 300);
        if (mVd.useAohpForDisplayOps()) {
            return mShell.swipeOnDisplay(displayId, x1, y1, x2, y2, dur).thenApply(this::crToJson);
        }
        return CompletableFuture.completedFuture(errObj("aohp_required",
                "aohp_virtual_display not available; act.swipe requires AOHP"));
    }

    private CompletableFuture<JSONObject> actInput(JSONObject p) {
        int mode = parseInputMode(p);
        if (mode < 0) {
            return CompletableFuture.completedFuture(
                    errObj("bad_input_mode", "inputMode must be replace, append, or prepend"));
        }
        int displayId = resolveDisplayId(p);
        String text = p.optString("text", "");
        if (mVd.useAohpForDisplayOps()) {
            return completeInputAfterFocusJson(displayId, text, mode, p);
        }
        return CompletableFuture.completedFuture(errObj("aohp_required",
                "aohp_virtual_display not available; act.input requires AOHP"));
    }

    /**
     * Parses {@code inputMode}: {@code replace} (default) clears then types; {@code append} sends
     * MOVE_END then types; {@code prepend} sends MOVE_HOME then types. Case-insensitive.
     *
     * @return one of {@link #INPUT_MODE_REPLACE}, {@link #INPUT_MODE_APPEND}, {@link #INPUT_MODE_PREPEND},
     *         or {@code -1} if invalid
     */
    private static int parseInputMode(JSONObject p) {
        if (!p.has("inputMode")) {
            return INPUT_MODE_REPLACE;
        }
        String raw = p.optString("inputMode", "").trim();
        if (raw.isEmpty()) {
            return INPUT_MODE_REPLACE;
        }
        String m = raw.toLowerCase(Locale.ROOT);
        switch (m) {
            case "replace":
                return INPUT_MODE_REPLACE;
            case "append":
                return INPUT_MODE_APPEND;
            case "prepend":
                return INPUT_MODE_PREPEND;
            default:
                return -1;
        }
    }

    /**
     * After the target field is focused: replace = clear (via {@code ACTION_SET_TEXT}) then inject
     * keystrokes; append = MOVE_END + type; prepend = MOVE_HOME + type.
     * Uses {@code nodeId} when present ({@code >0}): same id as {@code ui.tree} for the field to clear.
     * When {@code nodeId <= 0}, clears the focused editable. Optional {@code clearCount} is ignored
     * (kept for RPC backward compatibility).
     * <p>
     * Replace avoids committing the final string solely via {@code ACTION_SET_TEXT}: some apps (e.g.
     * Fossify Notes) update the widget but skip persistence listeners unless real key injection runs.
     */
    private CompletableFuture<JSONObject> completeInputAfterFocusJson(
            int displayId, String text, int mode, JSONObject p) {
        int flags = p.optInt("flags", 0);
        int nodeIdForClear = p.optInt("nodeId", 0);
        if (mode == INPUT_MODE_REPLACE) {
            return mShell.clearTextOnDisplay(displayId, nodeIdForClear, flags).thenCompose(clr -> {
                if (!clr.success && !isAohpTextApiSkippableMismatch(clr)) {
                    return CompletableFuture.completedFuture(crToJson(clr));
                }
                return mShell.inputTextOnDisplay(displayId, text).thenApply(this::crToJson);
            });
        }
        if (mode == INPUT_MODE_APPEND) {
            return mShell.keyEventOnDisplay(displayId, KeyEvent.KEYCODE_MOVE_END).thenCompose(cr -> {
                if (!cr.success) {
                    return CompletableFuture.completedFuture(crToJson(cr));
                }
                return mShell.inputTextOnDisplay(displayId, text).thenApply(this::crToJson);
            });
        }
        if (mode == INPUT_MODE_PREPEND) {
            return mShell.keyEventOnDisplay(displayId, KeyEvent.KEYCODE_MOVE_HOME).thenCompose(cr -> {
                if (!cr.success) {
                    return CompletableFuture.completedFuture(crToJson(cr));
                }
                return mShell.inputTextOnDisplay(displayId, text).thenApply(this::crToJson);
            });
        }
        return CompletableFuture.completedFuture(
                errObj("bad_input_mode", "unknown input mode"));
    }

    /** Older system images may lack AOHP accessibility text APIs; fall back to key injection. */
    private static boolean isAohpTextApiSkippableMismatch(ShellExecutor.CommandResult cr) {
        if (cr == null || cr.success) {
            return false;
        }
        String err = cr.error != null ? cr.error : "";
        return err.contains("setEditableText unavailable")
                || err.contains("clearEditableText unavailable")
                || err.contains("NoSuchMethodError")
                || err.contains("setEditableText(")
                || err.contains("clearEditableText(III)");
    }

    private CompletableFuture<JSONObject> actKey(JSONObject p) {
        int displayId = resolveDisplayId(p);
        String keyName = p.optString("keyName", "").trim();
        boolean hasName = !keyName.isEmpty();
        boolean hasCode = p.has("keyCode");
        int shortcutCount = 0;
        String activeShortcut = null;
        for (String k : ACT_KEY_SHORTCUTS) {
            if (p.optBoolean(k, false)) {
                shortcutCount++;
                activeShortcut = k;
            }
        }
        if (shortcutCount > 1) {
            return CompletableFuture.completedFuture(errObj("bad_key",
                    "only one shortcut flag may be true (back, home, recents, …)"));
        }
        int modeCount = (hasName ? 1 : 0) + (hasCode ? 1 : 0) + (shortcutCount == 1 ? 1 : 0);
        if (modeCount > 1) {
            return CompletableFuture.completedFuture(errObj("bad_key",
                    "use only one of keyName, keyCode, or a single shortcut flag"));
        }
        if (modeCount == 0) {
            return CompletableFuture.completedFuture(errObj("bad_key",
                    "need keyCode, keyName, or one shortcut (back, home, recents, …)"));
        }

        int key;
        if (hasName) {
            Integer parsed = parseKeyNameToken(keyName);
            if (parsed == null) {
                return CompletableFuture.completedFuture(
                        errObj("bad_key", "unknown keyName: " + keyName));
            }
            key = parsed;
        } else if (hasCode) {
            try {
                key = p.getInt("keyCode");
            } catch (JSONException e) {
                return CompletableFuture.completedFuture(errObj("bad_key", e.getMessage()));
            }
        } else {
            key = shortcutToKeyCode(activeShortcut);
        }

        if (key == KeyEvent.KEYCODE_UNKNOWN) {
            return CompletableFuture.completedFuture(errObj("bad_key", "invalid or unknown key"));
        }
        if (mVd.useAohpForDisplayOps()) {
            return mShell.keyEventOnDisplay(displayId, key).thenApply(this::crToJson);
        }
        return CompletableFuture.completedFuture(errObj("aohp_required",
                "aohp_virtual_display not available; act.key requires AOHP"));
    }

    /**
     * Resolves a token like {@code adb shell input keyevent}: decimal, {@code 0x} hex,
     * {@code KEYCODE_*}, common names, or a single character (0-9, A-Z).
     */
    private static Integer parseKeyNameToken(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (t.length() > 2 && (t.startsWith("0x") || t.startsWith("0X"))) {
            try {
                return Integer.parseInt(t.substring(2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String u = t.toUpperCase(Locale.ROOT);
        if (u.startsWith("KEYCODE_")) {
            u = u.substring(8);
        }
        if (u.length() == 1) {
            char c = u.charAt(0);
            if (c >= '0' && c <= '9') {
                return KeyEvent.KEYCODE_0 + (c - '0');
            }
            if (c >= 'A' && c <= 'Z') {
                return KeyEvent.KEYCODE_A + (c - 'A');
            }
        }
        switch (u) {
            case "BACK":
                return KeyEvent.KEYCODE_BACK;
            case "HOME":
                return KeyEvent.KEYCODE_HOME;
            case "MENU":
                return KeyEvent.KEYCODE_MENU;
            case "ENTER":
                return KeyEvent.KEYCODE_ENTER;
            case "APP_SWITCH":
            case "RECENTS":
                return KeyEvent.KEYCODE_APP_SWITCH;
            case "TAB":
                return KeyEvent.KEYCODE_TAB;
            case "SPACE":
                return KeyEvent.KEYCODE_SPACE;
            case "ESCAPE":
            case "ESC":
                return KeyEvent.KEYCODE_ESCAPE;
            case "DEL":
            case "BACKSPACE":
                return KeyEvent.KEYCODE_DEL;
            case "FORWARD_DEL":
                return KeyEvent.KEYCODE_FORWARD_DEL;
            case "VOLUME_UP":
                return KeyEvent.KEYCODE_VOLUME_UP;
            case "VOLUME_DOWN":
                return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "VOLUME_MUTE":
            case "MUTE":
                return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "POWER":
                return KeyEvent.KEYCODE_POWER;
            case "CAMERA":
                return KeyEvent.KEYCODE_CAMERA;
            case "DPAD_UP":
                return KeyEvent.KEYCODE_DPAD_UP;
            case "DPAD_DOWN":
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case "DPAD_LEFT":
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case "DPAD_RIGHT":
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "DPAD_CENTER":
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case "MEDIA_PLAY_PAUSE":
            case "PLAY_PAUSE":
                return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "MEDIA_PLAY":
                return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "MEDIA_PAUSE":
                return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "MEDIA_NEXT":
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "MEDIA_PREVIOUS":
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "PAGE_UP":
                return KeyEvent.KEYCODE_PAGE_UP;
            case "PAGE_DOWN":
                return KeyEvent.KEYCODE_PAGE_DOWN;
            case "MOVE_HOME":
                return KeyEvent.KEYCODE_MOVE_HOME;
            case "MOVE_END":
                return KeyEvent.KEYCODE_MOVE_END;
            case "BRIGHTNESS_UP":
                return KeyEvent.KEYCODE_BRIGHTNESS_UP;
            case "BRIGHTNESS_DOWN":
                return KeyEvent.KEYCODE_BRIGHTNESS_DOWN;
            case "SOFT_LEFT":
                return KeyEvent.KEYCODE_SOFT_LEFT;
            case "SOFT_RIGHT":
                return KeyEvent.KEYCODE_SOFT_RIGHT;
            case "CALL":
                return KeyEvent.KEYCODE_CALL;
            case "ENDCALL":
                return KeyEvent.KEYCODE_ENDCALL;
            case "SEARCH":
                return KeyEvent.KEYCODE_SEARCH;
            case "CLEAR":
                return KeyEvent.KEYCODE_CLEAR;
            case "NUMPAD_ENTER":
                return KeyEvent.KEYCODE_NUMPAD_ENTER;
            case "NUMPAD_DOT":
                return KeyEvent.KEYCODE_NUMPAD_DOT;
            case "NUMPAD_DIVIDE":
                return KeyEvent.KEYCODE_NUMPAD_DIVIDE;
            case "NUMPAD_MULTIPLY":
                return KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
            case "NUMPAD_SUBTRACT":
                return KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
            case "NUMPAD_ADD":
                return KeyEvent.KEYCODE_NUMPAD_ADD;
            case "NUMPAD_EQUALS":
                return KeyEvent.KEYCODE_NUMPAD_EQUALS;
            case "NUMPAD_COMMA":
                return KeyEvent.KEYCODE_NUMPAD_COMMA;
            case "NUMPAD_LEFT_PAREN":
                return KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN;
            case "NUMPAD_RIGHT_PAREN":
                return KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN;
            case "NUMPAD_0":
                return KeyEvent.KEYCODE_NUMPAD_0;
            case "NUMPAD_1":
                return KeyEvent.KEYCODE_NUMPAD_1;
            case "NUMPAD_2":
                return KeyEvent.KEYCODE_NUMPAD_2;
            case "NUMPAD_3":
                return KeyEvent.KEYCODE_NUMPAD_3;
            case "NUMPAD_4":
                return KeyEvent.KEYCODE_NUMPAD_4;
            case "NUMPAD_5":
                return KeyEvent.KEYCODE_NUMPAD_5;
            case "NUMPAD_6":
                return KeyEvent.KEYCODE_NUMPAD_6;
            case "NUMPAD_7":
                return KeyEvent.KEYCODE_NUMPAD_7;
            case "NUMPAD_8":
                return KeyEvent.KEYCODE_NUMPAD_8;
            case "NUMPAD_9":
                return KeyEvent.KEYCODE_NUMPAD_9;
            case "F1":
                return KeyEvent.KEYCODE_F1;
            case "F2":
                return KeyEvent.KEYCODE_F2;
            case "F3":
                return KeyEvent.KEYCODE_F3;
            case "F4":
                return KeyEvent.KEYCODE_F4;
            case "F5":
                return KeyEvent.KEYCODE_F5;
            case "F6":
                return KeyEvent.KEYCODE_F6;
            case "F7":
                return KeyEvent.KEYCODE_F7;
            case "F8":
                return KeyEvent.KEYCODE_F8;
            case "F9":
                return KeyEvent.KEYCODE_F9;
            case "F10":
                return KeyEvent.KEYCODE_F10;
            case "F11":
                return KeyEvent.KEYCODE_F11;
            case "F12":
                return KeyEvent.KEYCODE_F12;
            case "SHIFT_LEFT":
                return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "SHIFT_RIGHT":
                return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case "CTRL_LEFT":
                return KeyEvent.KEYCODE_CTRL_LEFT;
            case "CTRL_RIGHT":
                return KeyEvent.KEYCODE_CTRL_RIGHT;
            case "ALT_LEFT":
                return KeyEvent.KEYCODE_ALT_LEFT;
            case "ALT_RIGHT":
                return KeyEvent.KEYCODE_ALT_RIGHT;
            case "META_LEFT":
                return KeyEvent.KEYCODE_META_LEFT;
            case "META_RIGHT":
                return KeyEvent.KEYCODE_META_RIGHT;
            case "SYM":
                return KeyEvent.KEYCODE_SYM;
            case "NOTIFICATION":
                return KeyEvent.KEYCODE_NOTIFICATION;
            case "FOCUS":
                return KeyEvent.KEYCODE_FOCUS;
            case "STAR":
                return KeyEvent.KEYCODE_STAR;
            case "POUND":
                return KeyEvent.KEYCODE_POUND;
            case "COMMA":
                return KeyEvent.KEYCODE_COMMA;
            case "PERIOD":
                return KeyEvent.KEYCODE_PERIOD;
            case "PLUS":
                return KeyEvent.KEYCODE_PLUS;
            case "EQUALS":
                return KeyEvent.KEYCODE_EQUALS;
            case "MINUS":
                return KeyEvent.KEYCODE_MINUS;
            case "LEFT_BRACKET":
                return KeyEvent.KEYCODE_LEFT_BRACKET;
            case "RIGHT_BRACKET":
                return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case "BACKSLASH":
                return KeyEvent.KEYCODE_BACKSLASH;
            case "SEMICOLON":
                return KeyEvent.KEYCODE_SEMICOLON;
            case "APOSTROPHE":
                return KeyEvent.KEYCODE_APOSTROPHE;
            case "SLASH":
                return KeyEvent.KEYCODE_SLASH;
            case "AT":
                return KeyEvent.KEYCODE_AT;
            case "GRAVE":
                return KeyEvent.KEYCODE_GRAVE;
            default:
                return null;
        }
    }

    private static int shortcutToKeyCode(String shortcut) {
        if (shortcut == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        switch (shortcut) {
            case "back":
                return KeyEvent.KEYCODE_BACK;
            case "home":
                return KeyEvent.KEYCODE_HOME;
            case "enter":
                return KeyEvent.KEYCODE_ENTER;
            case "menu":
                return KeyEvent.KEYCODE_MENU;
            case "recents":
                return KeyEvent.KEYCODE_APP_SWITCH;
            case "volumeUp":
                return KeyEvent.KEYCODE_VOLUME_UP;
            case "volumeDown":
                return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "power":
                return KeyEvent.KEYCODE_POWER;
            case "tab":
                return KeyEvent.KEYCODE_TAB;
            case "del":
                return KeyEvent.KEYCODE_DEL;
            case "forwardDel":
                return KeyEvent.KEYCODE_FORWARD_DEL;
            case "escape":
                return KeyEvent.KEYCODE_ESCAPE;
            case "dpadUp":
                return KeyEvent.KEYCODE_DPAD_UP;
            case "dpadDown":
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case "dpadLeft":
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case "dpadRight":
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "dpadCenter":
                return KeyEvent.KEYCODE_DPAD_CENTER;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private CompletableFuture<JSONObject> actClearText(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int flags = p.optInt("flags", 0);
        return mShell.clearTextOnDisplay(displayId, 0, flags).thenApply(this::crToJson);
    }

    /**
     * Tap a node to focus its text field, then clear via {@code ACTION_SET_TEXT} (same as
     * {@code act.clear_text} semantics but targeting {@code nodeId} from the tree).
     */
    private CompletableFuture<JSONObject> actClearNode(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int nodeId = p.optInt("nodeId", -1);
        int flags = p.optInt("flags", 0);
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult dr = mVd.dumpUiTree(displayId, flags);
                if (!dr.success) {
                    return errObj("no_aohp_vd", dr.error != null ? dr.error : "dump failed");
                }
                String tree = dr.output;
                int[] center = centerFromTree(tree, nodeId);
                if (center == null) {
                    return errObj("node_not_found", "id=" + nodeId);
                }
                ShellExecutor.CommandResult tap = mShell.tapOnDisplay(displayId, center[0], center[1])
                        .get(30, TimeUnit.SECONDS);
                if (!tap.success) {
                    return crToJson(tap);
                }
                ShellExecutor.CommandResult clr =
                        mShell.clearTextOnDisplay(displayId, nodeId, flags).get(30, TimeUnit.SECONDS);
                return crToJson(clr);
            } catch (Exception e) {
                Log.e(TAG, "actClearNode", e);
                return errObj("clear_node", e.getMessage());
            }
        });
    }

    private CompletableFuture<JSONObject> actTapNode(JSONObject p) {
        return nodeAction(p, false, false, -1);
    }

    /** Long-press at node center; params mirror {@code act.long_tap} for {@code duration} (default 1000 ms). */
    private CompletableFuture<JSONObject> actLongTapNode(JSONObject p) {
        int dur = p.optInt("duration", 1000);
        return nodeAction(p, false, false, dur);
    }

    private CompletableFuture<JSONObject> actInputNode(JSONObject p) {
        return nodeAction(p, true, false, -1);
    }

    private CompletableFuture<JSONObject> actScrollToNode(JSONObject p) {
        return nodeAction(p, false, true, -1);
    }

    /**
     * Set a SeekBar / RangeSlider node to a given percentage (0–100) via system_server
     * AccessibilityNodeInfo.ACTION_SET_PROGRESS.
     * Params: displayId, nodeId, percent (float 0–100), flags (optional, default 0).
     */
    private CompletableFuture<JSONObject> actSetNodeProgress(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int nodeId = p.optInt("nodeId", -1);
        double percent = p.optDouble("percent", 50.0);
        int flags = p.optInt("flags", 0);
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        if (percent < 0.0 || percent > 100.0) {
            return CompletableFuture.completedFuture(
                    errObj("bad_percent", "percent must be 0–100, got: " + percent));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult r = mVd.setNodeProgress(
                        displayId, nodeId, (float) percent, flags);
                JSONObject out = null;
                if (r.output != null && !r.output.isEmpty()) {
                    out = new JSONObject(r.output);
                }
                if (!r.success) {
                    String code = out != null ? out.optString("error", "set_node_progress")
                            : "set_node_progress";
                    String msg = out != null ? out.optString("message", r.error) : r.error;
                    return errObj(code, msg != null ? msg : "setNodeProgress failed");
                }
                return out != null ? out : crToJson(r);
            } catch (Exception e) {
                Log.e(TAG, "actSetNodeProgress", e);
                return errObj("set_node_progress", e.getMessage());
            }
        });
    }

    /**
     * @param longPressMs {@code -1} for a normal tap; {@code >= 0} hold duration in ms (same semantics as
     *                      {@code act.long_tap}).
     */
    private CompletableFuture<JSONObject> nodeAction(
            JSONObject p, boolean input, boolean scroll, int longPressMs) {
        int displayId = resolveDisplayId(p);
        int nodeId = p.optInt("nodeId", -1);
        int flags = p.optInt("flags", 0);
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult dr = mVd.dumpUiTree(displayId, flags);
                if (!dr.success) {
                    return errObj("no_aohp_vd", dr.error != null ? dr.error : "dump failed");
                }
                String tree = dr.output;
                int[] center = centerFromTree(tree, nodeId);
                if (center == null) {
                    return errObj("node_not_found", "id=" + nodeId);
                }
                if (scroll) {
                    mShell.swipeOnDisplay(displayId, center[0], center[1] + 200,
                            center[0], center[1] - 200, 400).get(30, TimeUnit.SECONDS);
                    return okMsg("scroll attempted");
                }
                ShellExecutor.CommandResult tap;
                if (longPressMs >= 0) {
                    tap = mShell.longPressOnDisplay(displayId, center[0], center[1], longPressMs)
                            .get(30, TimeUnit.SECONDS);
                } else {
                    tap = mShell.tapOnDisplay(displayId, center[0], center[1])
                            .get(30, TimeUnit.SECONDS);
                }
                if (!tap.success) {
                    return crToJson(tap);
                }
                if (input) {
                    Thread.sleep(INPUT_FOCUS_SETTLE_MS);
                    int mode = parseInputMode(p);
                    if (mode < 0) {
                        return errObj("bad_input_mode", "inputMode must be replace, append, or prepend");
                    }
                    String text = p.getString("text");
                    return completeInputAfterFocusJson(displayId, text, mode, p)
                            .get(30, TimeUnit.SECONDS);
                }
                return crToJson(tap);
            } catch (Exception e) {
                Log.e(TAG, "nodeAction", e);
                return errObj("node_action", e.getMessage());
            }
        });
    }

    private int[] centerFromTree(String treeJson, int tempId) {
        try {
            JSONArray nodes;
            if (treeJson.trim().startsWith("{")) {
                JSONObject root = new JSONObject(treeJson);
                if (root.has("nodes")) {
                    nodes = root.getJSONArray("nodes");
                } else {
                    return null;
                }
            } else {
                nodes = new JSONArray(treeJson);
            }
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                int nid = n.optInt("id", -1);
                if (nid < 0) {
                    nid = n.optInt("temp_id", -1);
                }
                if (nid == tempId) {
                    JSONArray b = n.getJSONArray("bounds");
                    JSONArray p1 = b.getJSONArray(0);
                    JSONArray p2 = b.getJSONArray(1);
                    int l = p1.getInt(0), t = p1.getInt(1);
                    int r = p2.getInt(0), bt = p2.getInt(1);
                    return new int[]{(l + r) / 2, (t + bt) / 2};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "centerFromTree: " + e.getMessage());
        }
        return null;
    }

    private CompletableFuture<JSONObject> uiTree(JSONObject p) {
        int displayId = resolveDisplayId(p);
        boolean raw = p.optBoolean("raw", false);
        boolean origin = raw || p.optBoolean("origin", false);
        boolean enhanced = !raw && p.optBoolean("enhanced", !p.has("flags"));
        int effFlags = p.optInt("flags", enhanced ? 0x7 : 0);
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult r = mVd.dumpUiTree(displayId, effFlags);
                if (!r.success) {
                    return errObj("no_aohp_vd", r.error != null ? r.error : "dump failed");
                }
                JSONObject o = new JSONObject();
                o.put("tree", origin ? r.output : compactUiTreeHtml(r.output));
                return o;
            } catch (Exception e) {
                return errObj("ui_tree", e.getMessage());
            }
        });
    }

    private CompletableFuture<JSONObject> uiFind(JSONObject p) {
        int displayId = resolveDisplayId(p);
        boolean raw = p.optBoolean("raw", false);
        boolean enhanced = !raw && p.optBoolean("enhanced", !p.has("flags"));
        int effFlags = p.optInt("flags", enhanced ? 0x7 : 0);
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult dr = mVd.dumpUiTree(displayId, effFlags);
                if (!dr.success) {
                    return errObj("no_aohp_vd", dr.error != null ? dr.error : "dump failed");
                }
                String tree = dr.output;
                JSONArray nodes;
                if (tree.trim().startsWith("{")) {
                    JSONObject root = new JSONObject(tree);
                    nodes = root.getJSONArray("nodes");
                } else {
                    nodes = new JSONArray(tree);
                }
                String text = p.optString("text", null);
                String desc = p.optString("desc", null);
                String rid = p.optString("resourceId", null);
                JSONArray out = new JSONArray();
                for (int i = 0; i < nodes.length(); i++) {
                    JSONObject n = nodes.getJSONObject(i);
                    boolean ok = true;
                    if (text != null && !text.isEmpty()) {
                        ok &= text.equals(n.optString("text", ""));
                    }
                    if (desc != null && !desc.isEmpty()) {
                        String cd = n.optString("contentDescription", "");
                        if (cd.isEmpty()) {
                            cd = n.optString("content_desc", "");
                        }
                        ok &= desc.equals(cd);
                    }
                    if (rid != null && !rid.isEmpty()) {
                        String res = n.optString("resourceId", "");
                        if (res.isEmpty()) {
                            res = n.optString("resource_id", "");
                        }
                        ok &= rid.equals(res);
                    }
                    if (ok) {
                        out.put(n);
                    }
                }
                JSONObject r = new JSONObject();
                r.put("matches", out);
                return r;
            } catch (Exception e) {
                return errObj("ui_find", e.getMessage());
            }
        });
    }

    private static String compactUiTreeHtml(String treeJson) {
        try {
            JSONObject root = new JSONObject(treeJson);
            JSONArray windows = root.optJSONArray("windows");
            JSONArray nodes = root.optJSONArray("nodes");
            if (nodes == null) {
                return treeJson;
            }
            Map<Integer, JSONObject> byId = new HashMap<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                byId.put(n.optInt("id", n.optInt("temp_id", -1)), n);
            }
            Map<Integer, ArrayList<JSONObject>> byParent = new HashMap<>();
            Map<Integer, ArrayList<JSONObject>> rootsByWindow = new HashMap<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                int parent = n.isNull("parent") ? -1 : n.optInt("parent", -1);
                if (parent >= 0 && byId.containsKey(parent)) {
                    byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(n);
                } else {
                    int windowId = n.optInt("windowId", -1);
                    rootsByWindow.computeIfAbsent(windowId, k -> new ArrayList<>()).add(n);
                }
            }

            JSONObject stats = root.optJSONObject("stats");
            StringBuilder sb = new StringBuilder(Math.max(4096, treeJson.length() / 3));
            sb.append("<!-- AOHP UI Tree v1: use `aohp ui tree -o` or RPC origin=true for origin JSON. -->\n");
            sb.append("<ui-tree");
            appendAttr(sb, "displayId", root.opt("displayId"));
            appendAttr(sb, "flags", root.opt("flags"));
            if (stats != null) {
                appendAttr(sb, "windows", stats.opt("windowCount"));
                appendAttr(sb, "nodes", stats.opt("nodeCount"));
                if (stats.optBoolean("truncated", false)) {
                    appendAttr(sb, "truncated", true);
                }
            }
            sb.append(">\n");
            Set<Integer> rendered = new HashSet<>();
            if (windows != null) {
                for (int i = 0; i < windows.length(); i++) {
                    JSONObject w = windows.getJSONObject(i);
                    int windowId = w.optInt("windowId", -1);
                    indent(sb, 1).append("<window");
                    appendAttr(sb, "id", windowId);
                    appendAttr(sb, "type", w.opt("type"));
                    appendAttr(sb, "package", w.opt("package"));
                    appendAttr(sb, "bounds", boundsText(w.optJSONArray("bounds")));
                    appendTrueAttr(sb, "focused", w.optBoolean("focused", false));
                    appendTrueAttr(sb, "active", w.optBoolean("active", false));
                    appendTrueAttr(sb, "inputFocused", w.optBoolean("inputFocused", false));
                    sb.append(">\n");
                    ArrayList<JSONObject> roots = rootsByWindow.get(windowId);
                    if (roots != null) {
                        for (JSONObject n : roots) {
                            renderNodeHtml(sb, n, byParent, rendered, 2);
                        }
                    }
                    indent(sb, 1).append("</window>\n");
                }
            }
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                int id = n.optInt("id", n.optInt("temp_id", -1));
                if (!rendered.contains(id)) {
                    renderNodeHtml(sb, n, byParent, rendered, 1);
                }
            }
            sb.append("</ui-tree>");
            return sb.toString();
        } catch (Exception e) {
            return treeJson;
        }
    }

    private static void renderNodeHtml(
            StringBuilder sb,
            JSONObject n,
            Map<Integer, ArrayList<JSONObject>> byParent,
            Set<Integer> rendered,
            int depth) {
        int id = n.optInt("id", n.optInt("temp_id", -1));
        if (id >= 0 && rendered.contains(id)) {
            return;
        }
        if (id >= 0) {
            rendered.add(id);
        }
        ArrayList<JSONObject> children = id >= 0 ? byParent.get(id) : null;
        indent(sb, depth).append("<node");
        appendAttr(sb, "id", id >= 0 ? id : null);
        appendAttr(sb, "class", shortClassName(n.optString("class", "")));
        appendAttr(sb, "text", nonEmpty(n.optString("text", "")));
        appendAttr(sb, "desc", firstNonEmpty(n.optString("contentDescription", ""),
                n.optString("content_desc", "")));
        appendAttr(sb, "resourceId", firstNonEmpty(n.optString("resourceId", ""),
                n.optString("resource_id", "")));
        appendAttr(sb, "bounds", boundsText(n.optJSONArray("bounds")));
        appendFalseAttr(sb, "visible", n.optBoolean("visible", true));
        appendFalseAttr(sb, "enabled", n.optBoolean("enabled", true));
        appendTrueAttr(sb, "focused", n.optBoolean("focused", false));
        appendTrueAttr(sb, "clickable", n.optBoolean("clickable", false));
        appendTrueAttr(sb, "longClickable", n.optBoolean("longClickable", false));
        appendTrueAttr(sb, "scrollable", n.optBoolean("scrollable", false));
        appendTrueAttr(sb, "editable", n.optBoolean("editable", false));
        appendTrueAttr(sb, "checkable", n.optBoolean("checkable", false));
        appendTrueAttr(sb, "checked", n.optBoolean("checked", false));
        appendTrueAttr(sb, "selected", n.optBoolean("selected", false));
        JSONObject range = n.optJSONObject("range");
        if (range != null) {
            appendAttr(sb, "range", rangeText(range));
        }
        JSONArray marks = n.optJSONArray("marks");
        if (marks != null && marks.length() > 0) {
            appendAttr(sb, "marks", marks.toString());
        }
        if (children == null || children.isEmpty()) {
            sb.append(" />\n");
            return;
        }
        sb.append(">\n");
        for (JSONObject child : children) {
            renderNodeHtml(sb, child, byParent, rendered, depth + 1);
        }
        indent(sb, depth).append("</node>\n");
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb;
    }

    private static void appendTrueAttr(StringBuilder sb, String name, boolean value) {
        if (value) {
            appendAttr(sb, name, true);
        }
    }

    private static void appendFalseAttr(StringBuilder sb, String name, boolean value) {
        if (!value) {
            appendAttr(sb, name, false);
        }
    }

    private static void appendAttr(StringBuilder sb, String name, Object value) {
        if (value == null || value == JSONObject.NULL) {
            return;
        }
        String s = String.valueOf(value);
        if (s.isEmpty()) {
            return;
        }
        sb.append(' ').append(name).append("=\"").append(escapeXml(truncate(s, 180))).append('"');
    }

    private static String boundsText(JSONArray b) {
        if (b == null || b.length() < 2) {
            return null;
        }
        try {
            JSONArray p1 = b.getJSONArray(0);
            JSONArray p2 = b.getJSONArray(1);
            return p1.getInt(0) + "," + p1.getInt(1) + "," + p2.getInt(0) + "," + p2.getInt(1);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String rangeText(JSONObject range) {
        Object current = range.has("currentValue") ? range.opt("currentValue") : range.opt("current");
        Object percent = range.opt("currentPercent");
        return "type=" + range.opt("type")
                + " min=" + range.opt("min")
                + " max=" + range.opt("max")
                + " value=" + current
                + (percent != null && percent != JSONObject.NULL ? " percent=" + percent : "");
    }

    private static String shortClassName(String cls) {
        if (cls == null || cls.isEmpty()) {
            return null;
        }
        int idx = cls.lastIndexOf('.');
        return idx >= 0 && idx + 1 < cls.length() ? cls.substring(idx + 1) : cls;
    }

    private static String firstNonEmpty(String a, String b) {
        String aa = nonEmpty(a);
        return aa != null ? aa : nonEmpty(b);
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private CompletableFuture<JSONObject> uiFocused(JSONObject p) {
        MyAccessibilityService acc = MyAccessibilityService.getInstance();
        if (acc == null) {
            return CompletableFuture.completedFuture(errObj("no_a11y", "AccessibilityService"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("focused", "not_implemented");
                return o;
            } catch (Exception e) {
                return errObj("ui_focused", e.getMessage());
            }
        });
    }

    private CompletableFuture<JSONObject> uiInputText(JSONObject p) {
        MyAccessibilityService acc = MyAccessibilityService.getInstance();
        if (acc == null) {
            return CompletableFuture.completedFuture(errObj("no_a11y", "AccessibilityService"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("text", acc.getFocusedInputText());
                return o;
            } catch (Exception e) {
                return errObj("ui_input_text", e.getMessage());
            }
        });
    }

    private CompletableFuture<JSONObject> sensorCameraCapture(JSONObject p) {
        String defPath = SensorCameraCapture.defaultOutputPath();
        String optPath = p.optString("path", defPath);
        final String path = (optPath == null || optPath.isEmpty()) ? defPath : optPath;
        int facing = p.optInt("facing", 0);
        int quality = p.optInt("quality", 90);
        return CompletableFuture.supplyAsync(() -> crToJson(
                SensorCameraCapture.captureStill(mContext, path, facing, quality)));
    }

    private CompletableFuture<JSONObject> shotFull(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int q = p.optInt("quality", 85);
        boolean returnBase64 = p.optBoolean("returnBase64", false);
        String defPath = "/sdcard/Pictures/aohp_shot_" + System.currentTimeMillis() + ".jpg";
        // Always honor "path" when set (e.g. CLI -O) so the agent writes with app UID; otherwise
        // a second client-side write from the sandbox can create unreadable files (EACCES).
        String optPath = p.optString("path", defPath);
        final String path = (optPath == null || optPath.isEmpty()) ? defPath : optPath;
        return CompletableFuture.supplyAsync(() -> {
            ShellExecutor.CommandResult r = mAgentView.captureDisplayToFile(displayId, path, q);
            if (returnBase64 && r.success) {
                return shotResultWithBase64(path, r);
            }
            return crToJson(r);
        });
    }

    private CompletableFuture<JSONObject> shotRegion(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int l = p.optInt("left");
        int t = p.optInt("top6");
        int r = p.optInt("right");
        int b = p.optInt("bottom");
        int q = p.optInt("quality", 85);
        boolean returnBase64 = p.optBoolean("returnBase64", false);
        String defPath = "/sdcard/Pictures/aohp_region_" + System.currentTimeMillis() + ".jpg";
        String optPath = p.optString("path", defPath);
        final String path = (optPath == null || optPath.isEmpty()) ? defPath : optPath;
        return CompletableFuture.supplyAsync(() -> {
            ShellExecutor.CommandResult res = mAgentView.captureRegionToFile(displayId, l, t, r, b, path, q);
            if (returnBase64 && res.success) {
                return shotResultWithBase64(path, res);
            }
            return crToJson(res);
        });
    }

    private CompletableFuture<JSONObject> shotNode(JSONObject p) {
        int displayId = resolveDisplayId(p);
        int nodeId = p.optInt("nodeId", -1);
        int flags = p.optInt("flags", 0);
        int q = p.optInt("quality", 85);
        boolean returnBase64 = p.optBoolean("returnBase64", false);
        String defPath = "/sdcard/Pictures/aohp_node_" + System.currentTimeMillis() + ".jpg";
        String optPath = p.optString("path", defPath);
        final String path = (optPath == null || optPath.isEmpty()) ? defPath : optPath;
        if (!mVd.useAohpForDisplayOps()) {
            return CompletableFuture.completedFuture(
                    errObj("no_aohp_vd", "aohp_virtual_display service not available"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellExecutor.CommandResult dr = mVd.dumpUiTree(displayId, flags);
                if (!dr.success) {
                    return errObj("no_aohp_vd", dr.error != null ? dr.error : "dump failed");
                }
                String tree = dr.output;
                int[] rect = boundsFromTree(tree, nodeId);
                if (rect == null) {
                    return errObj("node_not_found", "id=" + nodeId);
                }
                ShellExecutor.CommandResult res = mAgentView.captureRegionToFile(displayId,
                        rect[0], rect[1], rect[2], rect[3], path, q);
                if (returnBase64 && res.success) {
                    return shotResultWithBase64(path, res);
                }
                return crToJson(res);
            } catch (Exception e) {
                return errObj("shot_node", e.getMessage());
            }
        });
    }

    private JSONObject shotResultWithBase64(String path, ShellExecutor.CommandResult cr) {
        try {
            File f = new File(path);
            byte[] buf = readAll(f);
            JSONObject o = new JSONObject();
            o.put("base64", Base64.encodeToString(buf, Base64.NO_WRAP));
            o.put("path", path);
            return o;
        } catch (Exception e) {
            return errObj("shot_base64", e.getMessage());
        }
    }

    private int[] boundsFromTree(String treeJson, int tempId) {
        try {
            JSONArray nodes;
            if (treeJson.trim().startsWith("{")) {
                nodes = new JSONObject(treeJson).getJSONArray("nodes");
            } else {
                nodes = new JSONArray(treeJson);
            }
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                int nid = n.optInt("id", -1);
                if (nid < 0) {
                    nid = n.optInt("temp_id", -1);
                }
                if (nid == tempId) {
                    JSONArray b = n.getJSONArray("bounds");
                    JSONArray p1 = b.getJSONArray(0);
                    JSONArray p2 = b.getJSONArray(1);
                    int l = p1.getInt(0), t = p1.getInt(1);
                    int r = p2.getInt(0), bt = p2.getInt(1);
                    return new int[]{l, t, r, bt};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            in.read(b);
            return b;
        }
    }

    private CompletableFuture<JSONObject> appList(JSONObject p) {
        boolean third = p.optBoolean("thirdParty", false);
        CompletableFuture<ShellExecutor.CommandResult> f =
                third ? mShell.getThirdPartyPackages() : mShell.getAllInstalledPackages();
        return f.thenApply(this::crToJson);
    }

    private CompletableFuture<JSONObject> appInfo(JSONObject p) {
        String pkg = p.optString("packageName", "");
        return mShell.getPackageMainActivity(pkg).thenApply(this::crToJson);
    }

    private CompletableFuture<JSONObject> appStart(JSONObject p) {
        int displayId = resolveDisplayId(p);
        String pkg = p.optString("packageName", "");
        String action = p.optString("action", "");
        String data = p.optString("data", p.optString("dataUri", ""));
        String mimeType = p.optString("mimeType", p.optString("type", ""));
        if (!action.isEmpty() && !data.isEmpty()) {
            return mShell.startAppWithIntent(displayId, pkg, action, data, mimeType)
                    .thenApply(this::crToJson);
        }
        return mShell.launchAppOnDisplay(displayId, pkg, null).thenApply(this::crToJson);
    }

    private CompletableFuture<JSONObject> appKill(JSONObject p) {
        int displayId = resolveDisplayId(p);
        String pkg = p.optString("packageName", "");
        return mShell.forceStopApp(displayId, pkg).thenApply(this::crToJson);
    }

    private CompletableFuture<JSONObject> appForeground(JSONObject p) {
        int displayId = resolveDisplayId(p);
        if (displayId == Display.DEFAULT_DISPLAY) {
            return mShell.getCurrentForegroundPackage().thenApply(this::crToJson);
        }
        return CompletableFuture.completedFuture(errObj("unsupported", "use display 0 or snapshot"));
    }

    private JSONObject appRunning() throws JSONException {
        return displayList();
    }

    private CompletableFuture<JSONObject> sysClipboard(JSONObject p) {
        MyAccessibilityService acc = MyAccessibilityService.getInstance();
        String op = p.optString("op", "get");
        if (acc == null) {
            return CompletableFuture.completedFuture(errObj("no_a11y", "AccessibilityService"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject o = new JSONObject();
                if ("set".equals(op)) {
                    acc.setClipboardText(p.getString("text"));
                    o.put("ok", true);
                } else {
                    o.put("text", acc.getClipboardText());
                }
                return o;
            } catch (Exception e) {
                return errObj("clipboard", e.getMessage());
            }
        });
    }

    private CompletableFuture<JSONObject> sysNotifications(JSONObject p) {
        String op = p.optString("op", "expand");
        CompletableFuture<ShellExecutor.CommandResult> f =
                "expand".equals(op)
                        ? mShell.expandNotificationPanel()
                        : mShell.collapseNotificationPanel();
        return f.thenApply(this::crToJson);
    }

    private JSONObject smsSend(JSONObject p) {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return errObj("permission", "SEND_SMS not granted");
        }
        try {
            String body = p.getString("body");
            if (body == null || body.trim().isEmpty()) {
                return errObj("bad_args", "body is required");
            }
            String address = resolveSmsAddress(p);
            if (address == null || address.trim().isEmpty()) {
                return errObj("bad_args", "address or contactName required");
            }
            SmsManager sms = SmsManager.getDefault();
            if (sms == null) {
                return errObj("sms", "SmsManager unavailable");
            }
            sms.sendTextMessage(address, null, body, null, null);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("address", address);
            o.put("body", body);
            return o;
        } catch (JSONException e) {
            return errObj("bad_args", e.getMessage());
        } catch (Exception e) {
            return errObj("sms", e.getMessage());
        }
    }

    private String resolveSmsAddress(JSONObject p) throws JSONException {
        if (p.has("address")) {
            String address = p.getString("address").trim();
            if (!address.isEmpty()) {
                return normalizePhone(address);
            }
        }
        String contactName = p.optString("contactName", "").trim();
        if (contactName.isEmpty() && p.has("to")) {
            contactName = p.getString("to").trim();
        }
        if (contactName.isEmpty()) {
            throw new JSONException("address or contactName required");
        }
        if (looksLikePhone(contactName)) {
            return normalizePhone(contactName);
        }
        String fromContacts = lookupContactPhone(contactName);
        if (fromContacts != null) {
            return fromContacts;
        }
        throw new JSONException("contact not found: " + contactName);
    }

    private static boolean looksLikePhone(String value) {
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits >= 7;
    }

    private static String normalizePhone(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+' || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String lookupContactPhone(String displayName) {
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
        String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?";
        try (Cursor c = mContext.getContentResolver().query(
                uri, projection, selection, new String[]{displayName}, null)) {
            if (c != null && c.moveToFirst()) {
                return normalizePhone(c.getString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "lookupContactPhone exact", e);
        }
        try (Cursor c = mContext.getContentResolver().query(
                uri,
                projection,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + displayName + "%"},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (c != null && c.moveToFirst()) {
                return normalizePhone(c.getString(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "lookupContactPhone like", e);
        }
        return null;
    }

    private JSONObject eventRegister(JSONObject p) {
        String clientId = p.optString("clientId", "aohp-cli");
        JSONObject opts = new JSONObject();
        copyIfPresent(p, opts, "maxEvents");
        copyIfPresent(p, opts, "ttlMs");
        copyIfPresent(p, opts, "captureScreenshots");
        copyIfPresent(p, opts, "screenshotQuality");
        return mEventStream.register(clientId, opts);
    }

    private JSONObject eventDrain(JSONObject p) {
        String sessionId = p.optString("sessionId", "");
        JSONObject opts = new JSONObject();
        copyIfPresent(p, opts, "includeScreenshots");
        copyIfPresent(p, opts, "inlineScreenshots");
        copyIfPresent(p, opts, "maxEvents");
        JSONObject result = mEventStream.drain(sessionId, opts);
        resolveVaultTokensInDrain(result);
        return result;
    }

    private void resolveVaultTokensInDrain(JSONObject drainResult) {
        if (drainResult == null || !drainResult.optBoolean("ok", false)) {
            return;
        }
        JSONArray events = drainResult.optJSONArray("events");
        if (events == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                continue;
            }
            if (resolveVaultField(event, "text")) {
                changed = true;
            }
            JSONObject notification = event.optJSONObject("notification");
            if (notification != null) {
                if (resolveVaultField(notification, "title")
                        | resolveVaultField(notification, "text")
                        | resolveVaultField(notification, "bigText")) {
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        try {
            drainResult.put("events", events);
            drainResult.put("summary", summarizeDrainEvents(events));
        } catch (JSONException ignored) {
        }
    }

    private boolean resolveVaultField(JSONObject obj, String key) {
        String text = obj.optString(key, "");
        if (text.isEmpty() || !text.contains("aohp://vault/")) {
            return false;
        }
        String resolved = mSecurityBridge.resolveVaultReference(text);
        if (resolved.equals(text)) {
            return false;
        }
        try {
            obj.put(key, resolved);
            obj.put("aohpVaultResolved", true);
            return true;
        } catch (JSONException ignored) {
            return false;
        }
    }

    private static String summarizeDrainEvents(JSONArray events) {
        if (events.length() == 0) {
            return "No new AOHP events.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) {
                continue;
            }
            sb.append('#').append(e.optLong("seq"))
                    .append(' ').append(e.optString("type"))
                    .append(" display=").append(e.optInt("displayId", -1))
                    .append(' ').append(e.optString("packageName", ""));
            String text = e.optString("text", "");
            if (!text.isEmpty()) {
                sb.append(": ").append(text);
            }
            if (i + 1 < events.length()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private JSONObject eventUnregister(JSONObject p) {
        return mEventStream.unregister(p.optString("sessionId", ""));
    }

    private JSONObject eventStatus() {
        return mEventStream.status();
    }

    private static void copyIfPresent(JSONObject src, JSONObject dst, String key) {
        if (!src.has(key)) {
            return;
        }
        try {
            dst.put(key, src.opt(key));
        } catch (JSONException ignored) {
        }
    }

    private CompletableFuture<JSONObject> wrapFuture(CompletableFuture<ShellExecutor.CommandResult> f) {
        return f.thenApply(this::crToJson);
    }

    private JSONObject sandboxList() throws JSONException {
        String[] names = mContainer.listContainers();
        JSONArray a = new JSONArray();
        for (String n : names) {
            a.put(n);
        }
        JSONObject o = new JSONObject();
        o.put("containers", a);
        return o;
    }

    private JSONObject sandboxCreate(JSONObject p) throws JSONException {
        String name = p.getString("name");
        String tpl = p.optString("template", "debian");
        ShellExecutor.CommandResult r = mContainer.createContainer(name, tpl);
        return crToJson(r);
    }

    private JSONObject sandboxDestroy(JSONObject p) throws JSONException {
        ShellExecutor.CommandResult r = mContainer.destroyContainer(p.getString("name"));
        return crToJson(r);
    }

    private JSONObject sandboxReset(JSONObject p) throws JSONException {
        ShellExecutor.CommandResult r = mContainer.resetContainer(p.getString("name"));
        return crToJson(r);
    }

    private JSONObject sandboxExec(JSONObject p) throws JSONException {
        String name = p.getString("name");
        String cmd = p.getString("command");
        int to = p.optInt("timeoutMs", 30000);
        ShellExecutor.CommandResult r = mContainer.execSync(name, cmd, to);
        return crToJson(r);
    }

    // ---- secrets (Android Keystore-backed store for provider keys used inside sandboxes) ----

    private JSONObject secretGet(JSONObject p) throws JSONException {
        String name = p.optString("name", "");
        if (!SecretStore.isValidName(name)) return errObj("bad_args", "invalid secret name");
        String v = mSecrets.get(name);
        if (v == null) return errObj("not_found", "no secret named " + name);
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("value", v);
        return o;
    }

    private JSONObject secretSet(JSONObject p) throws JSONException {
        String name = p.optString("name", "");
        if (!SecretStore.isValidName(name)) return errObj("bad_args", "invalid secret name");
        if (!p.has("value") || p.isNull("value")) return errObj("bad_args", "value required");
        boolean ok = mSecrets.set(name, p.getString("value"));
        if (!ok) return errObj("bad_args", "value rejected");
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("name", name);
        return o;
    }

    private JSONObject secretDelete(JSONObject p) throws JSONException {
        String name = p.optString("name", "");
        if (!SecretStore.isValidName(name)) return errObj("bad_args", "invalid secret name");
        JSONObject o = new JSONObject();
        o.put("ok", mSecrets.delete(name));
        o.put("name", name);
        return o;
    }

    private JSONObject secretList() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("names", new JSONArray(mSecrets.list()));
        return o;
    }

    private JSONObject sandboxSvcStart(JSONObject p) throws JSONException {
        long id = mContainer.startService(
                p.getString("name"), p.getString("serviceId"), p.getString("command"));
        JSONObject o = new JSONObject();
        o.put("pidOrHandle", id);
        return o;
    }

    private JSONObject sandboxSvcStop(JSONObject p) throws JSONException {
        boolean ok = mContainer.stopService(p.getString("name"), p.getString("serviceId"));
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        return o;
    }

    private JSONObject sandboxSvcList(JSONObject p) throws JSONException {
        String j = mContainer.listServicesJson(p.getString("name"));
        JSONObject o = new JSONObject();
        try {
            o.put("services", new JSONArray(j));
        } catch (JSONException e) {
            o.put("servicesRaw", j);
        }
        return o;
    }

    private JSONObject sandboxSvcLog(JSONObject p) throws JSONException {
        String log = mContainer.serviceLog(p.getString("name"), p.getString("serviceId"),
                p.optInt("tailBytes", 8192));
        JSONObject o = new JSONObject();
        o.put("log", log);
        return o;
    }

    private JSONObject sandboxDiag(JSONObject p) throws JSONException {
        String name = p.getString("name");
        JSONObject o = new JSONObject();
        o.put("templateInfo", mContainer.templateInfo(name));
        org.aohp.agentdriver.executor.CgroupUsage u = mContainer.getUsage(name);
        JSONObject usage = new JSONObject();
        usage.put("summary", u.formatShort());
        usage.put("cgroupEnabled", u.cgroupEnabled);
        usage.put("memoryCurrent", u.memoryCurrent);
        o.put("usage", usage);
        o.put("diagnose", new JSONObject(mContainer.diagnose(name)));
        return o;
    }

    private JSONObject udaConfigGet() throws JSONException {
        return mUda.configGet();
    }

    private JSONObject udaConfigSet(JSONObject p) throws JSONException {
        return mUda.configSet(p);
    }

    private JSONObject udaGenerate(JSONObject p) throws JSONException {
        return mUda.generate(p);
    }

    private JSONObject udaInputInit(JSONObject p) throws JSONException {
        return mUda.inputInit(p);
    }

    private JSONObject udaInputWrite(JSONObject p) throws JSONException {
        return mUda.inputWrite(p);
    }

    private JSONObject udaStatus(JSONObject p) throws JSONException {
        return mUda.status(p);
    }

    private JSONObject udaList() throws JSONException {
        return mUda.listJobsJson();
    }

    private JSONObject udaDelete(JSONObject p) throws JSONException {
        return mUda.deleteJob(p);
    }

    private JSONObject udaPreview(JSONObject p) throws JSONException {
        return mUda.preview(p);
    }

    private JSONObject udaLaunch(JSONObject p) throws JSONException {
        return mUda.launch(p);
    }

    private JSONObject udaInstall(JSONObject p) throws JSONException {
        return mUda.install(p);
    }

    private JSONObject udaUninstall(JSONObject p) throws JSONException {
        return mUda.uninstall(p);
    }

    private JSONObject udaUnpin(JSONObject p) throws JSONException {
        return mUda.unpin(p);
    }

    private JSONObject udaPin(JSONObject p) throws JSONException {
        return mUda.pin(p);
    }

    private JSONObject crToJson(ShellExecutor.CommandResult r) {
        try {
            JSONObject o = new JSONObject();
            o.put("success", r.success);
            o.put("exitCode", r.exitCode);
            o.put("stdout", r.output != null ? r.output : "");
            o.put("stderr", r.error != null ? r.error : "");
            return o;
        } catch (JSONException e) {
            return errObj("json", e.getMessage());
        }
    }

    private JSONObject okMsg(String s) {
        try {
            JSONObject o = new JSONObject();
            o.put("message", s);
            o.put("ok", s.startsWith("OK") || !s.startsWith("FAILED"));
            return o;
        } catch (JSONException e) {
            return errObj("okmsg", e.getMessage());
        }
    }

    private JSONObject overlayTaskStart(JSONObject p) throws JSONException {
        String runId = p.optString("runId", "");
        String task = p.optString("task", "");
        String title = p.optString("title", "");
        return AgentOverlayManager.getInstance(mContext).taskStart(runId, task, title);
    }

    private JSONObject overlayEventPush(JSONObject p) throws JSONException {
        String runId = p.optString("runId", "");
        JSONArray events = p.optJSONArray("events");
        if (events == null) {
            events = new JSONArray();
        }
        return AgentOverlayManager.getInstance(mContext).eventPush(runId, events);
    }

    private JSONObject overlayState(JSONObject p) throws JSONException {
        String runId = p.optString("runId", "");
        String state = p.optString("state", "");
        return AgentOverlayManager.getInstance(mContext).setState(runId, state);
    }

    private JSONObject overlayTaskFinish(JSONObject p) throws JSONException {
        String runId = p.optString("runId", "");
        String status = p.optString("status", "finished");
        String summary = p.optString("summary", "");
        return AgentOverlayManager.getInstance(mContext).taskFinish(runId, status, summary);
    }

    private JSONObject overlayHide(JSONObject p) throws JSONException {
        String runId = p.optString("runId", "");
        return AgentOverlayManager.getInstance(mContext).hide(runId);
    }

    private JSONObject overlayTapShow(JSONObject p) throws JSONException {
        int x = p.optInt("x", -1);
        int y = p.optInt("y", -1);
        if (x < 0 || y < 0) {
            return errObj("invalid_params", "x and y are required");
        }
        int radius = p.optInt("radius", 16);
        int durationMs = p.optInt("durationMs", 500);
        return TapHighlightManager.getInstance(mContext).show(x, y, radius, durationMs);
    }

    private JSONObject overlayTapHide(JSONObject p) throws JSONException {
        return TapHighlightManager.getInstance(mContext).hide();
    }

    private JSONObject errObj(String code, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", true);
            o.put("code", code);
            o.put("message", msg);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private JSONObject err(String code, String msg) {
        try {
            JSONObject e = new JSONObject();
            e.put("code", code);
            e.put("message", msg);
            return e;
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private void send(WebSocket conn, String id, boolean ok, JSONObject result, JSONObject error) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("ok", ok);
            if (ok && result != null) {
                o.put("result", result);
            }
            if (!ok && error != null) {
                o.put("error", error);
            }
            conn.send(o.toString());
        } catch (JSONException e) {
            Log.e(TAG, "send", e);
        }
    }
}
