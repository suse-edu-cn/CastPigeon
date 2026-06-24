package com.suseoaa.castpigeon;

interface IRootClipboard {
    String getClipboardText();
    void setClipboardText(String text);
    // 以 UID=0 身份执行 am start，绕过 Android 11+ 后台 Activity 启动限制
    void launchClipboardActivity();
}
