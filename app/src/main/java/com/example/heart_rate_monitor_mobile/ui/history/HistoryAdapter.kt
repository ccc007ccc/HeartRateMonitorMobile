package com.example.heart_rate_monitor_mobile.ui.history

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.db.SessionWithDevices
import com.example.heart_rate_monitor_mobile.databinding.ListItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val listener: HistoryAdapterListener
) : ListAdapter<SessionWithDevices, HistoryAdapter.HistoryViewHolder>(HistoryDiffCallback) {

    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<Long>()

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun getSelectedItems(): Set<Long> = selectedItems

    fun toggleSelection(sessionId: Long) {
        if (selectedItems.contains(sessionId)) {
            selectedItems.remove(sessionId)
        } else {
            selectedItems.add(sessionId)
        }
        notifyItemChanged(currentList.indexOfFirst { it.session.id == sessionId })
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(currentList.map { it.session.id })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ListItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding, listener)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position), isMultiSelectMode, selectedItems.contains(getItem(position).session.id))
    }

    class HistoryViewHolder(
        private val binding: ListItemHistoryBinding,
        private val listener: HistoryAdapterListener
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun bind(item: SessionWithDevices, isMultiSelectMode: Boolean, isSelected: Boolean) {
            val session = item.session
            val primaryName = item.primaryDevice?.deviceName
                ?: itemView.context.getString(R.string.history_unknown_device)
            binding.deviceNameText.text = if (item.comparisonCount > 0) {
                itemView.context.getString(R.string.history_session_summary, primaryName, item.comparisonCount)
            } else {
                primaryName
            }
            val startTime = dateFormat.format(Date(session.startTime))
            val endTime = session.endTime?.let { dateFormat.format(Date(it)).substring(11) }
                ?: itemView.context.getString(R.string.history_in_progress)
            binding.dateTimeText.text = "$startTime - $endTime"

            binding.checkbox.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            binding.checkbox.isChecked = isSelected

            if (isSelected) {
                binding.container.setBackgroundColor(itemView.context.getColor(R.color.primary_container_light))
            } else {
                binding.container.setBackgroundColor(Color.TRANSPARENT)
            }

            itemView.setOnClickListener { listener.onItemClick(item) }
            itemView.setOnLongClickListener { listener.onItemLongClick(item); true }
        }
    }
}

interface HistoryAdapterListener {
    fun onItemClick(item: SessionWithDevices)
    fun onItemLongClick(item: SessionWithDevices)
}

object HistoryDiffCallback : DiffUtil.ItemCallback<SessionWithDevices>() {
    override fun areItemsTheSame(oldItem: SessionWithDevices, newItem: SessionWithDevices): Boolean {
        return oldItem.session.id == newItem.session.id
    }

    override fun areContentsTheSame(oldItem: SessionWithDevices, newItem: SessionWithDevices): Boolean {
        return oldItem == newItem
    }
}