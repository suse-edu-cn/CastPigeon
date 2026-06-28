package com.suseoaa.castpigeon

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

//应用信息数据类
data class AppInfo(
    //包名
    val packageName: String,
    //应用名称
    val appName: String,
    //是否为系统应用
    val isSystemApp: Boolean,
    //是否被选中同步
    var isSelected: Boolean
)

//应用管理单例
object AppManager {
    private const val PREFS_NAME = "cast_pigeon_app_prefs"
    private const val DEFAULT_SYNC_ALL = "default_sync_all"
    private const val SHOW_SYSTEM_APPS = "show_system_apps"
    private const val ICON_CACHE_MAX_KB = 12 * 1024
    
    private var prefs: SharedPreferences? = null
    private var allApps: List<AppInfo> = emptyList()
    private var hasLoadedApps: Boolean = false

    private val iconCacheLock = Any()
    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount / 1024
        }
    }

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()
    
    //初始化方法
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _showSystemApps.value = prefs?.getBoolean(SHOW_SYSTEM_APPS, false) ?: false
        if (hasLoadedApps) {
            publishAppList()
        } else {
            loadInstalledApps(context)
        }
    }
    
    //加载已安装的应用列表
    private fun loadInstalledApps(context: Context) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = mutableListOf<AppInfo>()
        
        //默认全部同步标识
        val isFirstLaunch = prefs?.getBoolean(DEFAULT_SYNC_ALL, true) ?: true
        if (isFirstLaunch) {
            prefs?.edit()?.putBoolean(DEFAULT_SYNC_ALL, false)?.apply()
        }
        
        for (app in packages) {
            val isSystemApp =
                (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val appName = pm.getApplicationLabel(app).toString()
            val pkgName = app.packageName
            val isSelected = if (isFirstLaunch) {
                prefs?.edit()?.putBoolean(pkgName, true)?.apply()
                true
            } else {
                prefs?.getBoolean(pkgName, true) ?: true
            }
            list.add(AppInfo(pkgName, appName, isSystemApp, isSelected))
        }
        
        //按名称排序
        allApps = list.sortedBy { it.appName.lowercase() }
        hasLoadedApps = true
        publishAppList()
    }
    
    //更新应用同步状态
    fun updateAppSelection(packageName: String, isSelected: Boolean) {
        prefs?.edit()?.putBoolean(packageName, isSelected)?.apply()
        val currentList = _appList.value.toMutableList()
        val index = currentList.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            val updatedApp = currentList[index].copy(isSelected = isSelected)
            currentList[index] = updatedApp
            _appList.value = currentList
        }
        allApps = allApps.map { app ->
            if (app.packageName == packageName) app.copy(isSelected = isSelected) else app
        }
    }
    
    //检查某应用是否允许同步
    fun isAppAllowed(packageName: String): Boolean {
        return prefs?.getBoolean(packageName, true) ?: true
    }

    fun getAppIconBitmap(context: Context, packageName: String): Bitmap? {
        synchronized(iconCacheLock) {
            iconCache.get(packageName)?.let { return it }
        }

        val bitmap = runCatching {
            val iconDrawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            val width = iconDrawable.intrinsicWidth.coerceAtLeast(1)
            val height = iconDrawable.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                iconDrawable.draw(canvas)
            }
        }.getOrNull() ?: return null

        synchronized(iconCacheLock) {
            iconCache.put(packageName, bitmap)
        }
        return bitmap
    }

    fun setShowSystemApps(show: Boolean) {
        prefs?.edit()?.putBoolean(SHOW_SYSTEM_APPS, show)?.apply()
        _showSystemApps.value = show
        publishAppList()
    }

    private fun publishAppList() {
        _appList.value = if (_showSystemApps.value) {
            allApps
        } else {
            allApps.filterNot { it.isSystemApp }
        }
    }
}
