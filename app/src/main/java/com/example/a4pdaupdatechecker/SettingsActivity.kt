package com.example.a4pdaupdatechecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
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
        setupLogSizeSelector()
        setupLogButton()
    }

    companion object {
        private val logSizeKbValues = intArrayOf(
            50,        // 50 КБ
            100,       // 100 КБ
            250,       // 250 КБ
            500,       // 500 КБ
            1000,      // 1 МБ
            5000,      // 5 МБ
            10000,     // 10 МБ
            50000,     // 50 МБ
            100000,    // 100 МБ
            500000,    // 500 МБ
            1000000    // 1 ГБ
        )

        private val logSizeLabels = arrayOf(
            "50 КБ (~100 записей)",
            "100 КБ (~200 записей, по умолчанию)",
            "250 КБ (~500 записей)",
            "500 КБ (~1000 записей)",
            "1 МБ (~2 тыс. записей)",
            "5 МБ (~10 тыс. записей)",
            "10 МБ (~20 тыс. записей)",
            "50 МБ (~100 тыс. записей)",
            "100 МБ (~200 тыс. записей)",
            "500 МБ (~1 млн записей)",
            "1 ГБ (~2 млн записей)"
        )
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

    private fun setupLogSizeSelector() {
        val adapter = NoFilterAdapter(this, R.layout.list_item_dropdown, logSizeLabels)
        val selector = findViewById<AutoCompleteTextView>(R.id.logSizeSelector)
        
        selector.setAdapter(adapter)
        
        val currentLimitKb = AppLog.getLimitKb()
        val currentIndex = logSizeKbValues.indexOf(currentLimitKb).let { if (it >= 0) it else 1 }
        selector.setText(logSizeLabels[currentIndex], false)

        selector.setOnItemClickListener { _, _, position, _ ->
            if (position in logSizeKbValues.indices) {
                AppLog.setLimitKb(this, logSizeKbValues[position])
            }
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

    private fun setupLogButton() {
        findViewById<Button>(R.id.btnLogs).setOnClickListener { showLogsDialog() }
    }

    private fun showLogsDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        val infoTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }

        val logTextView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, Color.GRAY))
        }

        val scroll = ScrollView(this).apply { addView(logTextView) }

        layout.addView(infoTextView)
        layout.addView(scroll)

        fun refresh() {
            infoTextView.text = "Размер файла: ${AppLog.getLogSizeInfo()}"
            val text = AppLog.getText()
            logTextView.text = if (text.isBlank()) {
                "Журнал пуст. Обновите список на главном экране, и записи появятся здесь."
            } else {
                text
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        refresh()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Журнал работы сети")
            .setView(layout)
            .setPositiveButton("Поделиться", null)
            .setNeutralButton("Копировать", null)
            .setNegativeButton("Очистить", null)
            .create()
        dialog.show()

        // Кнопки переопределены, чтобы окно не закрывалось после нажатия.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Журнал 4PDA Update Checker")
                putExtra(Intent.EXTRA_TEXT, AppLog.buildReport(this@SettingsActivity))
            }
            startActivity(Intent.createChooser(send, "Отправить журнал"))
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Журнал 4PDA Update Checker", AppLog.buildReport(this)))
            // На Android 13+ система сама показывает подтверждение.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            AppLog.clear()
            refresh()
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