import subprocess

code = """package com.suseoaa.castpigeon.service

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell

object AppOpsTester {
    fun test() {
        val result = Shell.cmd("appops set com.suseoaa.castpigeon READ_CLIPBOARD allow").exec()
        Log.i("CastPigeonRoot", "AppOps set result: isSuccess=${result.isSuccess}, out=${result.out}, err=${result.err}")
    }
}
"""
