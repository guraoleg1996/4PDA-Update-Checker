package com.example.a4pdaupdatechecker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AppVersionHelper {
    fun getVersion(context: Context, packageName: String): String? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: info.longVersionCode.toString()
        } catch (e: Exception) {
            null
        }
    }
}