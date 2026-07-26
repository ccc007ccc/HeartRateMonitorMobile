package com.example.heart_rate_monitor_mobile.ui.favorite

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.data.settings.SettingsKeys
import com.example.heart_rate_monitor_mobile.databinding.ActivityFavoriteDevicesBinding
import com.example.heart_rate_monitor_mobile.databinding.ListItemFavoriteDeviceBinding
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 收藏设备历史二级页面。
 *
 * 数据存储于 SharedPreferences 键 favorite_device_history（JSON 数组），
 * 每条记录含 id（MAC 地址）、name（设备名）、timestamp。
 * 最近收藏的排最前，最多保留 20 条。
 *
 * 删除记录时，若删除的恰好是当前收藏设备（favorite_device_id），
 * 同步清除当前收藏以避免自动连接仍指向已删除设备。
 */
class FavoriteDevicesActivity : BaseActivity() {

    private lateinit var binding: ActivityFavoriteDevicesBinding
    private val settings get() = container.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoriteDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        setupToolbar()

        // 设置流驱动列表刷新：删除（异步写 DataStore）落盘后自动重建列表，
        // 修复"setAsync 写后同步读导致删除后列表不刷新"的回归
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.flowOf { it.connection.favoriteDeviceHistoryJson }
                    .collect { refreshFavoriteDevices(it) }
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.favorite_title)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * 加载收藏历史并渲染列表。
     */
    private fun refreshFavoriteDevices(json: String) {
        val container = binding.favoriteDevicesContainer
        container.removeAllViews()

        val deviceList = mutableListOf<Pair<String, String>>() // (id, name)
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                deviceList.add(obj.getString("id") to obj.getString("name"))
            }
        } catch (e: Exception) {
            android.util.Log.w("FavoriteDevices", "解析收藏历史失败", e)
        }

        if (deviceList.isEmpty()) {
            binding.favoriteDevicesEmpty.visibility = View.VISIBLE
            return
        }
        binding.favoriteDevicesEmpty.visibility = View.GONE

        for ((id, name) in deviceList) {
            val itemBinding = ListItemFavoriteDeviceBinding.inflate(layoutInflater, container, false)
            itemBinding.favoriteDeviceName.text = name
            itemBinding.favoriteDeviceAddress.text = id
            itemBinding.favoriteDeviceDeleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.favorite_delete_title)
                    .setMessage(getString(R.string.favorite_delete_message, name))
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.common_delete) { _, _ ->
                        removeFavoriteDevice(id)
                    }
                    .show()
            }
            container.addView(itemBinding.root)
        }
    }

    /**
     * 从收藏历史中删除指定设备。
     * 若删除的恰好是当前收藏设备（favorite_device_id），同时清除当前收藏。
     */
    private fun removeFavoriteDevice(id: String) {
        lifecycleScope.launch {
            val json = settings.settings.value.connection.favoriteDeviceHistoryJson
            try {
                val arr = JSONArray(json)
                val filtered = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("id") != id) {
                        filtered.put(obj)
                    }
                }
                settings.set(SettingsKeys.FAVORITE_DEVICE_HISTORY, filtered.toString())
            } catch (e: Exception) {
                android.util.Log.w("FavoriteDevices", "删除收藏记录失败", e)
            }
            if (settings.settings.value.connection.favoriteDeviceId == id) {
                settings.remove(SettingsKeys.FAVORITE_DEVICE_ID)
            }
            // 列表刷新由设置流驱动，无需手动调用
        }
    }
}
