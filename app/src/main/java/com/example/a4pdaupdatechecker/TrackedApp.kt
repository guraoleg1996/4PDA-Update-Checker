package com.example.a4pdaupdatechecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_apps")
data class TrackedApp(
    @PrimaryKey val topicUrl: String,
    val appName: String? = null,
    val packageName: String? = null,
    val currentVersionOnSite: String? = null,
    val installedVersion: String? = null,
    val lastCheckTime: Long = 0,
    val sortOrder: Int = 0,
    val folderName: String? = null,
    val isFolder: Boolean = false
)