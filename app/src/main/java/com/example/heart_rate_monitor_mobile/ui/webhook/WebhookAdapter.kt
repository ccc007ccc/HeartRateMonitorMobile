package com.example.heart_rate_monitor_mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.heart_rate_monitor_mobile.databinding.ListItemWebhookBinding

class WebhookAdapter(
    private var webhooks: MutableList<Webhook>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<WebhookAdapter.ViewHolder>() {

    class ViewHolder(val binding: ListItemWebhookBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemWebhookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val webhook = webhooks[position]
        holder.binding.webhookName.text = webhook.name
        holder.binding.webhookUrl.text = webhook.url
        holder.binding.webhookStatus.text = if (webhook.enabled) "✓" else "✗"
        holder.binding.editButton.setOnClickListener { onEdit(position) }
        holder.binding.deleteButton.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = webhooks.size

    fun updateWebhooks(newWebhooks: List<Webhook>) {
        webhooks.clear()
        webhooks.addAll(newWebhooks)
        notifyDataSetChanged()
    }
}