package com.huankongyu.app.shizuku

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * UserService bound by Shizuku. Runs in a separate process with shell/root identity.
 * Not a normal Android app process — avoid Context APIs that need a full app runtime.
 */
class ShizukuUserService : IUserService.Stub {

    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "ERROR: command timed out"
            }
            val code = process.exitValue()
            if (code == 0) {
                stdout.trim()
            } else {
                val err = stderr.trim()
                if (err.isEmpty()) stdout.trim() else "exit=$code $err"
            }
        } catch (t: Throwable) {
            "ERROR: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
