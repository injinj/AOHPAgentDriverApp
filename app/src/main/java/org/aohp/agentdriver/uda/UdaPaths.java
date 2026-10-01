package org.aohp.agentdriver.uda;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;

/** Host/container paths for UDAGen workspace (bind-mounted via aohp-containerd). */
public final class UdaPaths {
    public static final String CONTAINER_NAME = "uda";
    public static final String CONTAINER_TEMPLATE = "debian";

    /** Android host shared directory (bind → container workspace). */
    public static final String HOST_SHARED_ROOT = "/data/aohp/shared/uda";

    /** Container-side output root (bind mount of HOST_SHARED_ROOT). */
    public static final String CONTAINER_WORKSPACE = "/opt/udagen/workspace";

    /** Empty input template baked into rootfs. */
    public static final String CONTAINER_INPUT = "/opt/udagen/template-input";

    /** Per-job input subdirectory under {@link #CONTAINER_WORKSPACE}/{@code jobId}/input. */
    @NonNull
    public static String jobInputContainerPath(@NonNull String jobId) {
        return CONTAINER_WORKSPACE + "/" + jobId + "/input";
    }

    /** Host-side per-job input (bind-mounted into the UDA container). */
    @NonNull
    public static String jobInputHostPath(@NonNull String jobId) {
        return HOST_SHARED_ROOT + "/" + jobId + "/input";
    }

    /** PYTHONPATH parent for `python3 -m udagen`. */
    public static final String CONTAINER_PYTHONPATH = "/opt/udagen-lib";

    public static final int MOCK_PORT = 8787;
    public static final int STATIC_PORT = 8790;

    public static final long GENERATION_TIMEOUT_MS = 30L * 60L * 1000L;

    /** Do not treat a missing service entry as failure within this window after job creation. */
    public static final long SERVICE_START_GRACE_MS = 20_000L;

  /** Fallback when {@link #HOST_SHARED_ROOT} is not writable (e.g. SELinux on some builds). */
    @NonNull
    public static File privateJobsRoot(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "uda_jobs");
    }

    /** Resolved output directory: prefer an app-readable tree (private copy over shared host). */
    @NonNull
    public static File resolveJobRoot(@NonNull Context context, @NonNull String jobId) {
        File priv = new File(privateJobsRoot(context), jobId);
        if (UdaHostFiles.isReadableAppIndex(priv)) {
            return priv;
        }
        File host = new File(HOST_SHARED_ROOT, jobId);
        if (UdaHostFiles.isReadableAppIndex(host)) {
            return host;
        }
        return priv;
    }

    public static boolean hasAppIndex(@NonNull Context context, @NonNull String jobId) {
        return UdaHostFiles.isJobReadable(context, jobId);
    }

    private UdaPaths() {}
}
