package com.example.heart_rate_monitor_mobile.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.heart_rate_monitor_mobile.data.db.HeartRateSession
import com.example.heart_rate_monitor_mobile.databinding.ListItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onClick: (HeartRateSession) -> Unit,
    private val onDelete: (HeartRateSession) -> Unit
) : ListAdapter<HeartRateSession, HistoryAdapter.HistoryViewHolder>(HistoryDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ListItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding, onClick, onDelete)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(
        private val binding: ListItemHistoryBinding,
        private val onClick: (HeartRateSession) -> Unit,
        private val onDelete: (HeartRateSession) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun bind(session: HeartRateSession) {
            binding.deviceNameText.text = session.deviceName
            val startTime = dateFormat.format(Date(session.startTime))
            val endTime = session.endTime?.let { dateFormat.format(Date(it)).substring(11) } ?: "进行中"
            binding.dateTimeText.text = "$startTime - $endTime"

            itemView.setOnClickListener { onClick(session) }
            binding.deleteButton.setOnClickListener { onDelete(session) }
        }
    }
}

object HistoryDiffCallback : DiffUtil.ItemCallback<HeartRateSession>() {
    override fun areItemsTheSame(oldItem: HeartRateSession, newItem: HeartRateSession): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: HeartRateSession, newItem: HeartRateSession): Boolean {
        return oldItem == newItem
    }
}