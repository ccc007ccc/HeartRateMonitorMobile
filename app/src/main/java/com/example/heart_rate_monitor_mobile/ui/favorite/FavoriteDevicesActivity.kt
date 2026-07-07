package com.example.heart_rate_monitor_mobile.ui.favorite

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.databinding.ActivityFavoriteDevicesBinding
import com.example.heart_rate_monitor_mobile.databinding.ListItemFavoriteDeviceBinding
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val sharedPreferences by lazy {
        getSharedPreferences("app_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoriteDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        setupToolbar()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回或删除后刷新
        refreshFavoriteDevices()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "收藏设备"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * 加载收藏历史并渲染列表。
     */
    private fun refreshFavoriteDevices() {
        val container = binding.favoriteDevicesContainer
        container.removeAllViews()

        val json = sharedPreferences.getString("favorite_device_history", null) ?: "[]"
        val deviceList = mutableListOf<Pair<String, String>>() // (id, name)
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                deviceList.add(obj.getString("id") to obj.getString("name"))
            }
        } catch (_: Exception) {
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
                    .setTitle("删除收藏设备")
                    .setMessage("确定要删除「$name」的收藏记录吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
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
        val json = sharedPreferences.getString("favorite_device_history", null) ?: "[]"
        try {
            val arr = JSONArray(json)
            val filtered = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") != id) {
                    filtered.put(obj)
                }
            }
            sharedPreferences.edit().putString("favorite_device_history", filtered.toString()).apply()
        } catch (_: Exception) {
        }
        if (sharedPreferences.getString("favorite_device_id", null) == id) {
            sharedPreferences.edit().putString("favorite_device_id", null).apply()
        }
        refreshFavoriteDevices()
    }
}
