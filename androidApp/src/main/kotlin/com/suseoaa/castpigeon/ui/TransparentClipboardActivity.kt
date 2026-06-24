package com.suseoaa.castpigeon.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.suseoaa.castpigeon.service.AppConnectionManager

class TransparentClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 确保 Activity 不会闪现出默认的黑色/白色背景
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            readClipboardAndSend()
            finish()
            // 禁用退出动画
            overridePendingTransition(0, 0)
        }
    }

    private fun readClipboardAndSend() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) {
            val payload = "CLIP|" + text
            try {
                AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                Toast.makeText(this, "已推送到 Mac", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "推送失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "剪贴板为空或无法访问", Toast.LENGTH_SHORT).show()
        }
    }
}
