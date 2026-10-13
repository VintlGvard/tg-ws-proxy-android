package org.flowseal.tgwsproxy.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class NativeProxyRunner(private val context: Context) {

    private var process: Process? = null
    private val isRunning = AtomicBoolean(false)
    private var logListener: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "NativeProxyRunner"
        private const val LIB_NAME = "libtgproxy.so"
    }

    fun setLogListener(listener: ((String) -> Unit)?) {
        this.logListener = listener
    }

    fun start(
        port: Int = 1443,
        secret: String = "",
        domain: String = "google.com",
        cfWorker: String = "",
        disableFakeTls: Boolean = false
    ): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Proxy is already running")
            return true
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val libFile = File(nativeLibDir, LIB_NAME)

        if (!libFile.exists()) {
            val errMsg = "Native binary not found at: ${libFile.absolutePath}"
            Log.e(TAG, errMsg)
            logListener?.invoke("ОШИБКА: $errMsg")
            return false
        }

        val cmd = mutableListOf<String>().apply {
            add(libFile.absolutePath)
            add("-listen")
            add("127.0.0.1:$port")
            if (secret.isNotBlank()) {
                add("-secret")
                add(secret)
            }
            if (domain.isNotBlank()) {
                add("-domain")
                add(domain)
            }
            if (cfWorker.isNotBlank()) {
                add("-cf-worker")
                add(cfWorker)
            }
            if (disableFakeTls) {
                add("-no-faketls")
            }
        }

        try {
            Log.i(TAG, "Starting native process: ${cmd.joinToString(" ")}")
            logListener?.invoke("Запуск ядра: 127.0.0.1:$port (Fake TLS SNI: $domain)")

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            process = pb.start()
            isRunning.set(true)

            Thread {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                try {
                    while (isRunning.get()) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, line)
                        logListener?.invoke(line)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Log stream read exception", e)
                    }
                } finally {
                    reader.close()
                }
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch native proxy process", e)
            logListener?.invoke("Ошибка запуска: ${e.message}")
            isRunning.set(false)
            return false
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "Stopping native proxy process")
        logListener?.invoke("Остановка ядра...")

        try {
            process?.destroy()
            process?.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for process destruction", e)
        } finally {
            process = null
        }
    }

    fun isActive(): Boolean = isRunning.get()
}
