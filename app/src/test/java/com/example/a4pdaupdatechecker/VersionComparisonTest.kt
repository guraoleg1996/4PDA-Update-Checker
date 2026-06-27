package com.example.a4pdaupdatechecker

import org.junit.Test
import org.junit.Assert.*

class VersionComparisonTest {

    @Test
    fun testIsUpdateAvailable() {
        // Одинаковые версии
        assertFalse(UpdateChecker.isUpdateAvailable("1.20.4", "1.20.4"))
        
        // Префикс 'v' в установленной версии
        assertFalse(UpdateChecker.isUpdateAvailable("1.20.4", "v1.20.4"))
        
        // Префикс 'v' в версии на сайте
        assertFalse(UpdateChecker.isUpdateAvailable("v1.20.4", "1.20.4"))
        
        // DuckStation случай: 0.1-8969 vs 0.1-8969-g611bb8fb4
        assertFalse(UpdateChecker.isUpdateAvailable("0.1-8969", "0.1-8969-g611bb8fb4"))
        
        // NetherSX2 случай: 2.2n vs v2.2n-4248
        assertFalse(UpdateChecker.isUpdateAvailable("2.2n", "v2.2n-4248"))
        
        // Обновление буквами: 2.2o vs 2.2n
        assertTrue(UpdateChecker.isUpdateAvailable("2.2o", "2.2n"))

        // Обновление доступно (минорная версия)
        assertTrue(UpdateChecker.isUpdateAvailable("1.20.5", "1.20.4"))
        
        // Обновление доступно (мажорная версия)
        assertTrue(UpdateChecker.isUpdateAvailable("2.0.0", "1.9.9"))
        
        // Установленная версия новее (бета или откат)
        assertFalse(UpdateChecker.isUpdateAvailable("1.20.4", "1.20.5"))
        
        // Разное количество сегментов
        assertTrue(UpdateChecker.isUpdateAvailable("1.21", "1.20.4"))
        assertFalse(UpdateChecker.isUpdateAvailable("1.20", "1.20.4"))
        
        // Текст в версии
        assertFalse(UpdateChecker.isUpdateAvailable("1.20.4 (stable)", "v1.20.4"))
        
        // Пустые или null значения
        assertFalse(UpdateChecker.isUpdateAvailable(null, "1.0"))
        assertFalse(UpdateChecker.isUpdateAvailable("1.0", null))
    }
}