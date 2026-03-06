package com.swf.workflow.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

data class ShizukuState(
    val installed: Boolean,
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val ready: Boolean,
    val statusLabel: String
)

data class ShizukuCommandResult(
    val success: Boolean,
    val executed: Boolean,
    val message: String,
    val exitCode: Int? = null
)

object ShizukuBridge {

    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

    fun queryState(context: Context): ShizukuState {
        val installed = isShizukuInstalled(context)
        if (!installed) {
            return ShizukuState(
                installed = false,
                binderAlive = false,
                permissionGranted = false,
                ready = false,
                statusLabel = "未安装"
            )
        }

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return ShizukuState(
                installed = true,
                binderAlive = false,
                permissionGranted = false,
                ready = false,
                statusLabel = "已安装，未运行"
            )
        }

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return ShizukuState(
                installed = true,
                binderAlive = true,
                permissionGranted = false,
                ready = false,
                statusLabel = "版本过旧"
            )
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        return if (permissionGranted) {
            ShizukuState(
                installed = true,
                binderAlive = true,
                permissionGranted = true,
                ready = true,
                statusLabel = "已就绪"
            )
        } else {
            ShizukuState(
                installed = true,
                binderAlive = true,
                permissionGranted = false,
                ready = false,
                statusLabel = "已运行，未授权"
            )
        }
    }

    fun requestPermissionIfNeeded(requestCode: Int): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return false
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return false
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            return false
        }

        val canRequest = runCatching {
            !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(true)
        if (!canRequest) {
            return false
        }

        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }

    fun forceStopPackage(context: Context, packageName: String): ShizukuCommandResult {
        if (!isValidPackageName(packageName)) {
            return ShizukuCommandResult(
                success = false,
                executed = false,
                message = "无效包名：$packageName"
            )
        }
        return runCommandIfReady(
            context = context,
            command = "am force-stop $packageName",
            actionLabel = "关闭应用"
        )
    }

    fun startPackage(context: Context, packageName: String): ShizukuCommandResult {
        if (!isValidPackageName(packageName)) {
            return ShizukuCommandResult(
                success = false,
                executed = false,
                message = "无效包名：$packageName"
            )
        }
        return runCommandIfReady(
            context = context,
            command = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName",
            actionLabel = "拉起应用"
        )
    }

    private fun runCommandIfReady(
        context: Context,
        command: String,
        actionLabel: String
    ): ShizukuCommandResult {
        val state = queryState(context)
        if (!state.ready) {
            return ShizukuCommandResult(
                success = false,
                executed = false,
                message = "Shizuku未就绪（${state.statusLabel}）"
            )
        }

        return runShellCommand(command, actionLabel)
    }

    private fun runShellCommand(command: String, actionLabel: String): ShizukuCommandResult {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as? Process ?: return ShizukuCommandResult(
                success = false,
                executed = true,
                message = "${actionLabel}失败：无法创建远程进程"
            )

            process.outputStream.close()
            val exitCode = process.waitFor()
            val stdErr = process.errorStream.bufferedReader().use { it.readText().trim() }
            val stdOut = process.inputStream.bufferedReader().use { it.readText().trim() }

            if (exitCode == 0) {
                ShizukuCommandResult(
                    success = true,
                    executed = true,
                    message = stdOut.ifBlank { "${actionLabel}成功" },
                    exitCode = exitCode
                )
            } else {
                ShizukuCommandResult(
                    success = false,
                    executed = true,
                    message = stdErr.ifBlank { "${actionLabel}失败，退出码：$exitCode" },
                    exitCode = exitCode
                )
            }
        }.getOrElse { throwable ->
            ShizukuCommandResult(
                success = false,
                executed = true,
                message = "${actionLabel}失败：${throwable.message.orEmpty()}"
            )
        }
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_MANAGER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            }
        }.isSuccess
    }

    private fun isValidPackageName(packageName: String): Boolean {
        return packageName.matches(PACKAGE_NAME_REGEX)
    }
}
