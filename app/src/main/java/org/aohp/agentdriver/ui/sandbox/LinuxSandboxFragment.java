package org.aohp.agentdriver.ui.sandbox;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Display;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.aohp.agentdriver.R;
import org.aohp.agentdriver.executor.AohpContainerClient;
import org.aohp.agentdriver.executor.AohpServiceInfo;
import org.aohp.agentdriver.executor.AohpVdClient;
import org.aohp.agentdriver.executor.MultiVirtualDisplayManager;
import org.aohp.agentdriver.executor.ShellExecutor;
import org.aohp.agentdriver.ui.aohp.AohpDisplaySnapshotAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class LinuxSandboxFragment extends Fragment implements EnvAdapter.EnvActionListener {

    private AohpContainerClient containerClient;
    /**
     * 与视图生命周期绑定：Navigation 切换 Tab 时往往只走 {@link #onDestroyView()} 而保留 Fragment
     * 实例，若在 onDestroyView 里 shutdown 固定字段的线程池，再次 onViewCreated 会复用已终止的
     * Executor，触发 RejectedExecutionException。因此在线程池在 onViewCreated 创建、在
     * onDestroyView 关闭。
     */
    private ExecutorService bgExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger envCounter = new AtomicInteger(0);

    private final Runnable usagePollRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (!isResumed()) {
                        return;
                    }
                    if (bgExecutor == null || bgExecutor.isShutdown()) {
                        return;
                    }
                    String selected = envAdapter != null ? envAdapter.getSelectedEnv() : null;
                    if (selected != null && containerClient != null && containerClient.isServiceAvailable()) {
                        bgExecutor.execute(
                                () -> {
                                    String js = containerClient.listServicesJson(selected);
                                    List<AohpServiceInfo> list = AohpServiceInfo.listFromJson(js);
                                    mainHandler.post(
                                            () -> {
                                                if (serviceAdapter != null) {
                                                    serviceAdapter.setData(list);
                                                }
                                            });
                                });
                    }
                    if (isResumed()) {
                        mainHandler.postDelayed(this, 2000);
                    }
                }
            };

    private RecyclerView rvEnvList;
    private EnvAdapter envAdapter;
    private RecyclerView rvServices;
    private SandboxServiceAdapter serviceAdapter;
    private TextView tvTerminalOutput;
    private TextView tvTerminalTitle;
    private TextView tvServiceStatus;
    private TerminalScrollView svTerminal;
    private EditText etCommandInput;
    private MaterialButton btnCreateEnv;
    private MaterialButton btnCliContactTest;
    private MaterialButton btnSendCommand;
    private MaterialButton btnExportTerminal;
    private MaterialButton btnClearTerminal;
    private MaterialButton btnStartService;
    private MaterialButton btnStartOpenclawGateway;

    private final AtomicBoolean contactTestRunning = new AtomicBoolean(false);

    /** 无沙盒时自动创建的默认环境名。 */
    private static final String OPENCLAW_AUTO_ENV_NAME = "oc";
    private static final String DEFAULT_TEMPLATE = "debian";

    private static final String OPENCLAW_GATEWAY_SERVICE_ID = "openclaw-gateway";

    private static final long SERVICE_LOG_POLL_MS = 1500L;

    private static final Pattern ANSI_ESCAPE_PATTERN =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    // 联系人 CLI 测试：坐标按虚拟屏比例计算（720x1280 等）
    private static final String CT_FIRST_NAME = "AOHP";
    private static final String CT_PHONE = "012346789";
    /** Create-contact form: First name row (~32% height). */
    private static final float CT_FIRST_NAME_Y_RATIO = 0.32f;
    /**
     * com.android.contacts create form focus order: First name → Last name → Phone.
     * Coordinate taps between 45%–70% hit Last name or the Mobile label spinner, not Phone.
     */
    private static final int CT_TAB_COUNT_TO_PHONE_FIELD = 3;
    private static final int CT_SAVE_BTN_X = 650;
    // 56dp ActionBar 中线；原先 y=105 会贴到 48dp 触控区底边外，导致不命中。
    private static final int CT_SAVE_BTN_Y = 56;
    private static final String[] CT_TEST_PACKAGES = {
            "com.google.android.contacts",
            "com.android.contacts",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.settings",
    };

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_linux_sandbox, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (bgExecutor != null) {
            bgExecutor.shutdownNow();
        }
        bgExecutor = Executors.newSingleThreadExecutor();

        containerClient = new AohpContainerClient(requireContext());

        rvEnvList = view.findViewById(R.id.rv_env_list);
        rvServices = view.findViewById(R.id.rv_services);
        tvTerminalOutput = view.findViewById(R.id.tv_terminal_output);
        tvTerminalTitle = view.findViewById(R.id.tv_terminal_title);
        tvServiceStatus = view.findViewById(R.id.tv_service_status);
        svTerminal = view.findViewById(R.id.sv_terminal);
        etCommandInput = view.findViewById(R.id.et_command_input);
        btnCreateEnv = view.findViewById(R.id.btn_create_env);
        btnCliContactTest = view.findViewById(R.id.btn_cli_contact_test);
        btnSendCommand = view.findViewById(R.id.btn_send_command);
        btnExportTerminal = view.findViewById(R.id.btn_export_terminal);
        btnClearTerminal = view.findViewById(R.id.btn_clear_terminal);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStartOpenclawGateway = view.findViewById(R.id.btn_start_openclaw_gateway);

        setupEnvList();
        setupServiceList();
        setupListeners();
        checkServiceAndRefresh();
    }

    private void setupEnvList() {
        envAdapter = new EnvAdapter();
        envAdapter.setListener(this);
        LinearLayoutManager lm =
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false);
        rvEnvList.setLayoutManager(lm);
        rvEnvList.setAdapter(envAdapter);
    }

    private void setupServiceList() {
        serviceAdapter = new SandboxServiceAdapter();
        serviceAdapter.setListener(
                new SandboxServiceAdapter.Listener() {
                    @Override
                    public void onStop(String serviceId) {
                        String env = envAdapter.getSelectedEnv();
                        if (env == null) {
                            return;
                        }
                        appendTerminal(getString(R.string.terminal_stop_service, serviceId));
                        bgExecutor.execute(
                                () -> {
                                    boolean ok = containerClient.stopService(env, serviceId);
                                    mainHandler.post(
                                            () -> {
                                                appendTerminal(
                                                        getString(
                                                                ok
                                                                        ? R.string
                                                                                .terminal_service_stopped
                                                                        : R.string
                                                                                .terminal_stop_failed));
                                            });
                                });
                    }

                    @Override
                    public void onLog(String serviceId) {
                        String env = envAdapter.getSelectedEnv();
                        if (env == null) {
                            return;
                        }
                        mainHandler.post(() -> showServiceLogViewer(env, serviceId));
                    }
                });
        rvServices.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvServices.setAdapter(serviceAdapter);
    }

    private void setupListeners() {
        btnCreateEnv.setOnClickListener(v -> createNewEnv());

        if (btnCliContactTest != null) {
            btnCliContactTest.setOnClickListener(v -> showCliContactTestDialog());
        }

        btnStartService.setOnClickListener(v -> showStartServiceDialog());

        if (btnStartOpenclawGateway != null) {
            btnStartOpenclawGateway.setOnClickListener(v -> startOpenClawGateway());
        }

        btnSendCommand.setOnClickListener(v -> sendCommand());

        btnExportTerminal.setOnClickListener(v -> exportTerminalLog());

        btnClearTerminal.setOnClickListener(
                v -> {
                    tvTerminalOutput.setText("");
                });

        etCommandInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        sendCommand();
                        return true;
                    }
                    return false;
                });
    }

    /** Writes current terminal text to app external files (adb-friendly path under /sdcard/Android/data/...). */
    private void exportTerminalLog() {
        CharSequence text = tvTerminalOutput.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(requireContext(), R.string.sandbox_toast_no_terminal_export, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        bgExecutor.execute(
                () -> {
                    try {
                        File base = requireContext().getExternalFilesDir("sandbox_logs");
                        if (base == null) {
                            base = new File(requireContext().getFilesDir(), "sandbox_logs");
                        }
                        if (!base.isDirectory() && !base.mkdirs()) {
                            mainHandler.post(
                                    () ->
                                            Toast.makeText(
                                                            requireContext(),
                                                            getString(R.string.sandbox_toast_export_dir_failed),
                                                            Toast.LENGTH_SHORT)
                                                    .show());
                            return;
                        }
                        String ts =
                                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                        .format(new Date());
                        File out = new File(base, "sandbox_terminal_" + ts + ".log");
                        try (OutputStreamWriter w =
                                new OutputStreamWriter(
                                        new FileOutputStream(out), StandardCharsets.UTF_8)) {
                            w.write(text.toString());
                        }
                        String adbHint =
                                getString(
                                        R.string.sandbox_export_adb_hint,
                                        out.getAbsolutePath(),
                                        base.getAbsolutePath());
                        mainHandler.post(
                                () ->
                                        Toast.makeText(
                                                        requireContext(),
                                                        getString(
                                                                R.string.sandbox_toast_exported,
                                                                out.getAbsolutePath(),
                                                                adbHint),
                                                        Toast.LENGTH_LONG)
                                                .show());
                    } catch (Exception e) {
                        mainHandler.post(
                                () ->
                                        Toast.makeText(
                                                        requireContext(),
                                                        getString(
                                                                R.string.sandbox_toast_export_failed,
                                                                e.getMessage()),
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(usagePollRunnable);
        mainHandler.post(usagePollRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(usagePollRunnable);
    }

    private void checkServiceAndRefresh() {
        executeBackground(
                () -> {
                    boolean available = containerClient.isServiceAvailable();
                    mainHandler.post(
                            () -> {
                                if (!isViewActive()) {
                                    return;
                                }
                                if (available) {
                                    tvServiceStatus.setText(R.string.sandbox_container_connected);
                                    tvServiceStatus.setTextColor(0xFF4CAF50);
                                } else {
                                    tvServiceStatus.setText(R.string.sandbox_container_unavailable);
                                    tvServiceStatus.setTextColor(0xFFF44336);
                                }
                            });
                    if (available) {
                        refreshEnvList();
                    }
                });
    }

    private void refreshEnvList() {
        executeBackground(
                () -> {
                    String[] containers = containerClient.listContainers();
                    List<String> list = new java.util.ArrayList<>(java.util.Arrays.asList(containers));
                    mainHandler.post(
                            () -> {
                                if (!isViewActive()) {
                                    return;
                                }
                                envAdapter.setData(list);
                                int max = 0;
                                for (String name : list) {
                                    if (name.startsWith("env-")) {
                                        try {
                                            int n = Integer.parseInt(name.substring(4));
                                            if (n > max) {
                                                max = n;
                                            }
                                        } catch (NumberFormatException ignored) {
                                        }
                                    }
                                }
                                envCounter.set(max);

                                if (envAdapter.getSelectedEnv() == null && !list.isEmpty()) {
                                    onSelect(list.get(0));
                                }
                            });
                });
    }

    private boolean executeBackground(Runnable task) {
        ExecutorService executor = bgExecutor;
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        executor.execute(task);
        return true;
    }

    private boolean isViewActive() {
        return isAdded() && getView() != null && bgExecutor != null && !bgExecutor.isShutdown();
    }

    private void createNewEnv() {
        btnCreateEnv.setEnabled(false);
        appendTerminal(getString(R.string.terminal_creating_env));

        executeBackground(
                () -> {
                    int num = envCounter.incrementAndGet();
                    String name = "env-" + num;
                    ShellExecutor.CommandResult result = containerClient.createContainer(name, DEFAULT_TEMPLATE);

                    if (result.success) {
                        mainHandler.post(
                                () -> {
                                    if (!isViewActive()) {
                                        return;
                                    }
                                    appendTerminal(getString(R.string.terminal_env_created, name));
                                    btnCreateEnv.setEnabled(true);
                                    refreshEnvList();
                                    onSelect(name);
                                });
                    } else {
                        mainHandler.post(
                                () -> {
                                    if (!isViewActive()) {
                                        return;
                                    }
                                    btnCreateEnv.setEnabled(true);
                                    appendTerminal(
                                            getString(R.string.terminal_create_failed, result.error));
                                    envCounter.decrementAndGet();
                                });
                    }
                });
    }

    private void sendCommand() {
        String command = etCommandInput.getText().toString().trim();
        if (command.isEmpty()) {
            return;
        }

        // 内置命令拦截：contact-test [--display=<id>]
        if (command.equals("contact-test")
                || command.startsWith("contact-test ")
                || command.startsWith("contact-test\t")) {
            etCommandInput.setText("");
            appendTerminal("$ " + command + "\n");
            handleContactTestBuiltin(command);
            return;
        }
        if (command.equals("contact-test --help") || command.equals("contact-test -h")) {
            etCommandInput.setText("");
            appendTerminal("$ " + command + "\n");
            appendTerminal(getString(R.string.terminal_contact_test_help));
            return;
        }

        String selectedEnv = envAdapter.getSelectedEnv();
        if (selectedEnv == null) {
            Toast.makeText(requireContext(), R.string.sandbox_toast_select_env_first, Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        etCommandInput.setText("");
        appendTerminal("$ " + command + "\n");
        btnSendCommand.setEnabled(false);

        bgExecutor.execute(
                () -> {
                    ShellExecutor.CommandResult result =
                            containerClient.execSync(selectedEnv, command, 30000);

                    mainHandler.post(
                            () -> {
                                btnSendCommand.setEnabled(true);
                                if (result.output != null && !result.output.isEmpty()) {
                                    appendTerminal(result.output);
                                    if (!result.output.endsWith("\n")) {
                                        appendTerminal("\n");
                                    }
                                }
                                if (result.error != null && !result.error.isEmpty()) {
                                    appendTerminalError(result.error);
                                    if (!result.error.endsWith("\n")) {
                                        appendTerminal("\n");
                                    }
                                }
                                if (!result.success
                                        && (result.output == null || result.output.isEmpty())
                                        && (result.error == null || result.error.isEmpty())) {
                                    appendTerminalError("[exit code: " + result.exitCode + "]\n");
                                }
                            });
                });
    }

    private void showStartServiceDialog() {
        String selectedEnv = envAdapter.getSelectedEnv();
        if (selectedEnv == null) {
            Toast.makeText(requireContext(), R.string.sandbox_toast_select_env, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        View form = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_start_service, null);
        EditText etId = form.findViewById(R.id.et_service_id);
        EditText etCmd = form.findViewById(R.id.et_service_command);
        etId.setText("demo-loop");
        etCmd.setText("while true; do date; sleep 10; done");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sandbox_start_service_dialog_title)
                .setView(form)
                .setPositiveButton(
                        R.string.sandbox_dialog_start,
                        (d, w) -> {
                            String sid = etId.getText().toString().trim();
                            String cmd = etCmd.getText().toString().trim();
                            if (sid.isEmpty() || cmd.isEmpty()) {
                                Toast.makeText(
                                                requireContext(),
                                                R.string.sandbox_toast_id_cmd_required,
                                                Toast.LENGTH_SHORT)
                                        .show();
                                return;
                            }
                            appendTerminal(getString(R.string.terminal_start_service, sid));
                            bgExecutor.execute(
                                    () -> {
                                        long pid = containerClient.startService(selectedEnv, sid, cmd);
                                        mainHandler.post(
                                                () ->
                                                        appendTerminal(
                                                                getString(R.string.terminal_pid, pid)));
                                    });
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 一键在沙盒内启动 OpenClaw Gateway：无沙盒时创建名为 oc 的沙盒，否则使用当前选中环境（无效时用列表第一项）。
     * 终端中展示与 CLI 一致的 {@code aohp sandbox svc-start ...} 行，实际通过 {@link AohpContainerClient#startService} 下发。
     */
    private void startOpenClawGateway() {
        if (!containerClient.isServiceAvailable()) {
            Toast.makeText(requireContext(), R.string.sandbox_toast_container_unavailable, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        btnStartOpenclawGateway.setEnabled(false);
        bgExecutor.execute(
                () -> {
                    String[] containers = containerClient.listContainers();
                    if (containers.length == 0) {
                        mainHandler.post(
                                () ->
                                        appendTerminal(
                                                getString(
                                                        R.string.terminal_no_sandbox_creating,
                                                        OPENCLAW_AUTO_ENV_NAME)));
                        ShellExecutor.CommandResult r =
                                containerClient.createContainer(OPENCLAW_AUTO_ENV_NAME, DEFAULT_TEMPLATE);
                        if (!r.success) {
                            mainHandler.post(
                                    () -> {
                                        appendTerminal(
                                                getString(
                                                        R.string.terminal_sandbox_create_failed,
                                                        OPENCLAW_AUTO_ENV_NAME,
                                                        r.error));
                                        btnStartOpenclawGateway.setEnabled(true);
                                    });
                            return;
                        }
                        mainHandler.post(
                                () -> {
                                    appendTerminal(
                                            getString(
                                                    R.string.terminal_sandbox_created,
                                                    OPENCLAW_AUTO_ENV_NAME));
                                    refreshEnvList();
                                });
                        runOpenClawGatewayInEnv(OPENCLAW_AUTO_ENV_NAME);
                        return;
                    }
                    final List<String> names = Arrays.asList(containers);
                    final String fallback = containers[0];
                    mainHandler.post(
                            () -> {
                                String sel =
                                        envAdapter != null ? envAdapter.getSelectedEnv() : null;
                                String target =
                                        (sel != null && names.contains(sel)) ? sel : fallback;
                                bgExecutor.execute(() -> runOpenClawGatewayInEnv(target));
                            });
                });
    }

    /**
     * 在后台线程调用：打印 CLI 等价行并启动服务。{@code innerCmd} 为 chroot 内实际执行的命令。
     */
    private void runOpenClawGatewayInEnv(String envName) {
        final String innerCmd = "openclaw gateway";
        String displayCli =
                "aohp sandbox svc-start -n "
                        + envName
                        + " -i "
                        + OPENCLAW_GATEWAY_SERVICE_ID
                        + " -C \""
                        + innerCmd
                        + "\"";
        mainHandler.post(() -> appendTerminal("$ " + displayCli + "\n"));
        long pid =
                containerClient.startService(envName, OPENCLAW_GATEWAY_SERVICE_ID, innerCmd);
        mainHandler.post(
                () -> {
                    if (pid > 0) {
                        appendTerminal(
                                getString(
                                        R.string.terminal_start_service_pid,
                                        OPENCLAW_GATEWAY_SERVICE_ID,
                                        pid));
                    } else {
                        appendTerminalError(
                                getString(R.string.terminal_start_service_failed, pid));
                    }
                    btnStartOpenclawGateway.setEnabled(true);
                });
    }

    private void appendTerminal(String text) {
        tvTerminalOutput.append(text);
        svTerminal.post(() -> svTerminal.fullScroll(View.FOCUS_DOWN));
    }

    private void appendTerminalError(String text) {
        tvTerminalOutput.append(text);
        svTerminal.post(() -> svTerminal.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 与下方 Terminal 卡片风格一致的后台服务日志查看：等宽、暗色主题、按行着色；可选定时拉取新日志。
     */
    private void showServiceLogViewer(String env, String serviceId) {
        Context ctx = requireContext();
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_service_log, null, false);
        TextView tvTitle = root.findViewById(R.id.tv_service_log_title);
        TextView tvOut = root.findViewById(R.id.tv_service_log_output);
        TerminalScrollView svLog = root.findViewById(R.id.sv_service_log);
        CheckBox cbFollow = root.findViewById(R.id.cb_service_log_auto_refresh);
        tvTitle.setText(serviceId);
        cbFollow.setChecked(true);

        final int svLogBasePaddingBottom = svLog.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(
                svLog,
                (v, insets) -> {
                    int b = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                    v.setPadding(
                            v.getPaddingLeft(),
                            v.getPaddingTop(),
                            v.getPaddingRight(),
                            svLogBasePaddingBottom + b);
                    // 底部 inset 会增大可滚范围；不重新滚到底会导致最后一行留在视口下方
                    if (cbFollow.isChecked()) {
                        v.post(() -> scrollServiceLogToBottom((ScrollView) v));
                    }
                    return insets;
                });
        ViewCompat.requestApplyInsets(svLog);

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.sandbox_service_log_dialog_title)
                        .setView(root)
                        .setNegativeButton(R.string.sandbox_dialog_close, null)
                        .create();

        final Runnable[] pollRef = new Runnable[1];
        pollRef[0] =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing() || !cbFollow.isChecked()) {
                            return;
                        }
                        bgExecutor.execute(
                                () -> {
                                    String log = containerClient.serviceLog(env, serviceId, 65536);
                                    CharSequence styled = styleServiceLog(log);
                                    mainHandler.post(
                                            () -> {
                                                if (!dialog.isShowing() || tvOut == null) {
                                                    return;
                                                }
                                                applyServiceLogStyledContent(
                                                        svLog, tvOut, styled, false);
                                            });
                                });
                        if (dialog.isShowing() && cbFollow.isChecked()) {
                            mainHandler.postDelayed(pollRef[0], SERVICE_LOG_POLL_MS);
                        }
                    }
                };

        cbFollow.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    mainHandler.removeCallbacks(pollRef[0]);
                    if (isChecked && dialog.isShowing()) {
                        mainHandler.post(pollRef[0]);
                    }
                });

        dialog.setOnDismissListener(d -> mainHandler.removeCallbacks(pollRef[0]));
        dialog.show();

        bgExecutor.execute(
                () -> {
                    String log = containerClient.serviceLog(env, serviceId, 65536);
                    CharSequence styled = styleServiceLog(log);
                    mainHandler.post(
                            () -> {
                                if (!dialog.isShowing() || tvOut == null) {
                                    return;
                                }
                                applyServiceLogStyledContent(svLog, tvOut, styled, true);
                                if (cbFollow.isChecked()) {
                                    mainHandler.postDelayed(pollRef[0], SERVICE_LOG_POLL_MS);
                                }
                            });
                });
    }

    /**
     * 若 {@code forceScrollToBottom} 为 true（首次加载），更新后滚到底部。否则仅当用户当前已在底部附近时才跟随滚到底；
     * 否则保持原 {@link ScrollView#getScrollY()}，避免阅读中间内容时被自动刷新打断。
     *
     * <p>注意：{@link TerminalScrollView#requestChildRectangleOnScreen} 已被重写为恒定返回 {@code false}，因此
     * 可选文本 {@link TextView} 的 {@code setText} 不会触发 ScrollView 自动滚到游标位置，这是避免"下滑再滑上去"
     * 闪动的关键前提。
     */
    private static void applyServiceLogStyledContent(
            ScrollView svLog, TextView tvOut, CharSequence styled, boolean forceScrollToBottom) {
        final boolean pinToBottom =
                forceScrollToBottom || isServiceLogScrollAtBottom(svLog, 24);
        final int savedScrollY = svLog.getScrollY();

        tvOut.setText(styled);

        Runnable applyScroll =
                () ->
                        applyServiceLogScrollPosition(
                                svLog, pinToBottom, savedScrollY);
        svLog.post(applyScroll);
        if (pinToBottom) {
            svLog.post(applyScroll);
        }
    }

    /** 将日志 ScrollView 滚到目标位置（底部或保持原滚动）。 */
    private static void applyServiceLogScrollPosition(
            ScrollView svLog, boolean pinToBottom, int savedScrollY) {
        View child = svLog.getChildAt(0);
        if (child == null) {
            return;
        }
        int innerH = svLog.getHeight() - svLog.getPaddingTop() - svLog.getPaddingBottom();
        if (innerH <= 0) {
            return;
        }
        int maxY = Math.max(0, child.getHeight() - innerH);
        int targetY = pinToBottom ? maxY : Math.min(maxY, savedScrollY);
        if (svLog.getScrollY() != targetY) {
            svLog.scrollTo(0, targetY);
        }
    }

    private static void scrollServiceLogToBottom(ScrollView svLog) {
        applyServiceLogScrollPosition(svLog, true, 0);
    }

    private static boolean isServiceLogScrollAtBottom(ScrollView sv, int slopPx) {
        View child = sv.getChildAt(0);
        if (child == null) {
            return true;
        }
        int innerH = sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom();
        if (innerH <= 0) {
            return true;
        }
        int maxScrollY = child.getHeight() - innerH;
        if (maxScrollY <= 0) {
            return true;
        }
        return sv.getScrollY() >= maxScrollY - slopPx;
    }

    private static CharSequence styleServiceLog(String raw) {
        if (raw == null || raw.isEmpty()) {
            SpannableStringBuilder empty = new SpannableStringBuilder("(empty)");
            empty.setSpan(
                    new ForegroundColorSpan(0xFF6C7086),
                    0,
                    empty.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return empty;
        }
        String text = stripAnsiEscapes(raw);
        String[] lines = text.split("\n", -1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            int start = sb.length();
            sb.append(line);
            int color = serviceLogLineColor(line);
            sb.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static String stripAnsiEscapes(String s) {
        return ANSI_ESCAPE_PATTERN.matcher(s).replaceAll("");
    }

    private static int serviceLogLineColor(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.contains("error")
                || l.contains("fatal")
                || l.contains("exception")
                || l.contains("failed")
                || l.contains("traceback")) {
            return 0xFFF38BA8;
        }
        if (l.contains("warn") || l.contains("[警告]") || l.contains("[warn]")) {
            return 0xFFF9E2AF;
        }
        if (l.contains("debug") || l.contains("trace")) {
            return 0xFF6C7086;
        }
        if (l.contains("info")
                || l.contains("[系统]")
                || l.contains("[system]")) {
            return 0xFF89B4FA;
        }
        return 0xFFA6E3A1;
    }

    @Override
    public void onSelect(String name) {
        envAdapter.setSelectedEnv(name);
        tvTerminalTitle.setText(getString(R.string.terminal_title_format, name));
    }

    @Override
    public void onReset(String name) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sandbox_reset_env_title)
                .setMessage(getString(R.string.sandbox_reset_env_message, name))
                .setPositiveButton(
                        R.string.sandbox_env_reset,
                        (dialog, which) -> {
                            appendTerminal(getString(R.string.terminal_resetting_env, name));
                            bgExecutor.execute(
                                    () -> {
                                        ShellExecutor.CommandResult result =
                                                containerClient.resetContainer(name);
                                        mainHandler.post(
                                                () -> {
                                                    if (result.success) {
                                                        appendTerminal(
                                                                getString(R.string.terminal_env_reset, name));
                                                    } else {
                                                        appendTerminalError(
                                                                getString(
                                                                        R.string.terminal_reset_failed,
                                                                        result.error));
                                                    }
                                                    refreshEnvList();
                                                });
                                    });
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    public void onDelete(String name) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sandbox_delete_env_title)
                .setMessage(getString(R.string.sandbox_delete_env_message, name))
                .setPositiveButton(
                        R.string.sandbox_env_delete,
                        (dialog, which) -> {
                            appendTerminal(getString(R.string.terminal_deleting_env, name));
                            bgExecutor.execute(
                                    () -> {
                                        ShellExecutor.CommandResult result =
                                                containerClient.destroyContainer(name);
                                        mainHandler.post(
                                                () -> {
                                                    if (result.success) {
                                                        appendTerminal(
                                                                getString(R.string.terminal_env_deleted, name));
                                                        if (name.equals(envAdapter.getSelectedEnv())) {
                                                            envAdapter.setSelectedEnv(null);
                                                            tvTerminalTitle.setText(
                                                                    R.string.terminal_title_default);
                                                        }
                                                    } else {
                                                        appendTerminalError(
                                                                getString(
                                                                        R.string.terminal_delete_failed,
                                                                        result.error));
                                                    }
                                                    refreshEnvList();
                                                });
                                    });
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ==================== CLI 联系人测试 ====================

    /**
     * 解析并执行 `contact-test` 内置命令；不经过 Alpine 容器。
     */
    private void handleContactTestBuiltin(String rawCommand) {
        int displayId = -1;
        String[] parts = rawCommand.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            try {
                if (arg.startsWith("--display=")) {
                    displayId = Integer.parseInt(arg.substring("--display=".length()));
                } else if (arg.equals("--display") && i + 1 < parts.length) {
                    displayId = Integer.parseInt(parts[++i]);
                } else if (arg.matches("-?\\d+")) {
                    displayId = Integer.parseInt(arg);
                }
            } catch (NumberFormatException e) {
                appendTerminalError(getString(R.string.terminal_parse_display_failed, arg));
                return;
            }
        }

        if (!isAdded() || getContext() == null) {
            return;
        }
        final Context appCtx = requireContext().getApplicationContext();
        final int resolvedDisplayId = displayId;
        bgExecutor.execute(
                () -> {
                    List<Integer> knownIds = computeMergedVirtualDisplayIds(appCtx);
                    mainHandler.post(
                            () -> {
                                if (!isAdded()) {
                                    return;
                                }
                                if (resolvedDisplayId < 0) {
                                    if (knownIds.isEmpty()) {
                                        appendTerminalError(
                                                getString(R.string.terminal_no_virtual_display));
                                        return;
                                    }
                                    appendTerminal(
                                            getString(R.string.terminal_available_vd_ids, knownIds));
                                    appendTerminal(getString(R.string.terminal_contact_test_usage));
                                    return;
                                }
                                if (!knownIds.isEmpty() && !knownIds.contains(resolvedDisplayId)) {
                                    appendTerminal(
                                            getString(
                                                    R.string.terminal_vd_id_warning,
                                                    knownIds,
                                                    resolvedDisplayId));
                                }
                                runContactTestCli(resolvedDisplayId);
                            });
                });
    }

    /**
     * 点击「CLI 测试联系人」按钮时，弹出虚拟屏选择对话框。
     */
    private void showCliContactTestDialog() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        final Context appCtx = requireContext().getApplicationContext();
        if (btnCliContactTest != null) {
            btnCliContactTest.setEnabled(false);
        }
        bgExecutor.execute(
                () -> {
                    List<Integer> ids = computeMergedVirtualDisplayIds(appCtx);
                    mainHandler.post(
                            () -> {
                                if (btnCliContactTest != null) {
                                    btnCliContactTest.setEnabled(true);
                                }
                                if (!isAdded() || getContext() == null) {
                                    return;
                                }
                                if (ids.isEmpty()) {
                                    new MaterialAlertDialogBuilder(requireContext())
                                            .setTitle(R.string.sandbox_contact_test_title)
                                            .setMessage(R.string.sandbox_contact_no_vd_message)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show();
                                    return;
                                }
                                String[] items = new String[ids.size()];
                                for (int i = 0; i < ids.size(); i++) {
                                    items[i] = "display=" + ids.get(i);
                                }
                                final int[] selected = {0};
                                new MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(R.string.sandbox_select_vd_title)
                                        .setSingleChoiceItems(
                                                items, 0, (d, which) -> selected[0] = which)
                                        .setPositiveButton(
                                                R.string.sandbox_start_test,
                                                (d, w) -> runContactTestCli(ids.get(selected[0])))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            });
                });
    }

    /**
     * 合并「本应用通过 Binder 创建并记录的 VD」与「getDisplayRuntimeSnapshotJson 中的逻辑屏」，
     * 从而包含在沙盒 / 终端里用 {@code aohp display create} 等方式创建的虚拟屏。
     */
    private static List<Integer> computeMergedVirtualDisplayIds(Context appCtx) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (int id : MultiVirtualDisplayManager.getInstance(appCtx).getKnownDisplayIds()) {
            if (id != Display.DEFAULT_DISPLAY) {
                set.add(id);
            }
        }
        ShellExecutor.CommandResult r =
                AohpVdClient.getInstance(appCtx).getDisplayRuntimeSnapshotJson(null);
        if (r.success && r.output != null && !r.output.isEmpty()) {
            try {
                for (int id :
                        AohpDisplaySnapshotAdapter.listDisplayIdsFromSnapshotJson(
                                r.output, true)) {
                    set.add(id);
                }
            } catch (JSONException ignored) {
            }
        }
        List<Integer> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }

    /**
     * 把联系人测试所有交互改为向已选沙盒发送 {@code aohp ...} CLI。
     * 每次截图后额外抓取一份 enhanced ui tree JSON；截图在 {@code Pictures/AOHPVirtualDisplay/}，
     * UI 树在 {@code Documents/AOHPVirtualDisplay/}（与 scoped storage 对 JSON 的要求一致）。
     */
    private void runContactTestCli(int displayId) {
        if (!contactTestRunning.compareAndSet(false, true)) {
            appendTerminalError(getString(R.string.terminal_contact_test_busy));
            return;
        }
        if (btnCliContactTest != null) {
            btnCliContactTest.setEnabled(false);
        }

        final String screenshotDir =
                android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES)
                        .getAbsolutePath()
                        + "/AOHPVirtualDisplay";
        // UI tree JSON is not a photo; MediaStore.Files rejects RELATIVE_PATH under Pictures for
        // application/json (allowed top-level dirs: Documents, Download). Keep trees under Documents.
        final String uiTreeDir =
                android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOCUMENTS)
                        .getAbsolutePath()
                        + "/AOHPVirtualDisplay";
        File dirShots = new File(screenshotDir);
        if (!dirShots.exists()) {
            dirShots.mkdirs();
        }
        File dirTrees = new File(uiTreeDir);
        if (!dirTrees.exists()) {
            dirTrees.mkdirs();
        }
        final String timestamp =
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        bgExecutor.execute(
                () -> {
                    final Context appCtx = requireContext().getApplicationContext();
                    List<String> savedShots = new ArrayList<>();
                    List<String> savedTrees = new ArrayList<>();
                    try {
                        String env = resolveSandboxEnv();
                        if (env == null) {
                            return;
                        }

                        ShellExecutor shell = ShellExecutor.getInstance();
                        shell.bindHostContext(requireContext().getApplicationContext());

                        printCli("========== contact-test --display=" + displayId + " ==========");
                        printCliRes(R.string.terminal_cli_sandbox, env);
                        printCliRes(R.string.terminal_cli_shot_dir, screenshotDir);
                        printCliRes(R.string.terminal_cli_ui_tree_dir, uiTreeDir);

                        String pkg = pickInstalledContactsPackage();
                        if (pkg == null) {
                            appendTerminalErrorOnBg(R.string.terminal_no_contacts_package);
                            return;
                        }
                        printCliRes(R.string.terminal_cli_selected_pkg, pkg);

                        int[] metrics = shell.getBuiltinDisplayRealMetricsPx();
                        int width = metrics != null ? metrics[0] : 1080;
                        int height = metrics != null ? metrics[1] : 1920;
                        int maxX = Math.max(0, width - 1);
                        int maxY = Math.max(0, height - 1);
                        int cx = Math.min(Math.max(width / 2, 0), maxX);
                        int cy = Math.min(Math.max(height / 2, 0), maxY);
                        printCliRes(R.string.terminal_cli_display_size, width, height);

                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp, "00_before_launch_xy_na", savedShots, savedTrees);

                        cliDisplayLauncher(env, displayId, pkg);
                        Thread.sleep(2000);

                        cliDisplayFocus(env, pkg);

                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp, "01_after_launch_xy_na", savedShots, savedTrees);

                        // 空列表页右下角 FAB，或已在创建页则无害点击
                        int fabX = Math.min(Math.max((int) (width * 0.88f), 0), maxX);
                        int fabY = Math.min(Math.max((int) (height * 0.90f), 0), maxY);
                        cliTap(env, displayId, fabX, fabY);
                        Thread.sleep(800);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US, "02_tap_fab_xy_x%d_y%d", fabX, fabY),
                                savedShots, savedTrees);

                        int firstNameY = Math.min(Math.max(
                                (int) (height * CT_FIRST_NAME_Y_RATIO), 0), maxY);
                        cliTap(env, displayId, cx, firstNameY);
                        Thread.sleep(400);
                        cliInput(env, displayId, CT_FIRST_NAME);
                        Thread.sleep(500);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US,
                                        "03_input_firstname_%s_xy_x%d_y%d",
                                        CT_FIRST_NAME, cx, firstNameY),
                                savedShots, savedTrees);

                        // act.input types into the focused editable; tap Y≈45% often lands on
                        // Last name or the Mobile label, not Phone. TAB×3 matches focus order.
                        printCliRes(
                                R.string.terminal_cli_focus_tab, CT_TAB_COUNT_TO_PHONE_FIELD);
                        for (int tab = 0; tab < CT_TAB_COUNT_TO_PHONE_FIELD; tab++) {
                            cliKeyTab(env, displayId);
                            Thread.sleep(300);
                        }
                        Thread.sleep(400);
                        ShellExecutor.CommandResult phoneInput =
                                cliInput(env, displayId, CT_PHONE);
                        if (!phoneInput.success || cliStdoutIndicatesFailure(phoneInput.output)) {
                            appendTerminalErrorOnBg(
                                    R.string.terminal_phone_input_failed,
                                    describeErr(phoneInput));
                            return;
                        }
                        Thread.sleep(500);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US,
                                        "04_input_phone_%s_tab%d",
                                        CT_PHONE, CT_TAB_COUNT_TO_PHONE_FIELD),
                                savedShots, savedTrees);

                        int scrollX = cx;
                        int yLow = Math.min(Math.max((int) (height * 0.72f), 0), maxY);
                        int yHigh = Math.min(Math.max((int) (height * 0.28f), 0), maxY);
                        cliSwipe(env, displayId, scrollX, yLow, scrollX, yHigh, 500);
                        Thread.sleep(500);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US,
                                        "05_scroll_up_x%d_y%d_to_x%d_y%d",
                                        scrollX, yLow, scrollX, yHigh),
                                savedShots, savedTrees);

                        cliSwipe(env, displayId, scrollX, yHigh, scrollX, yLow, 500);
                        Thread.sleep(500);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US,
                                        "06_scroll_down_x%d_y%d_to_x%d_y%d",
                                        scrollX, yHigh, scrollX, yLow),
                                savedShots, savedTrees);

                        int sx = Math.min(Math.max(CT_SAVE_BTN_X, 0), maxX);
                        int sy = Math.min(Math.max(CT_SAVE_BTN_Y, 0), maxY);
                        printCliRes(R.string.terminal_cli_tap_save);
                        cliTap(env, displayId, sx, sy);
                        Thread.sleep(800);
                        captureShotAndTree(appCtx, env, displayId, screenshotDir, uiTreeDir,
                                timestamp,
                                String.format(Locale.US, "07_tap_save_xy_x%d_y%d", sx, sy),
                                savedShots, savedTrees);

                        printCliRes(
                                R.string.terminal_cli_test_done,
                                savedShots.size(),
                                savedTrees.size());
                        printCli("# adb pull " + screenshotDir + "/");
                        printCli("# adb pull " + uiTreeDir + "/");
                    } catch (Exception e) {
                        appendTerminalErrorOnBg(
                                R.string.terminal_contact_test_exception, e.getMessage());
                    } finally {
                        if (!savedShots.isEmpty()) {
                            notifyMediaScannerForCli(savedShots);
                        }
                        mainHandler.post(
                                () -> {
                                    if (btnCliContactTest != null) {
                                        btnCliContactTest.setEnabled(true);
                                    }
                                });
                        contactTestRunning.set(false);
                    }
                });
    }

    /** Auto-pick the first sandbox when none is selected; null if no sandbox exists. */
    private String resolveSandboxEnv() {
        String env = envAdapter != null ? envAdapter.getSelectedEnv() : null;
        if (env != null) {
            return env;
        }
        String[] names = containerClient.listContainers();
        if (names != null && names.length > 0) {
            String pick = names[0];
            Context ctx = terminalLocaleContext();
            if (ctx != null) {
                appendTerminalOnBg(ctx.getString(R.string.terminal_auto_select_sandbox, pick));
            }
            mainHandler.post(() -> onSelect(pick));
            return pick;
        }
        appendTerminalErrorOnBg(R.string.terminal_select_sandbox_first);
        return null;
    }

    private void captureShotAndTree(
            Context appCtx, String env, int displayId, String shotDir, String treeDir, String ts,
            String tag, List<String> savedShots, List<String> savedTrees) {
        cliShot(env, displayId, shotPath(shotDir, ts, tag), savedShots);
        cliUiTree(appCtx, env, displayId, treePath(treeDir, ts, tag), savedTrees);
    }

    private String pickInstalledContactsPackage() {
        for (String pkg : CT_TEST_PACKAGES) {
            try {
                requireContext().getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (Exception ignored) {
                // not installed
            }
        }
        return null;
    }

    private String shotPath(String dir, String ts, String tag) {
        return dir + "/" + ts + "_" + tag + ".jpg";
    }

    private String treePath(String dir, String ts, String tag) {
        return dir + "/" + ts + "_" + tag + ".ui.json";
    }

    /** Print an "$ aohp …" line then run the command via the selected sandbox. */
    private ShellExecutor.CommandResult sbxExec(String env, String cliCmd, int timeoutMs) {
        printCli(cliCmd);
        return containerClient.execSync(env, cliCmd, timeoutMs);
    }

    private void cliDisplayLauncher(String env, int displayId, String pkg) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US, "aohp display launcher -d %d -P %s", displayId, pkg),
                20000);
        printCliResult(r);
    }

    private void cliDisplayFocus(String env, String pkg) {
        ShellExecutor.CommandResult r = sbxExec(env,
                "aohp display focus -P " + pkg,
                15000);
        printCliResult(r);
    }

    private void cliTap(String env, int displayId, int x, int y) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US, "aohp act tap -d %d -x %d -y %d", displayId, x, y),
                15000);
        printCliResult(r);
    }

    private void cliSwipe(
            String env, int displayId, int x1, int y1, int x2, int y2, int duration) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US,
                        "aohp act swipe -d %d --x1 %d --y1 %d --x2 %d --y2 %d -t %d",
                        displayId, x1, y1, x2, y2, duration),
                15000);
        printCliResult(r);
    }

    private ShellExecutor.CommandResult cliInput(String env, int displayId, String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US,
                        "aohp act input -d %d -t \"%s\"", displayId, escaped),
                15000);
        printCliResult(r);
        return r;
    }

    private void cliKeyTab(String env, int displayId) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US, "aohp act key -d %d --tab", displayId),
                15000);
        printCliResult(r);
    }

    private void cliShot(String env, int displayId, String jpgPath, List<String> savedShots) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US,
                        "aohp shot full -d %d -O %s", displayId, jpgPath),
                15000);
        if (r.success) {
            savedShots.add(jpgPath);
            appendTerminalOnBg("[ok] saved " + new File(jpgPath).getName() + "\n");
        } else {
            appendTerminalErrorOnBg(R.string.terminal_shot_failed, describeErr(r));
        }
    }

    private void cliUiTree(
            Context appCtx, String env, int displayId, String jsonPath, List<String> savedTrees) {
        ShellExecutor.CommandResult r = sbxExec(env,
                String.format(Locale.US, "aohp ui tree -e -d %d", displayId),
                20000);
        if (r.success && r.output != null && !r.output.isEmpty()) {
            try {
                File f = new File(jsonPath);
                writeUiTreeJson(appCtx, f, r.output);
                savedTrees.add(jsonPath);
                appendTerminalOnBg("[ok] saved " + f.getName() + "\n");
            } catch (Exception e) {
                appendTerminalErrorOnBg(
                        R.string.terminal_ui_tree_write_failed, e.getMessage());
            }
        } else {
            appendTerminalErrorOnBg(R.string.terminal_ui_tree_failed, describeErr(r));
        }
    }

    /**
     * Persist UI tree JSON under {@code Documents/AOHPVirtualDisplay/}. Scoped storage allows
     * {@code .jpg} in {@code Pictures/} for screenshots, but {@link MediaStore.Files} inserts with
     * MIME {@code application/json} may only use primary dirs {@code Documents} or {@code Download}
     * — not {@code Pictures} (MediaProvider rejects "Primary directory Pictures not allowed...").
     */
    private void writeUiTreeJson(Context appCtx, File targetFile, String utf8Content)
            throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(targetFile), StandardCharsets.UTF_8)) {
            w.write(utf8Content);
        } catch (IOException primary) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeUiTreeJsonViaMediaStore(appCtx, targetFile.getName(), utf8Content);
                return;
            }
            throw primary;
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void writeUiTreeJsonViaMediaStore(Context appCtx, String displayName, String utf8Content)
            throws IOException {
        ContentResolver resolver = appCtx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/AOHPVirtualDisplay");
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = resolver.insert(collection, values);
        if (item == null) {
            throw new IOException("MediaStore insert returned null");
        }
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) {
                throw new IOException("MediaStore openOutputStream returned null");
            }
            out.write(utf8Content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 对通用 aohp CLI 调用只打印简洁成功/失败摘要——RPC 返回的 JSON 体积不大但会
     * 污染终端；{@link #cliShot} 与 {@link #cliUiTree} 有各自的确认输出。
     */
    private void printCliResult(ShellExecutor.CommandResult r) {
        if (r == null) {
            return;
        }
        if (r.success && !cliStdoutIndicatesFailure(r.output)) {
            appendTerminalOnBg("[ok]\n");
        } else {
            appendTerminalErrorOnBg("[err] " + describeErr(r) + "\n");
        }
    }

    /** aohp CLI may exit 0 while RPC result JSON has {@code success:false}. */
    private static boolean cliStdoutIndicatesFailure(@Nullable String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(stdout.trim());
            return o.optBoolean("error", false) || !o.optBoolean("success", true);
        } catch (JSONException e) {
            return false;
        }
    }

    private static String describeErr(ShellExecutor.CommandResult r) {
        if (r == null) {
            return "(null result)";
        }
        if (r.error != null && !r.error.isEmpty()) {
            return r.error.trim();
        }
        if (r.output != null && !r.output.isEmpty()) {
            return r.output.trim();
        }
        return "exit=" + r.exitCode;
    }

    private void printCli(String line) {
        appendTerminalOnBg("$ " + line + "\n");
    }

    /** Resolves {@code resId} with the app locale (safe from background executor). */
    private void printCliRes(int resId, Object... formatArgs) {
        Context ctx = terminalLocaleContext();
        if (ctx == null) {
            return;
        }
        printCli(ctx.getString(resId, formatArgs));
    }

    @Nullable
    private Context terminalLocaleContext() {
        if (!isAdded()) {
            return null;
        }
        return requireContext().getApplicationContext();
    }

    private void appendTerminalOnBg(String text) {
        mainHandler.post(
                () -> {
                    if (isAdded() && tvTerminalOutput != null) {
                        appendTerminal(text);
                    }
                });
    }

    private void appendTerminalErrorOnBg(String text) {
        mainHandler.post(
                () -> {
                    if (isAdded() && tvTerminalOutput != null) {
                        appendTerminalError(text);
                    }
                });
    }

    private void appendTerminalErrorOnBg(int resId, Object... formatArgs) {
        Context ctx = terminalLocaleContext();
        if (ctx == null) {
            return;
        }
        appendTerminalErrorOnBg(ctx.getString(resId, formatArgs));
    }

    private void notifyMediaScannerForCli(List<String> paths) {
        if (!isAdded()) {
            return;
        }
        String[] arr = paths.toArray(new String[0]);
        String[] mimes = new String[arr.length];
        Arrays.fill(mimes, "image/jpeg");
        try {
            android.media.MediaScannerConnection.scanFile(
                    requireContext(), arr, mimes, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(usagePollRunnable);
        if (bgExecutor != null) {
            bgExecutor.shutdownNow();
            bgExecutor = null;
        }
    }
}
