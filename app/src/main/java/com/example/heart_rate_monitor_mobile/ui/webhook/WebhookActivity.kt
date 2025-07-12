package com.example.heart_rate_monitor_mobile.ui.webhook

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.Webhook
import com.example.heart_rate_monitor_mobile.data.WebhookTrigger
import com.example.heart_rate_monitor_mobile.databinding.ActivityWebhookBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class WebhookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebhookBinding
    private lateinit var webhookManager: WebhookManager
    private lateinit var adapter: WebhookAdapter
    private var webhooks = mutableListOf<Webhook>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebhookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webhookManager = WebhookManager(this)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()

        loadWebhooks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Webhook 设置"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = WebhookAdapter(webhooks,
            onEdit = { index -> showEditDialog(index) },
            onDelete = { index -> deleteWebhook(index) }
        )
        binding.webhooksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.webhooksRecyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.addNewWebhookButton.setOnClickListener { showEditDialog(null) }
        binding.syncButton.setOnClickListener { syncFromGithub() }
    }

    private fun loadWebhooks() {
        webhooks = webhookManager.getWebhooks()
        adapter.updateWebhooks(webhooks)
    }

    private fun syncFromGithub() {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认同步")
            .setMessage("这将从GitHub下载官方预设，并覆盖你本地的 `config_webhook.json` 文件。\n\n你所有自定义的Webhook都将丢失。确定要继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                webhookManager.syncFromGithub { success, message ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("同步结果")
                        .setMessage(message)
                        .setPositiveButton("好的", null)
                        .show()
                    if (success) {
                        loadWebhooks()
                    }
                }
            }
            .show()
    }


    private fun showEditDialog(index: Int?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_webhook, null)
        val webhook = if (index != null) webhooks[index] else Webhook("新的Webhook", "")

        val nameEditText = dialogView.findViewById<EditText>(R.id.webhookNameEditText)
        val urlEditText = dialogView.findViewById<EditText>(R.id.webhookUrlEditText)
        val enabledSwitch = dialogView.findViewById<SwitchMaterial>(R.id.webhookEnabledSwitch)
        val bodyEditText = dialogView.findViewById<EditText>(R.id.webhookBodyEditText)
        val headersEditText = dialogView.findViewById<EditText>(R.id.webhookHeadersEditText)
        val testButton = dialogView.findViewById<Button>(R.id.testWebhookButton)
        val responseTextView = dialogView.findViewById<TextView>(R.id.responseLogTextView)

        // **【修复】** 更新ID
        val checkHrUpdated = dialogView.findViewById<CheckBox>(R.id.checkHeartRateUpdated)
        val checkConnected = dialogView.findViewById<CheckBox>(R.id.checkConnected)
        val checkDisconnected = dialogView.findViewById<CheckBox>(R.id.checkDisconnected)

        nameEditText.setText(webhook.name)
        urlEditText.setText(webhook.url)
        enabledSwitch.isChecked = webhook.enabled
        bodyEditText.setText(webhook.body)
        headersEditText.setText(webhook.headers)

        // **【修复】** 更新设置CheckBox状态的逻辑
        checkHrUpdated.isChecked = webhook.triggers.contains(WebhookTrigger.HEART_RATE_UPDATED)
        checkConnected.isChecked = webhook.triggers.contains(WebhookTrigger.CONNECTED)
        checkDisconnected.isChecked = webhook.triggers.contains(WebhookTrigger.DISCONNECTED)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setTitle(if (index == null) "新增 Webhook" else "编辑 Webhook")
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                // **【修复】** 更新从CheckBox收集triggers的逻辑
                val selectedTriggers = mutableListOf<WebhookTrigger>()
                if (checkHrUpdated.isChecked) selectedTriggers.add(WebhookTrigger.HEART_RATE_UPDATED)
                if (checkConnected.isChecked) selectedTriggers.add(WebhookTrigger.CONNECTED)
                if (checkDisconnected.isChecked) selectedTriggers.add(WebhookTrigger.DISCONNECTED)

                // 如果一个都没选，则默认选择心率更新，避免出现没有触发器的webhook
                if (selectedTriggers.isEmpty()){
                    selectedTriggers.add(WebhookTrigger.HEART_RATE_UPDATED)
                }

                val newWebhook = Webhook(
                    name = nameEditText.text.toString(),
                    url = urlEditText.text.toString(),
                    enabled = enabledSwitch.isChecked,
                    body = bodyEditText.text.toString(),
                    headers = headersEditText.text.toString(),
                    triggers = selectedTriggers
                )
                if (index != null) {
                    webhooks[index] = newWebhook
                } else {
                    webhooks.add(newWebhook)
                }
                webhookManager.saveWebhooks(webhooks)
                adapter.updateWebhooks(webhooks)
                dialog.dismiss()
            }

            testButton.setOnClickListener {
                responseTextView.text = "正在测试..."
                val testWebhook = Webhook(
                    name = nameEditText.text.toString(),
                    url = urlEditText.text.toString(),
                    enabled = enabledSwitch.isChecked,
                    body = bodyEditText.text.toString(),
                    headers = headersEditText.text.toString()
                )
                webhookManager.testWebhook(testWebhook) { result ->
                    responseTextView.text = result
                }
            }
        }
        dialog.show()
    }

    private fun deleteWebhook(index: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 '${webhooks[index].name}' 吗？此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                webhooks.removeAt(index)
                webhookManager.saveWebhooks(webhooks)
                adapter.updateWebhooks(webhooks)
            }
            .show()
    }
}