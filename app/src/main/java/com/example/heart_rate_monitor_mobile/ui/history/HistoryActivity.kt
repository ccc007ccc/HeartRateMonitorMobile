package com.example.heart_rate_monitor_mobile.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.db.SessionWithDevices
import com.example.heart_rate_monitor_mobile.databinding.ActivityHistoryBinding
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HistoryActivity : BaseActivity(), HistoryAdapterListener {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private val viewModel: HistoryViewModel by viewModels()
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessions.collect { sessions ->
                    if (sessions == null) return@collect
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
        }
    }

    override fun onItemClick(item: SessionWithDevices) {
        if (actionMode != null) {
            toggleSelection(item.session.id)
        } else {
            val intent = Intent(this, ChartActivity::class.java).apply {
                putExtra("SESSION_ID", item.session.id)
            }
            startActivity(intent)
        }
    }

    override fun onItemLongClick(item: SessionWithDevices) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(actionModeCallback)
        }
        toggleSelection(item.session.id)
    }

    private fun toggleSelection(sessionId: Long) {
        adapter.toggleSelection(sessionId)
        val selectedCount = adapter.getSelectedItems().size
        if (selectedCount == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = getString(R.string.history_selected_count, selectedCount)
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
                    actionMode?.title = getString(R.string.history_selected_count, adapter.itemCount)
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
            .setTitle(R.string.common_confirm_delete_title)
            .setMessage(getString(R.string.history_delete_message, selectedIds.size))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                viewModel.deleteSessions(selectedIds)
                actionMode?.finish()
            }
            .show()
    }
}