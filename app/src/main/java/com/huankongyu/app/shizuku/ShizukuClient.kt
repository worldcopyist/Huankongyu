package com.huankongyu.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

/**
 * Lifecycle manager for the Shizuku binder and privileged UserService.
 *
 * Flow (official Shizuku-API guide):
 * 1. ShizukuProvider receives the binder from Shizuku/Sui
 * 2. App requests Shizuku self-permission
 * 3. App binds a UserService that runs Java code as shell (uid 2000) or root (uid 0)
 */
object ShizukuClient {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    const val PERMISSION_REQUEST_CODE = 0x53_48_49 // "SHI"

    private const val USER_SERVICE_VERSION = 1
    private const val USER_SERVICE_TAG = "huankongyu_shizuku_user_service"

    enum class State {
        /** Shizuku app is not present on this device. */
        NotInstalled,
        /** Installed, but the privileged service has not delivered a binder yet. */
        WaitingForService,
        /** Binder is alive; the user still needs to grant this app Shizuku permission. */
        PermissionNeeded,
        /** User permanently denied the permission. */
        PermissionDenied,
        /** Permission granted; waiting for the UserService binder. */
        Connecting,
        /** Binder + permission + UserService are all ready. */
        Ready,
        /** Binder died after a successful connection. */
        Dead
    }

    var state by mutableStateOf(State.WaitingForService)
        private set

    /** Shizuku server version when the binder is alive; otherwise null. */
    var serverVersion by mutableStateOf<Int?>(null)
        private set

    /** Linux uid of the Shizuku process: 0 = root, 2000 = shell (adb). */
    var privilegeUid by mutableStateOf<Int?>(null)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attached = false
    private var userService: IUserService? = null
    private var userServiceBound = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post { onBinderReceived() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post { onBinderDead() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            mainHandler.post {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    lastError = null
                    bindUserServiceIfNeeded()
                } else {
                    state = if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
                        State.PermissionDenied
                    } else {
                        State.PermissionNeeded
                    }
                    lastError = "未授予 Shizuku 权限"
                }
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mainHandler.post {
                userService = IUserService.Stub.asInterface(service)
                userServiceBound = true
                lastError = null
                state = State.Ready
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainHandler.post {
                userService = null
                userServiceBound = false
                if (state == State.Ready) {
                    state = State.Dead
                    lastError = "Shizuku 用户服务已断开"
                }
            }
        }
    }

    /** Registers binder/permission listeners. Safe to call multiple times. */
    fun attach(context: Context) {
        if (attached) {
            refresh(context)
            return
        }
        attached = true
        lastError = null
        // Sticky: delivers immediately when the binder is already alive.
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
            .onFailure { lastError = "注册 Shizuku 监听失败：${it.message ?: "未知错误"}" }
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh(context)
    }

    /** Releases listeners and the user-service connection. */
    fun detach(context: Context) {
        if (!attached) return
        attached = false
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        unbindUserService(context)
        userService = null
        state = if (isInstalled(context)) State.WaitingForService else State.NotInstalled
    }

    fun isInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isReady(): Boolean = state == State.Ready && userService != null

    fun hasPermission(): Boolean {
        return runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
    }

