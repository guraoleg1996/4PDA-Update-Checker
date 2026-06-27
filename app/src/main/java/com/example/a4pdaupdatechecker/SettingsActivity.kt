package com.example.a4pdaupdatechecker

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportToFile(it) }
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyAmoledToActivity(this)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ThemeHelper.updateStatusBarIcons(this)
        database = AppDatabase.getDatabase(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            val threshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics)
            findViewById<View>(R.id.bottomDivider).visibility = if (navBars.bottom > threshold) View.VISIBLE else View.GONE
            
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupThemeSelector()
        setupAmoledSwitch()
        setupBackupButtons()
    }

    private fun setupThemeSelector() {
        val themes = arrayOf("По выбору системы", "Светлая", "Темная")
        val adapter = NoFilterAdapter(this, R.layout.list_item_dropdown, themes)
        val selector = findViewById<AutoCompleteTextView>(R.id.themeSelector)
        
        selector.setAdapter(adapter)
        
        val currentTheme = ThemeHelper.getSelectedTheme(this)
        selector.setText(themes[currentTheme], false)

        selector.setOnItemClickListener { _, _, position, _ ->
            ThemeHelper.setSelectedTheme(this, position)
        }
    }

    private fun setupAmoledSwitch() {
        val amoledSwitch = findViewById<MaterialSwitch>(R.id.switchAmoled)
        amoledSwitch.isChecked = ThemeHelper.isAmoledEnabled(this)
        
        amoledSwitch.setOnCheckedChangeListener { _, isChecked ->
            ThemeHelper.setAmoledEnabled(this, isChecked)
            findViewById<AutoCompleteTextView>(R.id.themeSelector).clearFocus()
            recreate()
        }
    }

    private fun setupBackupButtons() {
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            createDocumentLauncher.launch("4pda_checker_backup.json")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }
    }

    private fun exportToFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apps = database.trackedAppDao().getAllList()
                val json = BackupHelper.toJson(apps)
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Список экспортирован", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (json != null) {
                    val apps = BackupHelper.fromJson(json)
                    if (apps != null) {
                        for (app in apps) {
                            database.trackedAppDao().insert(app)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SettingsActivity, "Импортировано ${apps.size} приложений", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        throw Exception("Не удалось прочитать файл")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private class NoFilterAdapter(context: Context, resource: Int, objects: Array<String>) :
        ArrayAdapter<String>(context, resource, objects) {
        
        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                results.values = objects
                results.count = objects.size
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter {
            return noFilter
        }
    }
}