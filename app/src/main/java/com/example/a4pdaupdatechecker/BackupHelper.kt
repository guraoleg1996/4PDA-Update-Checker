package com.example.a4pdaupdatechecker

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object BackupHelper {
    private val gson = Gson()

    fun toJson(apps: List<TrackedApp>): String {
        return gson.toJson(apps)
    }

    fun fromJson(json: String): List<TrackedApp>? {
        return try {
            val type = object : TypeToken<List<TrackedApp>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }
}