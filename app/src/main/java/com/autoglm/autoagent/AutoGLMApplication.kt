package com.autoglm.autoagent

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class AutoGLMApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (e: Throwable) {
            android.util.Log.e("AutoGLM", "Failed to bypass hidden API restrictions", e)
        }
    }
}
