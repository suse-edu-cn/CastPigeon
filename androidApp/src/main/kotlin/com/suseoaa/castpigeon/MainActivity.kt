package com.suseoaa.castpigeon

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.suseoaa.castpigeon.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.i("CastPigeonPerms", "启动权限请求结果: allGranted=$allGranted, permissions=$permissions")
        if (!allGranted) {
            android.widget.Toast.makeText(this, "请授予全部权限，否则同步功能可能无法正常工作", android.widget.Toast.LENGTH_LONG).show()
        }
        StartupPermissionCoordinator.continueSpecialPermissionFlow(this)
        showMainContent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        StartupPermissionCoordinator.requestRuntimePermissionsIfNeeded(
            activity = this,
            launcher = startupPermissionLauncher,
            onAlreadyGranted = {
                StartupPermissionCoordinator.continueSpecialPermissionFlow(this)
                showMainContent()
            }
        )
    }

    private fun showMainContent() {
        setContent {
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            val colorScheme = if (isDark) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            
            androidx.compose.material3.MaterialTheme(colorScheme = colorScheme) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize().background(colorScheme.background)
                ) {
                    MainScreen()
                }
            }
        }
    }
}
