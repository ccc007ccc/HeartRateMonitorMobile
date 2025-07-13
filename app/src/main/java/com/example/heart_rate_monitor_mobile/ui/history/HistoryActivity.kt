package com.example.heart_rate_monitor_mobile.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.data.db.HeartRateSession
import com.example.heart_rate_monitor_mobile.databinding.ActivityHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity(), HistoryAdapterListener {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var db: AppDatabase
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getDatabase(this)

        setupToolbar()
        setupRecyclerView()
        observeHistory()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(this)
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter
    }

    private fun observeHistory() {
        db.heartRateDao().getAllSessions().observe(this) { sessions ->
            if (sessions.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.historyRecyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.historyRecyclerView.visibility = View.VISIBLE
                adapter.submitList(sessions)
            }
        }
    }

    override fun onItemClick(session: HeartRateSession) {
        if (actionMode != null) {
            toggleSelection(session.id)
        } else {
            val intent = Intent(this, ChartActivity::class.java).apply {
                putExtra("SESSION_ID", session.id)
            }
            startActivity(intent)
        }
    }

    override fun onItemLongClick(session: HeartRateSession) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(actionModeCallback)
        }
        toggleSelection(session.id)
    }

    private fun toggleSelection(sessionId: Long) {
        adapter.toggleSelection(sessionId)
        val selectedCount = adapter.getSelectedItems().size
        if (selectedCount == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "已选择 $selectedCount 项"
            actionMode?.invalidate()
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_history_contextual, menu)
            adapter.setMultiSelectMode(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_delete -> {
                    showDeleteConfirmationDialog()
                    true
                }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    actionMode?.title = "已选择 ${adapter.itemCount} 项"
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.setMultiSelectMode(false)
            actionMode = null
        }
    }

    private fun showDeleteConfirmationDialog() {
        val selectedIds = adapter.getSelectedItems().toList()
        if (selectedIds.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除选中的 ${selectedIds.size} 条历史记录吗？此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch {
                    db.heartRateDao().deleteSessionsByIds(selectedIds)
                }
                actionMode?.finish()
            }
            .show()
    }
}