package com.suseoaa.castpigeon.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.suseoaa.castpigeon.service.AppConnectionManager

class TransparentClipboardActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        Log.i("CastPigeon", "TransparentClipboardActivity onCreate, action=${intent?.action}")
    }

    override fun onResume() {
        super.onResume()
        Log.i("CastPigeon", "TransparentClipboardActivity onResume, action=${intent?.action}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i("CastPigeon", "TransparentClipboardActivity onWindowFocusChanged: hasFocus=$hasFocus, handled=$handled")
        if (hasFocus && !handled) {
            handled = true
            handleAction()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun handleAction() {
        val action = intent?.action
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        Log.i("CastPigeon", "TransparentClipboardActivity handleAction: action=$action")

        when (action) {
            "ACTION_SET_CLIPBOARD" -> {
                // Mac → Android: 写入剪贴板
                val textToSet = intent.getStringExtra("text")
                if (textToSet != null) {
                    com.suseoaa.castpigeon.service.BleForegroundService.isInternalClipboardWrite = true
                    val clip = ClipData.newPlainText("CastPigeon", textToSet)
                    clipboard.setPrimaryClip(clip)
                    Log.i("CastPigeon", "TransparentClipboardActivity: 写入剪贴板成功: $textToSet")
                    Toast.makeText(this, "已同步 Mac 剪贴板", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                // Android → Mac: 读取剪贴板并通过 BLE 发送
                // action 可能是 ACTION_SYNC_CLIPBOARD（手动）或 ACTION_SYNC_CLIPBOARD_AUTO（Root 自动触发）
                Log.i("CastPigeon", "TransparentClipboardActivity: 读取剪贴板...")
                val clip = clipboard.primaryClip
                Log.i("CastPigeon", "TransparentClipboardActivity: primaryClip=$clip, itemCount=${clip?.itemCount ?: 0}")
                val text = clip?.getItemAt(0)?.text?.toString()
                Log.i("CastPigeon", "TransparentClipboardActivity: 读取结果: '$text'")

                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Log.i("CastPigeon", "TransparentClipboardActivity: BLE 发送成功! payload长度=${payload.length}")
                        // 仅手动触发时显示 Toast
                        if (action == "ACTION_SYNC_CLIPBOARD") {
                            Toast.makeText(this, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("CastPigeon", "TransparentClipboardActivity: BLE 发送失败", e)
                        Toast.makeText(this, "推送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w("CastPigeon", "TransparentClipboardActivity: 剪贴板为空")
                    if (action == "ACTION_SYNC_CLIPBOARD") {
                        Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
