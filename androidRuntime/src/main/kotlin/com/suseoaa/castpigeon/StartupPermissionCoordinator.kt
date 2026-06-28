package com.suseoaa.castpigeon

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.suseoaa.castpigeon.service.MyNotificationListener
import rikka.shizuku.Shizuku

object StartupPermissionCoordinator {
    private const val TAG = "CastPigeonPerms"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    fun runtimePermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun missingRuntimePermissions(context: Context): Array<String> {
        return runtimePermissions()
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
    }

    fun requestRuntimePermissionsIfNeeded(
        activity: Activity,
        launcher: ActivityResultLauncher<Array<String>>,
        onAlreadyGranted: () -> Unit
    ) {
        val missing = missingRuntimePermissions(activity)
        if (missing.isEmpty()) {
            onAlreadyGranted()
        } else {
            Log.i(TAG, "请求启动必需权限: ${missing.joinToString()}")
            launcher.launch(missing)
        }
    }

    fun continueSpecialPermissionFlow(activity: Activity) {
        requestShizukuPermissionIfAvailable(activity)
        ensureNotificationListenerAccess(activity)
        verifyPackageVisibility(activity)
    }

    private fun requestShizukuPermissionIfAvailable(activity: Activity) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku 未运行，启动阶段无法弹出 Shizuku 授权")
                Toast.makeText(activity, "Shizuku 未运行，剪贴板后台同步需要稍后授权", Toast.LENGTH_LONG).show()
                return
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "启动阶段请求 Shizuku 授权")
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动阶段请求 Shizuku 授权失败", e)
        }
    }

    private fun ensureNotificationListenerAccess(activity: Activity) {
        if (isNotificationListenerEnabled(activity)) return

        Log.i(TAG, "通知监听权限未开启，打开系统通知使用权设置页")
        Toast.makeText(activity, "请开启 CastPigeon 通知使用权", Toast.LENGTH_LONG).show()
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "打开通知使用权设置页失败", e)
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val listener = ComponentName(context, MyNotificationListener::class.java)
        return context.packageName in enabledPackages || listener.packageName in enabledPackages
    }

    private fun verifyPackageVisibility(context: Context) {
        try {
            val installedCount = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size
            Log.i(TAG, "应用列表可见性检查完成，installedApplications=$installedCount")
        } catch (e: Exception) {
            Log.e(TAG, "应用列表读取失败，请确认 Manifest 已声明 QUERY_ALL_PACKAGES", e)
        }
    }
}
