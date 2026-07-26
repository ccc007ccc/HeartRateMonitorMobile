package com.example.heart_rate_monitor_mobile.ui.webhook

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.Webhook
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import com.example.heart_rate_monitor_mobile.data.webhook.WebhookRepository
import com.example.heart_rate_monitor_mobile.databinding.ActivityWebhookBinding
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class WebhookActivity : BaseActivity() {

    private lateinit var binding: ActivityWebhookBinding
    private lateinit var adapter: WebhookAdapter
    private var webhooks = mutableListOf<Webhook>()

    private val repository get() = container.webhooks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebhookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        observeWebhooks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.webhook_settings)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = WebhookAdapter(
            webhooks,
            onEdit = { index -> showEditDialog(index) },
            onDelete = { index -> deleteWebhook(index) },
        )
        binding.webhooksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.webhooksRecyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.addNewWebhookButton.setOnClickListener { showEditDialog(null) }
        binding.syncButton.setOnClickListener { syncFromGithub() }
    }

    private fun observeWebhooks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.webhooks.collect { list ->
                    webhooks = list.toMutableList()
                    adapter.updateWebhooks(webhooks)
                    binding.webhookEmptyView.visibility =
                        if (webhooks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    binding.webhooksRecyclerView.visibility =
                        if (webhooks.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                }
            }
        }
    }

    private fun saveWebhooks() {
        val snapshot = webhooks.toList()
        lifecycleScope.launch { repository.save(snapshot) }
    }

    /**
     * 从 GitHub 同步官方预设：拉取 → 预览确认 → 合并（不再直接覆盖本地配置，
     * 且同步条目强制禁用，需用户逐条启用）。
     */
    private fun syncFromGithub() {
        binding.syncButton.isEnabled = false
        lifecycleScope.launch {
            val result = repository.fetchGithubPresets()
            binding.syncButton.isEnabled = true
            result.fold(
                onSuccess = { presets -> showPresetPreviewDialog(presets) },
                onFailure = { e ->
                    MaterialAlertDialogBuilder(this@WebhookActivity)
                        .setTitle(R.string.webhook_sync_failed_title)
                        .setMessage(getString(R.string.webhook_sync_failed_message, e.message))
                        .setPositiveButton(R.string.common_ok, null)
                        .show()
                },
            )
        }
    }

    private fun showPresetPreviewDialog(presets: List<Webhook>) {
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.webhook_presets_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val existingKeys = webhooks.map { it.name to it.url }.toSet()
        val newOnes = presets.filter { (it.name to it.url) !in existingKeys }
        val previewText = buildString {
            append(getString(R.string.webhook_sync_preview_header, presets.size, newOnes.size))
            append("\n\n")
            presets.forEach { preset ->
                val mark = if ((preset.name to preset.url) in existingKeys) {
                    getString(R.string.webhook_preset_exists)
                } else {
                    ""
                }
                append("• ${preset.name}$mark\n  ${preset.url}\n")
            }
            append("\n")
            append(getString(R.string.webhook_merge_note))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webhook_merge_confirm_title)
            .setMessage(previewText)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.webhook_merge) { _, _ ->
                lifecycleScope.launch {
                    repository.mergePresets(presets)
                    Toast.makeText(
                        this@WebhookActivity,
                        getString(R.string.webhook_merged_toast, newOnes.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .show()
    }

    private fun showEditDialog(index: Int?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_webhook, null)
        val webhook = if (index != null) {
            webhooks[index]
        } else {
            Webhook(getString(R.string.webhook_new_default_name), "")
        }

        val nameEditText = dialogView.findViewById<EditText>(R.id.webhookNameEditText)
        val urlEditText = dialogView.findViewById<EditText>(R.id.webhookUrlEditText)
        val enabledSwitch = dialogView.findViewById<SwitchMaterial>(R.id.webhookEnabledSwitch)
        val bodyEditText = dialogView.findViewById<EditText>(R.id.webhookBodyEditText)
        val headersEditText = dialogView.findViewById<EditText>(R.id.webhookHeadersEditText)
        val testButton = dialogView.findViewById<Button>(R.id.testWebhookButton)
        val responseTextView = dialogView.findViewById<TextView>(R.id.responseLogTextView)

        val checkHrUpdated = dialogView.findViewById<CheckBox>(R.id.checkHeartRateUpdated)
        val checkConnected = dialogView.findViewById<CheckBox>(R.id.checkConnected)
        val checkDisconnected = dialogView.findViewById<CheckBox>(R.id.checkDisconnected)

        nameEditText.setText(webhook.name)
        urlEditText.setText(webhook.url)
        enabledSwitch.isChecked = webhook.enabled
        bodyEditText.setText(webhook.body)
        headersEditText.setText(webhook.headers)

        checkHrUpdated.isChecked = webhook.triggers.contains(WebhookTrigger.HEART_RATE_UPDATED)
        checkConnected.isChecked = webhook.triggers.contains(WebhookTrigger.CONNECTED)
        checkDisconnected.isChecked = webhook.triggers.contains(WebhookTrigger.DISCONNECTED)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setTitle(if (index == null) R.string.webhook_dialog_add else R.string.webhook_dialog_edit)
            .setPositiveButton(R.string.common_save, null)
            .setNegativeButton(R.string.common_cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val url = urlEditText.text.toString().trim()
                // 协议校验：允许 http/https（生态大量依赖明文 http 目标）
                if (!WebhookRepository.isUrlAllowed(url)) {
                    urlEditText.error = getString(R.string.webhook_url_scheme_invalid)
                    return@setOnClickListener
                }

                val selectedTriggers = mutableListOf<WebhookTrigger>()
                if (checkHrUpdated.isChecked) selectedTriggers.add(WebhookTrigger.HEART_RATE_UPDATED)
                if (checkConnected.isChecked) selectedTriggers.add(WebhookTrigger.CONNECTED)
                if (checkDisconnected.isChecked) selectedTriggers.add(WebhookTrigger.DISCONNECTED)
                // 如果一个都没选，则默认选择心率更新，避免出现没有触发器的webhook
                if (selectedTriggers.isEmpty()) {
                    selectedTriggers.add(WebhookTrigger.HEART_RATE_UPDATED)
                }

                val newWebhook = Webhook(
                    name = nameEditText.text.toString(),
                    url = url,
                    enabled = enabledSwitch.isChecked,
                    body = bodyEditText.text.toString(),
                    headers = headersEditText.text.toString(),
                    triggers = selectedTriggers,
                )
                if (index != null) {
                    webhooks[index] = newWebhook
                } else {
                    webhooks.add(newWebhook)
                }
                saveWebhooks()
                adapter.updateWebhooks(webhooks)
                dialog.dismiss()
            }

            testButton.setOnClickListener {
                responseTextView.text = getString(R.string.webhook_testing)
                val testWebhook = Webhook(
                    name = nameEditText.text.toString(),
                    url = urlEditText.text.toString().trim(),
                    enabled = enabledSwitch.isChecked,
                    body = bodyEditText.text.toString(),
                    headers = headersEditText.text.toString(),
                )
                repository.testWebhook(testWebhook) { result ->
                    responseTextView.text = result
                }
            }
        }
        dialog.show()
    }

    private fun deleteWebhook(index: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.common_confirm_delete_title)
            .setMessage(getString(R.string.webhook_delete_message, webhooks[index].name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                webhooks.removeAt(index)
                saveWebhooks()
                adapter.updateWebhooks(webhooks)
            }
            .show()
    }
}