    /** Requests Shizuku permission for this app (user confirms inside the Shizuku dialog). */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { lastError = "无法请求 Shizuku 权限：${it.message ?: "未知错误"}" }
    }

    /** Opens the Shizuku app if installed. */
    fun openShizukuApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openDownloadPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Builds the ADB start command for the currently installed Shizuku APK.
     * Shizuku 13.x starts via the packaged starter binary (libshizuku.so).
     */
    fun adbStartCommand(context: Context): String {
        val apkPath = runCatching {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0).sourceDir
        }.getOrNull() ?: return "adb shell sh /sdcard/Android/data/$SHIZUKU_PACKAGE/start.sh"
        val libDir = apkPath.substringBeforeLast('/') + "/lib/arm64"
        val starter = "$libDir/libshizuku.so"
        return "adb shell $starter --apk=$apkPath"
    }

    fun privilegeLabel(): String = when (privilegeUid) {
        0 -> "ROOT"
        2000 -> "ADB shell"
        null -> "—"
        else -> "uid=$privilegeUid"
    }

    fun isConnected(): Boolean = state == State.Ready

    /** Re-evaluates install/binder/permission state without forcing a user dialog. */
    fun refresh(context: Context) {
        if (!isInstalled(context)) {
            state = State.NotInstalled
            serverVersion = null
            privilegeUid = null
            return
        }
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            if (state == State.Ready || state == State.PermissionNeeded || state == State.PermissionDenied) {
                state = State.Dead
            } else {
                state = State.WaitingForService
            }
            serverVersion = null
            privilegeUid = null
            return
        }
        serverVersion = runCatching { Shizuku.getVersion() }.getOrNull()
        privilegeUid = runCatching { Shizuku.getUid() }.getOrNull()
        when {
            hasPermission() -> bindUserServiceIfNeeded()
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false) -> {
                state = State.PermissionDenied
            }
            else -> state = State.PermissionNeeded
        }
    }

    /**
     * Runs a command through the bound UserService (shell/root identity).
     * Returns a human-readable error string when the service is not ready.
     */
    suspend fun exec(command: String): String {
        val service = userService
        if (state != State.Ready || service == null) {
            return when (state) {
                State.NotInstalled -> "Shizuku 未安装"
                State.WaitingForService -> "Shizuku 服务未运行"
                State.PermissionNeeded -> "尚未授予 Shizuku 权限"
                State.PermissionDenied -> "Shizuku 权限被拒绝"
                State.Connecting -> "Shizuku 正在连接用户服务"
                State.Dead -> "Shizuku 连接已断开"
                State.Ready -> "Shizuku 用户服务尚未就绪"
            }
        }
        return runCatching { service.exec(command) }
            .getOrElse { "Shizuku 命令执行失败：${it.message ?: "未知错误"}" }
    }

    /** One-line status for device context injection. */
    fun deviceContextLine(context: Context? = null): String {
        return when (state) {
            State.NotInstalled -> "Shizuku：未安装"
            State.WaitingForService -> "Shizuku：服务未运行（需在 Shizuku 中启动）"
            State.PermissionNeeded -> "Shizuku：服务可用，但本应用未获授权"
            State.PermissionDenied -> "Shizuku：权限被拒绝"
            State.Connecting -> "Shizuku：正在建立特权连接"
            State.Dead -> "Shizuku：连接已断开"
            State.Ready -> {
                val privilege = when (privilegeUid) {
                    0 -> "ROOT"
                    2000 -> "ADB shell"
                    null -> "已连接"
                    else -> "uid=$privilegeUid"
                }
                val version = serverVersion?.let { "，服务端 v$it" }.orEmpty()
                "Shizuku：已连接（$privilege$version）"
            }
        }
    }

    fun statusLabel(): String = when (state) {
        State.NotInstalled -> "未安装"
        State.WaitingForService -> "等待服务"
        State.PermissionNeeded -> "待授权"
        State.PermissionDenied -> "权限被拒绝"
        State.Connecting -> "连接中"
        State.Ready -> "已连接"
        State.Dead -> "连接断开"
    }

    fun statusDetail(): String = when (state) {
        State.NotInstalled -> "请先安装 Shizuku，再在其中启动服务"
        State.WaitingForService -> "打开 Shizuku 并启动服务后返回本应用"
        State.PermissionNeeded -> "Shizuku 已就绪，需要授权本应用使用"
        State.PermissionDenied -> "可在 Shizuku 中重新允许，或重置后再次请求"
        State.Connecting -> "已授权，正在绑定特权用户服务"
        State.Ready -> {
            val privilege = when (privilegeUid) {
                0 -> "ROOT 权限"
                2000 -> "ADB shell 权限"
                else -> "已连接"
            }
            val version = serverVersion?.let { " · 服务端 v$it" }.orEmpty()
            "$privilege$version，可用于特权命令"
        }
        State.Dead -> "服务已停止，请重新启动 Shizuku"
    }

    fun actionLabel(): String = when (state) {
        State.NotInstalled -> "去安装"
        State.WaitingForService, State.Dead -> "打开 Shizuku"
        State.PermissionNeeded, State.PermissionDenied -> "请求授权"
        State.Connecting, State.Ready -> "已就绪"
    }

    private fun onBinderReceived() {
        serverVersion = runCatching { Shizuku.getVersion() }.getOrNull()
        privilegeUid = runCatching { Shizuku.getUid() }.getOrNull()
        lastError = null
        if (hasPermission()) {
            bindUserServiceIfNeeded()
        } else {
            state = State.PermissionNeeded
        }
    }

    private fun onBinderDead() {
        userService = null
        userServiceBound = false
        serverVersion = null
        privilegeUid = null
        state = State.Dead
        lastError = "Shizuku binder 已断开"
    }

    private fun bindUserServiceIfNeeded() {
        if (userServiceBound && userService != null) {
            state = State.Ready
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            state = State.WaitingForService
            return
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName("com.huankongyu.app", ShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_user_service")
            .debuggable(false)
            .version(USER_SERVICE_VERSION)
            .tag(USER_SERVICE_TAG)
        runCatching { Shizuku.bindUserService(args, userServiceConnection) }
            .onSuccess {
                userServiceBound = true
                state = State.Connecting
            }
            .onFailure {
                userServiceBound = false
                state = if (hasPermission()) State.Dead else State.PermissionDenied
                lastError = "绑定用户服务失败：${it.message ?: "未知错误"}"
            }
    }

    private fun unbindUserService(context: Context) {
        if (!userServiceBound) return
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_user_service")
            .debuggable(false)
            .version(USER_SERVICE_VERSION)
            .tag(USER_SERVICE_TAG)
        runCatching { Shizuku.unbindUserService(args, userServiceConnection, true) }
        userServiceBound = false
        userService = null
    }
}
