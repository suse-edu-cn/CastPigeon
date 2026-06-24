package com.suseoaa.castpigeon;

import com.suseoaa.castpigeon.IClipboardChangeCallback;

interface IRootClipboard {
    String getClipboardText();
    boolean setClipboardText(String text);
    void registerClipboardCallback(IClipboardChangeCallback callback);
    void unregisterClipboardCallback(IClipboardChangeCallback callback);
    // 以 UID=0 身份执行 am start，绕过 Android 11+ 后台 Activity 启动限制
    void launchClipboardActivity();
}
