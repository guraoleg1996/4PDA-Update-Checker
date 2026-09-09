package com.example.a4pdaupdatechecker

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {

    private lateinit var trackedAdapter: TrackedAdapter
    private lateinit var database: AppDatabase
    private var currentAmoledState: Boolean = false
    private var currentFolderName: String? = null
    private var loadDataJob: Job? = null

    private var hoveredFolderPosition: Int = -1
    private var potentialMergeTargetPosition: Int = -1
    private var potentialMergeTargetItem: TrackedApp? = null
    private var lastMoveTime: Long = 0
    private var lastTargetPos: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyAmoledToActivity(this)
        currentAmoledState = ThemeHelper.isAmoledEnabled(this)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentFolderName = savedInstanceState?.getString("current_folder")

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fabMenu = findViewById<View>(R.id.fabMenu)
                val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
                val fabOverlay = findViewById<View>(R.id.fabOverlay)
                if (fabMenu.visibility == View.VISIBLE) {
                    fabMenu.visibility = View.GONE
                    fabOverlay.visibility = View.GONE
                    fabAdd.setImageResource(android.R.drawable.ic_input_add)
                    fabAdd.animate().rotation(0f).start()
                } else if (currentFolderName != null) {
                    navigateBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        ThemeHelper.updateStatusBarIcons(this)
        database = AppDatabase.getDatabase(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        if (currentFolderName != null) {
            supportActionBar?.title = currentFolderName
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
        }

        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            val threshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics)
            findViewById<View>(R.id.bottomDivider).visibility = if (navBars.bottom > threshold) View.VISIBLE else View.GONE

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        trackedAdapter = TrackedAdapter(
            onDelete = { app -> showDeleteConfirmation(app) },
            onClick = { app -> if (app.isFolder) showEditFolderDialog(app) else showEditAppDialog(app) },
            onLongClick = { app -> if (app.isFolder) showEditFolderDialog(app) else showEditAppDialog(app) },
            onFolderClick = { folderName -> 
                currentFolderName = folderName
                supportActionBar?.title = folderName
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
                loadData()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = trackedAdapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                val swipeFlags = 0
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                val targetItem = trackedAdapter.currentList.getOrNull(toPos)
                if (targetItem != null && targetItem != potentialMergeTargetItem) {
                    potentialMergeTargetItem = targetItem
                    potentialMergeTargetPosition = toPos
                    lastMoveTime = System.currentTimeMillis()
                }

                trackedAdapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.8f
                    viewHolder?.itemView?.scaleX = 1.05f
                    viewHolder?.itemView?.scaleY = 1.05f
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    val draggedView = viewHolder.itemView
                    // Центр перетаскиваемого элемента по вертикали
                    val centerY = draggedView.top + dY + draggedView.height / 2

                    var found = false
                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i)
                        if (child == draggedView) continue

                        // Проверяем, находится ли центр перетаскиваемого элемента близко к центру цели
                        // (в пределах 20% высоты цели), чтобы отличить "наложение" от "сортировки"
                        val childCenterY = child.top + child.height / 2
                        val tolerance = child.height * 0.2f

                        if (centerY > childCenterY - tolerance && centerY < childCenterY + tolerance) {
                            val targetPos = recyclerView.getChildAdapterPosition(child)
                            val targetItem = trackedAdapter.currentList.getOrNull(targetPos)
                            
                            if (targetItem != null && targetItem != potentialMergeTargetItem) {
                                potentialMergeTargetItem = targetItem
                                potentialMergeTargetPosition = targetPos
                                lastMoveTime = System.currentTimeMillis()
                            }
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        potentialMergeTargetItem = null
                        potentialMergeTargetPosition = -1
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                
                val pos = viewHolder.bindingAdapterPosition
                val timeSinceLastMove = System.currentTimeMillis() - lastMoveTime
                val targetItem = potentialMergeTargetItem
                
                if (pos != RecyclerView.NO_POSITION && targetItem != null && timeSinceLastMove > 500) {
                    val draggedItem = trackedAdapter.currentList[pos]
                    
                    if (targetItem != draggedItem) {
                        if (targetItem.isFolder) {
                            lifecycleScope.launch {
                                if (draggedItem.isFolder && isDescendantOf(targetItem, draggedItem)) {
                                    Toast.makeText(this@MainActivity, "Нельзя переместить папку саму в себя", Toast.LENGTH_SHORT).show()
                                } else {
                                    showMoveToFolderDialog(draggedItem, targetItem)
                                }
                            }
                        } else if (!draggedItem.isFolder) {
                            showMergeDialog(draggedItem, targetItem)
                        }
                    }
                }
                
                hoveredFolderPosition = -1
                potentialMergeTargetPosition = -1
                potentialMergeTargetItem = null
                saveNewOrder()

                // Форсируем перерисовку для устранения "глюков" наложения карточек
                recyclerView.invalidateItemDecorations()
                recyclerView.post { trackedAdapter.notifyDataSetChanged() }
            }

            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long
            ): Int {
                val standard = super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll)
                val direction = if (viewSizeOutOfBounds > 0) 1 else -1
                
                if (Math.abs(viewSizeOutOfBounds) > 2) {
                    val multiplier = (Math.abs(viewSizeOutOfBounds) / 20).coerceAtLeast(1).coerceAtMost(3)
                    return standard + (direction * 10 * multiplier)
                }
                return standard
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)

        val fabMenu = findViewById<View>(R.id.fabMenu)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)
        val fabOverlay = findViewById<View>(R.id.fabOverlay)

        fun closeFabMenu() {
            fabMenu.visibility = View.GONE
            fabOverlay.visibility = View.GONE
            fabAdd.setImageResource(android.R.drawable.ic_input_add)
            fabAdd.animate().rotation(0f).start()
        }

        fun openFabMenu() {
            fabMenu.visibility = View.VISIBLE
            fabOverlay.visibility = View.VISIBLE
            fabAdd.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            fabAdd.animate().rotation(90f).start()
        }

        fabAdd.setOnClickListener {
            if (fabMenu.visibility == View.VISIBLE) {
                closeFabMenu()
            } else {
                openFabMenu()
            }
        }

        fabOverlay.setOnClickListener {
            closeFabMenu()
        }

        findViewById<View>(R.id.btnAddApp).setOnClickListener {
            closeFabMenu()
            showAddAppDialog()
        }

        findViewById<View>(R.id.btnAddFolder).setOnClickListener {
            closeFabMenu()
            showAddFolderDialog()
        }

        loadData()
        Toast.makeText(this, "Обновление данных...", Toast.LENGTH_SHORT).show()
        refreshAll()
    }

    private fun navigateBack() {
        lifecycleScope.launch {
            val folder = currentFolderName?.let { database.trackedAppDao().getFolderByName(it) }
            currentFolderName = folder?.folderName
            
            if (currentFolderName == null) {
                supportActionBar?.title = "4PDA Update Checker"
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            } else {
                supportActionBar?.title = currentFolderName
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
            }
            loadData()
        }
    }

    private suspend fun isDescendantOf(folder: TrackedApp, potentialAncestor: TrackedApp): Boolean {
        var current: TrackedApp? = folder
        while (current != null) {
            if (current.appName == potentialAncestor.appName) return true
            current = current.folderName?.let { database.trackedAppDao().getFolderByName(it) }
        }
        return false
    }

    private fun loadData() {
        loadDataJob?.cancel()
        loadDataJob = lifecycleScope.launch {
            database.trackedAppDao().getInFolder(currentFolderName).collectLatest { list ->
                trackedAdapter.submitList(list)
            }
        }
    }

    private fun showMoveToFolderDialog(app: TrackedApp, folder: TrackedApp) {
        AlertDialog.Builder(this)
            .setTitle("Переместить в папку?")
            .setMessage("Переместить '${app.appName}' в папку '${folder.appName}'?")
            .setPositiveButton("Да") { _, _ ->
                moveAppToFolder(app, folder.appName)
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun moveAppToFolder(app: TrackedApp, folderName: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            database.trackedAppDao().update(app.copy(folderName = folderName))
        }
    }

    private fun showMergeDialog(app1: TrackedApp, app2: TrackedApp) {
        AlertDialog.Builder(this)
            .setTitle("Создать папку?")
            .setMessage("Объединить '${app1.appName}' и '${app2.appName}' в новую папку?")
            .setPositiveButton("Да") { _, _ -> showCreateFolderWithAppsDialog(app1, app2) }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun showCreateFolderWithAppsDialog(app1: TrackedApp, app2: TrackedApp) {
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val editText = EditText(this).apply {
            hint = "Название папки"
        }
        
        val hintText = TextView(this).apply {
            text = "Можно вводить путь через / (например, Мои игры/Action и RPG)"
            textSize = 11f
            setTextColor(Color.GRAY)
        }

        container.addView(editText)
        container.addView(hintText)

        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(container)
            .setPositiveButton("Создать") { _, _ ->
                val folderName = editText.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    createFolderWithApps(folderName, app1, app2)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun createFolderWithApps(folderName: String, app1: TrackedApp, app2: TrackedApp) {
        lifecycleScope.launch(Dispatchers.IO) {
            val maxOrder = database.trackedAppDao().getMaxSortOrder(currentFolderName) ?: -1
            val newFolder = TrackedApp(
                topicUrl = "folder_$folderName",
                appName = folderName,
                isFolder = true,
                folderName = currentFolderName,
                sortOrder = maxOrder + 1
            )
            database.trackedAppDao().insert(newFolder)
            database.trackedAppDao().update(app1.copy(folderName = folderName))
            database.trackedAppDao().update(app2.copy(folderName = folderName))
        }
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        val newState = ThemeHelper.isAmoledEnabled(this)
        if (newState != currentAmoledState) recreate()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Обновить все").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setIcon(android.R.drawable.stat_notify_sync)
        }
        menu.add(0, 2, 0, "Настройки").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setIcon(android.R.drawable.ic_menu_preferences)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return when (item.itemId) {
            1 -> { 
                refreshAll()
                true 
            }
            2 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddAppDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_app, null)
        val editUrl = view.findViewById<EditText>(R.id.editTopicUrl)
        val editAppName = view.findViewById<EditText>(R.id.editAppName)
        val editPackage = view.findViewById<EditText>(R.id.editPackageName)
        val editFolder = view.findViewById<AutoCompleteTextView>(R.id.editFolderName)

        editFolder.setText(currentFolderName)

        lifecycleScope.launch {
            val folders = database.trackedAppDao().getAllFolders().first()
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, folders)
            editFolder.setAdapter(adapter)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Добавить ссылку на 4PDA")
            .setView(view)
            .setPositiveButton("Добавить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = editUrl.text.toString().trim()
            val name = editAppName.text.toString().trim().ifEmpty { null }
            val pkg = editPackage.text.toString().trim().ifEmpty { null }
            val folder = editFolder.text.toString().trim().ifEmpty { null }
            
            if (url.isEmpty()) {
                editUrl.error = "Введите ссылку"
                return@setOnClickListener
            }
            
            if (folder != null && !isValidPath(folder, editFolder)) return@setOnClickListener
            
            addNewApp(url, name, pkg, folder)
            dialog.dismiss()
        }
    }

    private fun showAddFolderDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_folder, null)
        val editName = view.findViewById<EditText>(R.id.editFolderName)
        val editParent = view.findViewById<AutoCompleteTextView>(R.id.editParentFolder)

        editParent.setText(currentFolderName)

        lifecycleScope.launch {
            val folders = database.trackedAppDao().getAllFolders().first()
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, folders)
            editParent.setAdapter(adapter)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Создать папку")
            .setView(view)
            .setPositiveButton("Создать", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text.toString().trim()
            val parent = editParent.text.toString().trim().ifEmpty { null }
            
            if (name.isEmpty()) {
                editName.error = "Введите название"
                return@setOnClickListener
            }
            
            if (!isValidPath(name, editName)) return@setOnClickListener
            if (parent != null && !isValidPath(parent, editParent)) return@setOnClickListener
            
            addNewFolder(name, parent)
            dialog.dismiss()
        }
    }

    private fun addNewFolder(path: String, startParent: String? = currentFolderName) {
        lifecycleScope.launch(Dispatchers.IO) {
            val parts = path.split("/").filter { it.isNotEmpty() }
            var currentParent: String? = startParent
            
            for (part in parts) {
                val existing = database.trackedAppDao().getFolderByNameAndParent(part, currentParent)
                if (existing == null) {
                    val maxOrder = database.trackedAppDao().getMaxSortOrder(currentParent) ?: -1
                    val newFolder = TrackedApp(
                        topicUrl = "folder_${currentParent ?: "root"}_$part",
                        appName = part,
                        isFolder = true,
                        folderName = currentParent,
                        sortOrder = maxOrder + 1
                    )
                    database.trackedAppDao().insert(newFolder)
                }
                currentParent = part
            }
        }
    }

    private fun showEditFolderDialog(app: TrackedApp) {
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val editText = EditText(this).apply {
            setText(app.appName)
        }
        
        val hintText = TextView(this).apply {
            text = "Можно вводить путь через / (например, Мои игры/Action и RPG)"
            textSize = 11f
            setTextColor(Color.GRAY)
        }

        container.addView(editText)
        container.addView(hintText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Редактировать папку")
            .setView(container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = editText.text.toString().trim()
            if (newName.isEmpty()) {
                editText.error = "Название не может быть пустым"
                return@setOnClickListener
            }
            if (newName.contains("/")) {
                editText.error = "Название не должно содержать /"
                return@setOnClickListener
            }
            if (newName != app.appName) {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.trackedAppDao().update(app.copy(appName = newName))
                    val children = database.trackedAppDao().getInFolderList(app.appName)
                    database.trackedAppDao().updateAll(children.map { it.copy(folderName = newName) })
                }
            }
            dialog.dismiss()
        }
    }

    private fun addNewApp(topicUrl: String, manualName: String?, manualPackageName: String?, folderPath: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            var lastFolder: String? = null
            
            if (folderPath != null) {
                val parts = folderPath.split("/").filter { it.isNotEmpty() }
                var currentParent: String? = null
                
                for (part in parts) {
                    val existing = database.trackedAppDao().getFolderByNameAndParent(part, currentParent)
                    if (existing == null) {
                        val maxOrder = database.trackedAppDao().getMaxSortOrder(currentParent) ?: -1
                        val newFolder = TrackedApp(
                            topicUrl = "folder_${currentParent ?: "root"}_$part",
                            appName = part,
                            isFolder = true,
                            folderName = currentParent,
                            sortOrder = maxOrder + 1
                        )
                        database.trackedAppDao().insert(newFolder)
                    }
                    currentParent = part
                    lastFolder = part
                }
            }

            val appData = UpdateChecker.fetchAppData(topicUrl)
            val detectedName = manualName ?: appData.name
            val siteVersion = appData.version
            val detectedPackage = manualPackageName ?: appData.packageName
            val installedVersion = detectedPackage?.let { AppVersionHelper.getVersion(this@MainActivity, it) }
            val maxOrder = database.trackedAppDao().getMaxSortOrder(lastFolder) ?: -1
            
            val newApp = TrackedApp(
                topicUrl = topicUrl,
                appName = detectedName,
                packageName = detectedPackage,
                currentVersionOnSite = siteVersion,
                installedVersion = installedVersion,
                lastCheckTime = System.currentTimeMillis(),
                sortOrder = maxOrder + 1,
                folderName = lastFolder,
                isFolder = false
            )
            database.trackedAppDao().insert(newApp)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Добавлено: ${detectedName ?: topicUrl}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveNewOrder() {
        val currentList = trackedAdapter.currentList
        lifecycleScope.launch(Dispatchers.IO) {
            database.trackedAppDao().updateAll(currentList.mapIndexed { index, app -> app.copy(sortOrder = index) })
        }
    }

    private fun refreshApp(app: TrackedApp) {
        if (app.isFolder) return
        lifecycleScope.launch(Dispatchers.IO) {
            val siteVersion = UpdateChecker.parseVersionFromTopic(app.topicUrl)
            val detectedName = app.appName ?: UpdateChecker.parseAppName(app.topicUrl)
            val detectedPackage = app.packageName ?: UpdateChecker.parsePackageName(app.topicUrl)
            val installedVersion = detectedPackage?.let { AppVersionHelper.getVersion(this@MainActivity, it) }
            database.trackedAppDao().update(app.copy(
                appName = detectedName,
                packageName = detectedPackage,
                currentVersionOnSite = siteVersion,
                installedVersion = installedVersion,
                lastCheckTime = System.currentTimeMillis()
            ))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun refreshAppInternal(app: TrackedApp): Boolean {
        val appData = UpdateChecker.fetchAppData(app.topicUrl)
        
        val updatedApp = if (appData.isError) {
            app.copy(lastUpdateError = true)
        } else {
            val siteVersion = appData.version
            val detectedName = app.appName ?: appData.name
            val detectedPackage = app.packageName ?: appData.packageName
            val installedVersion = detectedPackage?.let { AppVersionHelper.getVersion(this@MainActivity, it) }
            
            app.copy(
                appName = detectedName,
                packageName = detectedPackage,
                currentVersionOnSite = siteVersion,
                installedVersion = installedVersion,
                lastCheckTime = System.currentTimeMillis(),
                lastUpdateError = false
            )
        }
        
        database.trackedAppDao().update(updatedApp)
        return !appData.isError
    }

    private fun refreshAll() {
        val progressBar = findViewById<LinearProgressIndicator>(R.id.updateProgress)
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this@MainActivity, "Отсутствует подключение к интернету", Toast.LENGTH_SHORT).show()
                return@launch
            }

            progressBar.visibility = View.VISIBLE
            var successCount = 0
            var errorCount = 0
            var connectionIssue = false

            withContext(Dispatchers.IO) {
                val apps = database.trackedAppDao().getAllList()
                for (app in apps) {
                    if (app.isFolder) continue
                    
                    if (!isNetworkAvailable()) {
                        connectionIssue = true
                        break
                    }

                    val success = refreshAppInternal(app)
                    if (success) successCount++ else errorCount++
                    delay(500)
                }
            }

            progressBar.visibility = View.GONE
            
            val message = when {
                connectionIssue -> "Проблема с интернетом"
                errorCount > 0 -> "Проблема с обновлением, ошибок: $errorCount"
                else -> "Обновление завершено"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(app: TrackedApp) {
        AlertDialog.Builder(this)
            .setTitle("Удалить из отслеживания?")
            .setMessage(app.appName ?: app.topicUrl)
            .setPositiveButton("Удалить") { _, _ -> deleteApp(app) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteApp(app: TrackedApp) {
        lifecycleScope.launch(Dispatchers.IO) {
            val toDelete = if (app.isFolder) {
                listOf(app) + getAllChildrenRec(app.appName)
            } else {
                listOf(app)
            }
            
            database.trackedAppDao().deleteAll(toDelete)
            
            withContext(Dispatchers.Main) {
                Snackbar.make(findViewById(R.id.main_root), "Удалено: ${app.appName ?: app.topicUrl}", Snackbar.LENGTH_LONG)
                    .setAction("ОТМЕНИТЬ") { 
                        lifecycleScope.launch(Dispatchers.IO) { 
                            database.trackedAppDao().insertAll(toDelete) 
                        } 
                    }
                    .show()
            }
        }
    }

    private suspend fun getAllChildrenRec(parentName: String?): List<TrackedApp> {
        if (parentName == null) return emptyList()
        val children = database.trackedAppDao().getInFolderList(parentName)
        val result = mutableListOf<TrackedApp>()
        for (child in children) {
            result.add(child)
            if (child.isFolder) {
                result.addAll(getAllChildrenRec(child.appName))
            }
        }
        return result
    }

    private fun showEditAppDialog(app: TrackedApp) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_app, null)
        val editUrl = view.findViewById<EditText>(R.id.editTopicUrl)
        val editAppName = view.findViewById<EditText>(R.id.editAppName)
        val editPackage = view.findViewById<EditText>(R.id.editPackageName)
        val editFolder = view.findViewById<AutoCompleteTextView>(R.id.editFolderName)

        editUrl.setText(app.topicUrl)
        editAppName.setText(app.appName)
        editPackage.setText(app.packageName)
        editFolder.setText(app.folderName)

        lifecycleScope.launch {
            val folders = database.trackedAppDao().getAllFolders().first()
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, folders)
            editFolder.setAdapter(adapter)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Редактировать")
            .setView(view)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newUrl = editUrl.text.toString().trim()
            val newName = editAppName.text.toString().trim().ifEmpty { null }
            val newPkg = editPackage.text.toString().trim().ifEmpty { null }
            val newFolder = editFolder.text.toString().trim().ifEmpty { null }
            
            if (newUrl.isEmpty()) {
                editUrl.error = "Введите ссылку"
                return@setOnClickListener
            }
            
            if (newFolder != null && !isValidPath(newFolder, editFolder)) return@setOnClickListener

            lifecycleScope.launch(Dispatchers.IO) {
                if (newUrl != app.topicUrl) {
                    database.trackedAppDao().delete(app)
                    val newApp = app.copy(topicUrl = newUrl, appName = newName, packageName = newPkg, folderName = newFolder, isFolder = false)
                    database.trackedAppDao().insert(newApp)
                    refreshApp(newApp)
                } else {
                    val updatedApp = app.copy(appName = newName, packageName = newPkg, folderName = newFolder)
                    database.trackedAppDao().update(updatedApp)
                    refreshApp(updatedApp)
                }
            }
            dialog.dismiss()
        }
    }

    private fun isValidPath(path: String, editText: EditText): Boolean {
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            editText.error = "Некорректный путь: проверьте слэши"
            return false
        }
        val parts = path.split("/")
        if (parts.any { it.trim().isEmpty() }) {
            editText.error = "Путь содержит пустые сегменты"
            return false
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_folder", currentFolderName)
    }
}