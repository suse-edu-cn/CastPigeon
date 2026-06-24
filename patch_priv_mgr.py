with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

new_content = """package com.suseoaa.castpigeon.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.suseoaa.castpigeon.IRootClipboard
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

object PrivilegeManager {
    var isPrivileged = false
        private set

    var rootClipboard: IRootClipboard? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("CastPigeonRoot", "RootService connected")
            rootClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "RootService disconnected")
            rootClipboard = null
            isPrivileged = false
        }
    }

    fun executeAppOpsCommand(context: Context): Boolean {
        try {
            // First check if root is available via Shell.cmd
            val result = Shell.cmd("id").exec()
            if (!result.isSuccess) {
                Log.w("CastPigeonRoot", "Root execute failed, please check KernelSU/Magisk manager.")
                return false
            }
            
            Log.i("CastPigeonRoot", "Binding RootClipboardService...")
            val intent = Intent(context, RootClipboardService::class.java)
            RootService.bind(intent, connection)
            // Note: bind is asynchronous, but we return true to indicate initialization started
            return true
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Failed to bind RootService", e)
            return false
        }
    }
}"""

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(new_content)
