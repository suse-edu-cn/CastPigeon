code = """
package com.suseoaa.castpigeon.service

import android.util.Log
import com.topjohnwu.superuser.Shell

object ServiceCallTester {
    fun test() {
        // Find the transaction code for getPrimaryClip. In Android 13, it's usually 3 or 4.
        // Let's just try to call it and see if we get SecurityException or Denying clipboard access.
        val res = Shell.cmd("service call clipboard 2").exec()
        Log.i("CastPigeonRoot", "service call 2: ${res.out}")
        val res3 = Shell.cmd("service call clipboard 3").exec()
        Log.i("CastPigeonRoot", "service call 3: ${res3.out}")
        
        // Also try reading via su 1000
        val res1000 = Shell.cmd("su 1000 -c id").exec()
        Log.i("CastPigeonRoot", "su 1000 id: ${res1000.out} err: ${res1000.err}")
    }
}
"""
