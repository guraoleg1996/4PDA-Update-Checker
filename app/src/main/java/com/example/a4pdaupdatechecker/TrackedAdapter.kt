package com.example.a4pdaupdatechecker

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TrackedAdapter(
    private val onDelete: (TrackedApp) -> Unit,
    private val onClick: (TrackedApp) -> Unit,
    private val onFolderClick: (String) -> Unit
) : ListAdapter<TrackedApp, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_FOLDER = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFolder) TYPE_FOLDER else TYPE_APP
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textAppName: TextView = view.findViewById(R.id.textAppName)
        val textUrl: TextView = view.findViewById(R.id.textUrl)
        val textPackage: TextView = view.findViewById(R.id.textPackage)
        val textSiteVersion: TextView = view.findViewById(R.id.textSiteVersion)
        val textInstalledVersion: TextView = view.findViewById(R.id.textInstalledVersion)
        val textStatus: TextView = view.findViewById(R.id.textStatus)
        val textFolder: TextView = view.findViewById(R.id.textFolder)
        val btnClose: ImageButton = view.findViewById(R.id.btnClose)
        val btnGoToUpdate: Button = view.findViewById(R.id.btnGoToUpdate)
        val btnGoToPage: Button = view.findViewById(R.id.btnGoToPage)
    }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textFolderName: TextView = view.findViewById(R.id.textFolderName)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteFolder)
    }

    private fun formatVersion(version: String?): String? {
        if (version == null) return null
        return version.trim().replace(Regex("^[vV]"), "")
    }

    private fun openUrl(view: View, url: String) {
        val context = view.context
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val pm = context.packageManager
        // Получаем все приложения, которые могут открыть эту ссылку
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        
        val targetIntents = mutableListOf<Intent>()
        for (resolveInfo in activities) {
            val packageName = resolveInfo.activityInfo.packageName
            // Не добавляем текущее приложение
            if (packageName != context.packageName) {
                val target = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(packageName)
                }
                targetIntents.add(target)
            }
        }

        if (targetIntents.isNotEmpty()) {
            // Создаем выбор вручную из всех найденных приложений. 
            // Это обходит настройку "браузер по умолчанию" и форсирует диалог.
            val chooserIntent = Intent.createChooser(targetIntents.removeAt(0), "Открыть с помощью")
            if (targetIntents.isNotEmpty()) {
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetIntents.toTypedArray())
            }
            if (context !is android.app.Activity) {
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } else {
            // Если приложений не найдено (маловероятно), запускаем стандартно
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Не найдено приложений для открытия ссылки", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOLDER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder, parent, false)
            FolderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tracked_app, parent, false)
            ViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val app = getItem(position)
        if (holder is FolderViewHolder) {
            holder.textFolderName.text = app.appName
            holder.itemView.setOnClickListener { onFolderClick(app.appName!!) }
            holder.btnDelete.setOnClickListener { onDelete(app) }
        } else if (holder is ViewHolder) {
            holder.textAppName.text = app.appName ?: "Без названия"
            holder.textUrl.text = app.topicUrl
            holder.textPackage.text = app.packageName ?: "Пакет не указан"
            holder.textSiteVersion.text = formatVersion(app.currentVersionOnSite) ?: "—"
            holder.textInstalledVersion.text = formatVersion(app.installedVersion) ?: "Не установлено"

            holder.textFolder.visibility = View.GONE

            val updateAvailable = app.currentVersionOnSite != null && 
                                 app.installedVersion != null && 
                                 UpdateChecker.isUpdateAvailable(app.currentVersionOnSite, app.installedVersion)

            if (updateAvailable) {
                holder.textStatus.visibility = View.VISIBLE
                holder.textStatus.text = "✅ ДОСТУПНО ОБНОВЛЕНИЕ!"
                holder.textStatus.setTextColor(Color.parseColor("#4CAF50"))
                holder.btnGoToUpdate.visibility = View.VISIBLE
            } else if (app.currentVersionOnSite != null && app.installedVersion != null) {
                holder.textStatus.visibility = View.VISIBLE
                holder.textStatus.text = "✓ Актуальная версия"
                holder.textStatus.setTextColor(Color.GRAY)
                holder.btnGoToUpdate.visibility = View.GONE
            } else {
                holder.textStatus.visibility = View.GONE
                holder.btnGoToUpdate.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(app) }
            
            holder.btnClose.setOnClickListener {
                onDelete(app)
            }

            holder.btnGoToUpdate.setOnClickListener {
                openUrl(it, app.topicUrl)
            }

            holder.btnGoToPage.setOnClickListener {
                openUrl(it, app.topicUrl)
            }
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val list = currentList.toMutableList()
        val item = list.removeAt(fromPosition)
        list.add(toPosition, item)
        submitList(list)
    }

    class DiffCallback : DiffUtil.ItemCallback<TrackedApp>() {
        override fun areItemsTheSame(oldItem: TrackedApp, newItem: TrackedApp) = oldItem.topicUrl == newItem.topicUrl
        override fun areContentsTheSame(oldItem: TrackedApp, newItem: TrackedApp) = oldItem == newItem
    }
}