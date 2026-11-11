package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Magpie LLM gateway (github.com/yetone/magpie, MIT).
 *
 * Runs the prebuilt linux/arm64 `nogui packedassets` binary inside the PRoot
 * Ubuntu sandbox, listening on 127.0.0.1:3425. The binary is shipped in APK
 * assets and extracted to app-private storage on first use.
 *
 * Design notes:
 * - Reuses PRootKernel.boot() so rootfs/proot install and bind-mount state
 *   are shared with the rest of the sandbox (no duplicate boot logic).
 * - Reuses PRootKernel.buildProotCommand() so the same proxy/env/bind-mount
 *   rules apply as for normal shell commands.
 * - Keeps the class small (<800 lines) per the repo's architecture gate.
 * - Does NOT auto-start on app launch; callers decide when to start it
 *   (e.g. when the user opens a chat that needs a local model).
 */
object MagpieGateway {

    private const val TAG = "MagpieGateway"

    /** Port Magpie listens on inside the PRoot sandbox. */
    const val PORT = 3425

    /** Base URL exposed to the app's provider layer. */
    const val BASE_URL = "http://127.0.0.1:$PORT/v1"

    /** Placeholder API key — Magpie does not validate it, but the provider layer needs a non-empty value. */
    const val API_KEY = "magpie-minis"

    /** Asset name inside the APK. */
    private const val ASSET_NAME = "magpie-linux-arm64"

    /** Private dir where the binary is extracted. */
    private const val INSTALL_DIR = "magpie"

    private val running = AtomicBoolean(false)
    private val startMutex = Mutex()

    @Volatile
    private var process: Process? = null

    /** True once the binary is extracted and the PRoot process is alive. */
    fun isRunning(): Boolean = running.get()

    /**
     * Extract the binary from assets (if needed) and start it inside PRoot.
     * Safe to call repeatedly — a second call while already running is a no-op.
     *
     * @throws IllegalStateException if PRootKernel has not been booted yet.
     */
    suspend fun start(context: Context) {
        if (running.get()) {
            Log.d(TAG, "Already running")
            return
        }
        startMutex.withLock {
            if (running.get()) {
                Log.d(TAG, "Already running (double-checked)")
                return
            }
            withContext(Dispatchers.IO) {
                // PRoot must be booted first — Magpie runs inside the same rootfs.
                check(PRootKernel.isBooted) {
                    "PRootKernel.boot() must be called before MagpieGateway.start()"
                }

                val binary = ensureBinary(context)
                val cmd = buildCommand(binary)

                Log.i(TAG, "Starting Magpie: ${cmd.joinToString(" ")}")
                val pb = ProcessBuilder(cmd).apply {
                    redirectErrorStream(true)
                }
                val proc = pb.start()
                process = proc

                // Give the Go binary a moment to bind the port. A more robust
                // readiness check (HTTP GET /v1/models) can be added later if
                // needed; for now a short sleep + liveness check is enough.
                Thread.sleep(500)
                if (!proc.isAlive) {
                    val output = proc.inputStream.bufferedReader().readText()
                    Log.e(TAG, "Magpie exited immediately. Output: $output")
                    running.set(false)
                    process = null
                    throw IllegalStateException("Magpie failed to start: $output")
                }

                running.set(true)
                Log.i(TAG, "Magpie started on port $PORT")
            }
        }
    }

    /** Stop the Magpie process if it is running. Safe to call repeatedly. */
    fun stop() {
        if (!running.get()) return
        process?.let { proc ->
            try {
                proc.destroy()
                // Give it a moment to exit gracefully before forcing.
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping Magpie: ${t.message}")
            }
        }
        process = null
        running.set(false)
        Log.i(TAG, "Magpie stopped")
    }

    /**
     * Extract the Magpie binary from APK assets into app-private storage.
     * Skips extraction if the file already exists and is non-empty.
     */
    private fun ensureBinary(context: Context): File {
        val dir = File(context.filesDir, INSTALL_DIR)
        dir.mkdirs()
        val target = File(dir, ASSET_NAME)

        if (target.exists() && target.length() > 0) {
            Log.d(TAG, "Magpie binary already extracted: ${target.absolutePath}")
            return target
        }

        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setExecutable(true, false)
        Log.i(TAG, "Extracted Magpie binary to ${target.absolutePath} (${target.length()} bytes)")
        return target
    }

    /**
     * Build the PRoot command that runs Magpie.
     *
     * Reuses PRootKernel.buildProotCommand() so bind mounts, proxy env, and
     * the rootfs layout stay consistent with the rest of the sandbox.
     */
    private fun buildCommand(binary: File): List<String> {
        // Env vars are set via PRootKernel's customEnvironment mechanism, which
        // SandboxHttpProxy and GuestLimits already read from. We append Magpie-
        // specific vars here so they ride along with the same env block.
        val env = PRootKernel.customEnvironment
        env["DO_NOT_TRACK"] = "1"
        env["NO_PROXY"] = "127.0.0.1,localhost"
        env["no_proxy"] = "127.0.0.1,localhost"

        // The actual command run inside PRoot: exec the binary in the background
        // is not needed — PRootKernel already runs the command as the main process.
        val shellCommand = "${binary.absolutePath} --port $PORT --host 127.0.0.1"
        return PRootKernel.buildProotCommand(shellCommand)
    }
}
