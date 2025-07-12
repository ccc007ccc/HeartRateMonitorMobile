package com.example.heart_rate_monitor_mobile.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.databinding.ActivityHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var db: AppDatabase

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
        adapter = HistoryAdapter(
            onClick = { session ->
                val intent = Intent(this, ChartActivity::class.java).apply {
                    putExtra("SESSION_ID", session.id)
                }
                startActivity(intent)
            },
            onDelete = { session ->
                showDeleteConfirmationDialog(session.id)
            }
        )
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter
    }

    private fun showDeleteConfirmationDialog(sessionId: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除这条历史记录吗？此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch {
                    db.heartRateDao().deleteSession(sessionId)
                }
            }
            .show()
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
}