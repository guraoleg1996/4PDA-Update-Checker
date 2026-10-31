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
import android.view.ViewConfiguration
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.os.Build
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MAIN_SCREEN_OPTION = "Главный экран"
        private const val CUSTOM_OPTION = "Своё..."
    }

    private lateinit var trackedAdapter: TrackedAdapter
    private lateinit var database: AppDatabase
    private var currentAmoledState: Boolean = false
    private var currentFolderName: String? = null
    private var loadDataJob: Job? = null

    private var hoveredFolderTarget: TrackedApp? = null
    private var isMovedDuringDrag: Boolean = false
    private var draggedInitialPosition: Int = -1
    private var draggedItem: TrackedApp? = null
    private var currentDraggedViewHolder: RecyclerView.ViewHolder? = null
    private var lastDragOrLongPressTime: Long = 0L

    private fun findHoveredFolder(
        recyclerView: RecyclerView,
        draggedHolder: RecyclerView.ViewHolder,
        draggedTop: Float,
        draggedBottom: Float
    ): Pair<RecyclerView.ViewHolder, TrackedApp>? {
        val currentDragItem = draggedItem ?: run {
            val pos = draggedHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in trackedAdapter.currentItems.indices) {
                trackedAdapter.getItem(pos)
            } else null
        }

        val draggedCenterY = (draggedTop + draggedBottom) / 2f

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child == draggedHolder.itemView) continue

            val holder = recyclerView.getChildViewHolder(child)
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos !in trackedAdapter.currentItems.indices) continue

            val folderItem = trackedAdapter.getItem(pos)
            if (!folderItem.isFolder) continue

            // Cannot drop into itself
            if (currentDragItem != null && currentDragItem.topicUrl == folderItem.topicUrl) continue
            if (currentDragItem != null && currentDragItem.isFolder && currentDragItem.appName == folderItem.appName) continue

            val childTop = child.top + child.translationY
            val childBottom = childTop + child.height
            val childCenterY = (childTop + childBottom) / 2f

            val overlapTop = maxOf(draggedTop, childTop)
            val overlapBottom = minOf(draggedBottom, childBottom)
            val overlapHeight = maxOf(0f, overlapBottom - overlapTop)

            val isOverFolder = overlapHeight >= child.height * 0.4f ||
                    (childCenterY in draggedTop..draggedBottom) ||
                    (draggedCenterY in childTop..childBottom)

            if (isOverFolder) {
                return Pair(holder, folderItem)
            }
        }
        return null
    }

    private fun updateFolderHighlights(recyclerView: RecyclerView, activeFolderHolder: RecyclerView.ViewHolder?) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child)
            if (holder is TrackedAdapter.FolderViewHolder) {
                holder.setHovered(holder == activeFolderHolder)
            }
        }
    }

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
            onClick = { app -> if (app.isFolder) onFolderClick(app) else showEditAppDialog(app) },
            onLongClick = { app -> if (app.isFolder) showEditFolderDialog(app) else showEditAppDialog(app) },
            onFolderClick = { folderName -> 
                currentFolderName = folderName
                supportActionBar?.title = folderName
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
                loadData()
            }
        )
        trackedAdapter.setLastDragTimeProvider { lastDragOrLongPressTime }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = trackedAdapter
        recyclerView.setChildDrawingOrderCallback { childCount, i ->
            val draggedView = currentDraggedViewHolder?.itemView
            if (draggedView == null) return@setChildDrawingOrderCallback i
            val draggedIndex = recyclerView.indexOfChild(draggedView)
            if (draggedIndex == -1) return@setChildDrawingOrderCallback i

            if (i == childCount - 1) {
                draggedIndex
            } else if (i >= draggedIndex) {
                i + 1
            } else {
                i
            }
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                val swipeFlags = 0
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun chooseDropTarget(
                selected: RecyclerView.ViewHolder,
                dropTargets: MutableList<RecyclerView.ViewHolder>,
                curX: Int,
                curY: Int
            ): RecyclerView.ViewHolder? {
                val draggedTop = curY.toFloat()
                val draggedBottom = (curY + selected.itemView.height).toFloat()
                val hovered = findHoveredFolder(recyclerView, selected, draggedTop, draggedBottom)

                hoveredFolderTarget = hovered?.second

                if (hovered != null) {
                    dropTargets.removeAll { it == hovered.first }
                }

                if (dropTargets.isEmpty()) return null
                return super.chooseDropTarget(selected, dropTargets, curX, curY)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                if (hoveredFolderTarget != null) {
                    val targetItem = trackedAdapter.getItem(toPos)
                    if (targetItem.topicUrl == hoveredFolderTarget?.topicUrl) {
                        return false
                    }
                }

                isMovedDuringDrag = true
                trackedAdapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    currentDraggedViewHolder = viewHolder
                    lastDragOrLongPressTime = System.currentTimeMillis()
                    isMovedDuringDrag = false
                    draggedInitialPosition = viewHolder?.bindingAdapterPosition ?: -1
                    draggedItem = if (draggedInitialPosition != RecyclerView.NO_POSITION && draggedInitialPosition in trackedAdapter.currentItems.indices) {
                        trackedAdapter.getItem(draggedInitialPosition)
                    } else null
                    hoveredFolderTarget = null
                    updateFolderHighlights(recyclerView, null)

                    viewHolder?.itemView?.alpha = 0.95f
                    viewHolder?.itemView?.scaleX = 1.04f
                    viewHolder?.itemView?.scaleY = 1.04f
                    viewHolder?.itemView?.translationZ = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics)
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

                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop * 2
                    if (Math.hypot(dX.toDouble(), dY.toDouble()) > touchSlop) {
                        isMovedDuringDrag = true
                    }

                    if (isCurrentlyActive) {
                        val draggedView = viewHolder.itemView
                        val draggedTop = draggedView.top + dY
                        val draggedBottom = draggedTop + draggedView.height
                        val hovered = findHoveredFolder(recyclerView, viewHolder, draggedTop, draggedBottom)
                        hoveredFolderTarget = hovered?.second
                        updateFolderHighlights(recyclerView, hovered?.first)
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                lastDragOrLongPressTime = System.currentTimeMillis()

                updateFolderHighlights(recyclerView, null)

                currentDraggedViewHolder = null
                viewHolder.itemView.animate().cancel()
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                viewHolder.itemView.translationX = 0f
                viewHolder.itemView.translationY = 0f
                viewHolder.itemView.translationZ = 0f
                if (viewHolder is TrackedAdapter.FolderViewHolder) {
                    viewHolder.cardView.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
                } else {
                    viewHolder.itemView.elevation = 0f
                }

                val pos = viewHolder.bindingAdapterPosition
                val currentItem = if (pos != RecyclerView.NO_POSITION && pos in trackedAdapter.currentItems.indices) {
                    trackedAdapter.getItem(pos)
                } else draggedItem

                val targetFolder = hoveredFolderTarget
                hoveredFolderTarget = null

                if (currentItem != null && targetFolder != null && targetFolder.topicUrl != currentItem.topicUrl) {
                    val index = trackedAdapter.currentItems.indexOfFirst { it.topicUrl == currentItem.topicUrl }
                    if (index != -1) {
                        trackedAdapter.removeItem(index)
                    }
                    saveNewOrder()

                    lifecycleScope.launch {
                        if (currentItem.isFolder && isDescendantOf(targetFolder, currentItem)) {
                            Toast.makeText(this@MainActivity, "Нельзя переместить папку саму в себя", Toast.LENGTH_SHORT).show()
                            loadData()
                        } else {
                            moveAppToFolder(currentItem, targetFolder.appName)
                        }
                    }
                } else {
                    if (!isMovedDuringDrag && currentItem != null && draggedInitialPosition == pos) {
                        if (currentItem.isFolder) {
                            showEditFolderDialog(currentItem)
                        } else {
                            showEditAppDialog(currentItem)
                        }
                    } else if (isMovedDuringDrag) {
                        saveNewOrder()
                    }
                }

                draggedItem = null
                recyclerView.invalidateItemDecorations()
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
        if (savedInstanceState == null) {
            Toast.makeText(this, "Обновление данных...", Toast.LENGTH_SHORT).show()
            refreshAll()
        }
    }

    private fun onFolderClick(folder: TrackedApp) {
        val name = folder.appName ?: return
        currentFolderName = name
        supportActionBar?.title = name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
        loadData()
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

    private suspend fun isDescendantOfByName(
        folderName: String,
        potentialAncestorName: String,
        allFolders: List<TrackedApp>? = null
    ): Boolean {
        var currentName: String? = folderName
        val foldersMap = (allFolders ?: database.trackedAppDao().getAllFolderEntities()).associateBy { it.appName }
        val visited = mutableSetOf<String>()

        while (currentName != null) {
            if (currentName == potentialAncestorName) return true
            if (!visited.add(currentName)) break
            val currentFolder = foldersMap[currentName]
            currentName = currentFolder?.folderName
        }
        return false
    }

    private suspend fun isDescendantOf(folder: TrackedApp, potentialAncestor: TrackedApp): Boolean {
        val folderName = folder.appName ?: return false
        val ancestorName = potentialAncestor.appName ?: return false
        return isDescendantOfByName(folderName, ancestorName)
    }

    private fun loadData() {
        loadDataJob?.cancel()
        loadDataJob = lifecycleScope.launch {
            database.trackedAppDao().getInFolder(currentFolderName).collectLatest { list ->
                trackedAdapter.submitList(list)
            }
        }
    }

    private fun moveAppToFolder(app: TrackedApp, folderName: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val maxOrder = database.trackedAppDao().getMaxSortOrder(folderName) ?: -1
            database.trackedAppDao().update(app.copy(folderName = folderName, sortOrder = maxOrder + 1))
            withContext(Dispatchers.Main) {
                val targetText = folderName ?: MAIN_SCREEN_OPTION
                Toast.makeText(this@MainActivity, "Перемещено в '$targetText'", Toast.LENGTH_SHORT).show()
            }
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
            val targetFolder = resolveFolderPath(folderName, currentFolderName) ?: folderName
            database.trackedAppDao().update(app1.copy(folderName = targetFolder))
            database.trackedAppDao().update(app2.copy(folderName = targetFolder))
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

    private fun setupLocationDropdown(
        autoCompleteTextView: AutoCompleteTextView,
        customEditText: EditText?,
        currentFolder: String?,
        excludeFolder: String? = null
    ) {
        lifecycleScope.launch {
            val allFolders = database.trackedAppDao().getAllFolderEntities()
            val validFolderNames = allFolders.filter { folder ->
                if (folder.appName == null) false
                else if (excludeFolder == null) true
                else folder.appName != excludeFolder && !isDescendantOfByName(folder.appName, excludeFolder, allFolders)
            }.mapNotNull { it.appName }.distinct()

            val dropdownOptions = mutableListOf(MAIN_SCREEN_OPTION)
            dropdownOptions.addAll(validFolderNames)
            dropdownOptions.add(CUSTOM_OPTION)

            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, dropdownOptions)
            autoCompleteTextView.setAdapter(adapter)

            if (currentFolder == null) {
                autoCompleteTextView.setText(MAIN_SCREEN_OPTION, false)
                customEditText?.visibility = View.GONE
            } else if (validFolderNames.contains(currentFolder)) {
                autoCompleteTextView.setText(currentFolder, false)
                customEditText?.visibility = View.GONE
            } else {
                autoCompleteTextView.setText(CUSTOM_OPTION, false)
                customEditText?.visibility = View.VISIBLE
                customEditText?.setText(currentFolder)
            }

            autoCompleteTextView.setOnItemClickListener { _, _, position, _ ->
                val selected = adapter.getItem(position)
                if (selected == CUSTOM_OPTION) {
                    customEditText?.visibility = View.VISIBLE
                    customEditText?.requestFocus()
                } else {
                    customEditText?.visibility = View.GONE
                }
            }
        }
    }

    private fun getSelectedLocation(
        autoCompleteTextView: AutoCompleteTextView,
        customEditText: EditText?
    ): String? {
        val selectedText = autoCompleteTextView.text.toString().trim()
        if (selectedText == CUSTOM_OPTION || (customEditText != null && customEditText.visibility == View.VISIBLE)) {
            val customText = customEditText?.text?.toString()?.trim() ?: ""
            return if (customText.isEmpty() || customText == MAIN_SCREEN_OPTION) null else customText
        }
        if (selectedText.isEmpty() || selectedText == MAIN_SCREEN_OPTION) {
            return null
        }
        return selectedText
    }

    private suspend fun resolveFolderPath(path: String?, startParent: String? = null): String? {
        if (path == null || path.trim().isEmpty() || path.trim() == MAIN_SCREEN_OPTION) return null
        val parts = path.split("/").filter { it.isNotEmpty() }
        var currentParent: String? = startParent

        for (part in parts) {
            val existing = database.trackedAppDao().getFolderByNameAndParent(part, currentParent)
            if (existing == null) {
                val maxOrder = database.trackedAppDao().getMaxSortOrder(currentParent) ?: -1
                val newFolder = TrackedApp(
                    topicUrl = "folder_${UUID.randomUUID()}",
                    appName = part,
                    isFolder = true,
                    folderName = currentParent,
                    sortOrder = maxOrder + 1
                )
                database.trackedAppDao().insert(newFolder)
            }
            currentParent = part
        }
        return currentParent
    }

    private fun showAddAppDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_app, null)
        val editUrl = view.findViewById<EditText>(R.id.editTopicUrl)
        val editAppName = view.findViewById<EditText>(R.id.editAppName)
        val editPackage = view.findViewById<EditText>(R.id.editPackageName)
        val editFolder = view.findViewById<AutoCompleteTextView>(R.id.editFolderName)
        val editCustomFolder = view.findViewById<EditText>(R.id.editCustomFolderName)

        setupLocationDropdown(editFolder, editCustomFolder, currentFolderName)

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
            val rawLocation = getSelectedLocation(editFolder, editCustomFolder)

            if (url.isEmpty()) {
                editUrl.error = "Введите ссылку"
                return@setOnClickListener
            }

            if (rawLocation != null && !isValidPath(rawLocation, editFolder)) return@setOnClickListener

            addNewApp(url, name, pkg, rawLocation)
            dialog.dismiss()
        }
    }

    private fun showAddFolderDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_folder, null)
        val editName = view.findViewById<EditText>(R.id.editFolderName)
        val editParent = view.findViewById<AutoCompleteTextView>(R.id.editParentFolder)
        val editCustomParent = view.findViewById<EditText>(R.id.editCustomParentFolder)

        setupLocationDropdown(editParent, editCustomParent, currentFolderName)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Создать папку")
            .setView(view)
            .setPositiveButton("Создать", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text.toString().trim()
            val rawParent = getSelectedLocation(editParent, editCustomParent)

            if (name.isEmpty()) {
                editName.error = "Введите название"
                return@setOnClickListener
            }

            if (!isValidPath(name, editName)) return@setOnClickListener
            if (rawParent != null && !isValidPath(rawParent, editParent)) return@setOnClickListener

            addNewFolder(name, rawParent)
            dialog.dismiss()
        }
    }

    private fun addNewFolder(path: String, startParentLocation: String? = currentFolderName) {
        lifecycleScope.launch(Dispatchers.IO) {
            val baseParent = resolveFolderPath(startParentLocation, currentFolderName)
            resolveFolderPath(path, baseParent)
        }
    }

    private fun showEditFolderDialog(app: TrackedApp) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_folder, null)
        val editName = view.findViewById<EditText>(R.id.editFolderName)
        val editParent = view.findViewById<AutoCompleteTextView>(R.id.editParentFolder)
        val editCustomParent = view.findViewById<EditText>(R.id.editCustomParentFolder)

        editName.setText(app.appName)
        setupLocationDropdown(editParent, editCustomParent, app.folderName, excludeFolder = app.appName)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Редактировать папку")
            .setView(view)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = editName.text.toString().trim()
            val rawParentLocation = getSelectedLocation(editParent, editCustomParent)

            if (newName.isEmpty()) {
                editName.error = "Название не может быть пустым"
                return@setOnClickListener
            }
            if (newName.contains("/")) {
                editName.error = "Название папки не должно содержать /"
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val targetParentFolder = resolveFolderPath(rawParentLocation, currentFolderName)
                val oldName = app.appName

                if (targetParentFolder != null && oldName != null) {
                    if (targetParentFolder == oldName || isDescendantOfByName(targetParentFolder, oldName)) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Нельзя переместить папку в себя или в свою подпапку", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                }

                var updatedApp = app.copy(folderName = targetParentFolder)

                if (newName != oldName && oldName != null) {
                    updatedApp = updatedApp.copy(appName = newName)
                    val children = database.trackedAppDao().getInFolderList(oldName)
                    database.trackedAppDao().updateAll(children.map { it.copy(folderName = newName) })
                }
                database.trackedAppDao().update(updatedApp)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun addNewApp(topicUrl: String, manualName: String?, manualPackageName: String?, folderPath: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFolder = resolveFolderPath(folderPath, currentFolderName)

            val appData = UpdateChecker.fetchAppData(topicUrl)
            val detectedName = manualName ?: appData.name
            val siteVersion = appData.version
            val detectedPackage = manualPackageName ?: appData.packageName
            val installedVersion = detectedPackage?.let { AppVersionHelper.getVersion(this@MainActivity, it) }
            val maxOrder = database.trackedAppDao().getMaxSortOrder(targetFolder) ?: -1
            
            val newApp = TrackedApp(
                topicUrl = topicUrl,
                appName = detectedName,
                packageName = detectedPackage,
                currentVersionOnSite = siteVersion,
                installedVersion = installedVersion,
                lastCheckTime = System.currentTimeMillis(),
                sortOrder = maxOrder + 1,
                folderName = targetFolder,
                isFolder = false
            )
            database.trackedAppDao().insert(newApp)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Добавлено: ${detectedName ?: topicUrl}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveNewOrder() {
        val currentList = trackedAdapter.currentItems
        lifecycleScope.launch(Dispatchers.IO) {
            database.trackedAppDao().updateAll(currentList.mapIndexed { index, app -> app.copy(sortOrder = index) })
        }
    }

    private fun refreshApp(app: TrackedApp) {
        if (app.isFolder) return
        lifecycleScope.launch(Dispatchers.IO) {
            refreshAppInternal(app)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo != null && networkInfo.isConnected
        }
    }

    private suspend fun refreshAppInternal(app: TrackedApp): Boolean {
        return try {
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
            !appData.isError
        } catch (e: Exception) {
            AppLog.e("MainActivity", "Ошибка при обновлении элемента ${app.appName ?: app.topicUrl}", e)
            val updatedApp = app.copy(lastUpdateError = true)
            database.trackedAppDao().update(updatedApp)
            false
        }
    }

    private fun refreshAll() {
        val progressBar = findViewById<LinearProgressIndicator>(R.id.updateProgress)
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this@MainActivity, "Отсутствует подключение к интернету", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val appsToCheck = withContext(Dispatchers.IO) {
                database.trackedAppDao().getAllList().filter { !it.isFolder }
            }

            if (appsToCheck.isEmpty()) {
                Toast.makeText(this@MainActivity, "Список пуст", Toast.LENGTH_SHORT).show()
                return@launch
            }

            progressBar.isIndeterminate = false
            progressBar.max = appsToCheck.size
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE

            val semaphore = Semaphore(3)
            val successCount = AtomicInteger(0)
            val errorCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)

            coroutineScope {
                val jobs = appsToCheck.map { app ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val success = refreshAppInternal(app)
                            if (success) successCount.incrementAndGet() else errorCount.incrementAndGet()
                            val done = completedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                progressBar.progress = done
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            progressBar.visibility = View.GONE
            
            val totalErrors = errorCount.get()
            val totalSuccess = successCount.get()
            val message = if (totalErrors > 0) {
                "Обновление завершено. Успешно: $totalSuccess, с ошибкой: $totalErrors"
            } else {
                "Обновление завершено ($totalSuccess)"
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
        val editCustomFolder = view.findViewById<EditText>(R.id.editCustomFolderName)

        editUrl.setText(app.topicUrl)
        editAppName.setText(app.appName)
        editPackage.setText(app.packageName)
        setupLocationDropdown(editFolder, editCustomFolder, app.folderName)

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
            val rawLocation = getSelectedLocation(editFolder, editCustomFolder)

            if (newUrl.isEmpty()) {
                editUrl.error = "Введите ссылку"
                return@setOnClickListener
            }

            if (rawLocation != null && !isValidPath(rawLocation, editFolder)) return@setOnClickListener

            lifecycleScope.launch(Dispatchers.IO) {
                val newFolder = resolveFolderPath(rawLocation, currentFolderName)
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
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
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
