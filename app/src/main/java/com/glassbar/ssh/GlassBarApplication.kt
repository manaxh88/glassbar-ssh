package com.glassbar.ssh

import android.app.Application
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

lateinit var glassBarApp: GlassBarApplication

class GlassBarApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: android.content.pm.ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = android.content.pm.ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        glassBarApp = this

        if (!isUserUnlocked()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = this.getSharedPreferences("settings", MODE_PRIVATE)
            val enable = prefs.getBoolean("enable_predictive_back", false)
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        // Provide working env for rust's temp_dir()
        android.system.Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "GlassBar/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
