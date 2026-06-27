package com.example.a4pdaupdatechecker

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {
    @Query("SELECT * FROM tracked_apps WHERE folderName IS :folder ORDER BY isFolder DESC, sortOrder ASC")
    fun getInFolder(folder: String?): Flow<List<TrackedApp>>

    @Query("SELECT * FROM tracked_apps WHERE folderName IS :folder ORDER BY isFolder DESC, sortOrder ASC")
    suspend fun getInFolderList(folder: String?): List<TrackedApp>

    @Query("SELECT * FROM tracked_apps ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<TrackedApp>>

    @Query("SELECT * FROM tracked_apps ORDER BY sortOrder ASC")
    suspend fun getAllList(): List<TrackedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: TrackedApp)

    @Delete
    suspend fun delete(app: TrackedApp)

    @Update
    suspend fun update(app: TrackedApp)

    @Update
    suspend fun updateAll(apps: List<TrackedApp>)
    
    @Query("SELECT * FROM tracked_apps WHERE topicUrl = :url")
    suspend fun getByUrl(url: String): TrackedApp?

    @Query("SELECT MAX(sortOrder) FROM tracked_apps WHERE folderName IS :folder")
    suspend fun getMaxSortOrder(folder: String?): Int?

    @Query("SELECT DISTINCT appName FROM tracked_apps WHERE isFolder = 1")
    fun getAllFolders(): Flow<List<String>>
}