package com.example.heart_rate_monitor_mobile.ui.history

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.ActivityChartBinding
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ChartActivity : BaseActivity() {

    private lateinit var binding: ActivityChartBinding
    private val viewModel: HistoryViewModel by viewModels()
    private var sessionId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeUtils.setup(this, binding.appBar)

        sessionId = intent.getLongExtra("SESSION_ID", -1)
        if (sessionId == -1L) {
            finish()
            return
        }

        setupToolbar()
        loadChartData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chart, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_orientation -> {
                requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupChart(startTime: Long) {
        binding.historyChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setDrawGridBackground(false)
            setPinchZoom(true)
            // 跟随主题（旧版固定白底，深色模式刺眼）
            setBackgroundColor(Color.TRANSPARENT)
            val axisTextColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            xAxis.textColor = axisTextColor
            axisLeft.textColor = axisTextColor

            // 设置自定义的MarkerView
            marker = ChartMarkerView(this@ChartActivity, R.layout.layout_chart_marker, startTime)

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter = object : ValueFormatter() {
                private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                override fun getFormattedValue(value: Float): String {
                    val timeInMillis = startTime + TimeUnit.SECONDS.toMillis(value.toLong())
                    return format.format(Date(timeInMillis))
                }
            }
            xAxis.setDrawGridLines(false)

            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
        }
    }

    private fun loadChartData() {
        viewModel.loadSessionRecords(sessionId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionRecords.collect { records ->
                    if (records.isNullOrEmpty()) return@collect
                val startTime = records.first().timestamp
                // 先设置好图表，确保MarkerView能拿到startTime
                setupChart(startTime)

                val entries = ArrayList<Entry>()
                records.forEach { record ->
                    val timeDiffSeconds = (record.timestamp - startTime) / 1000f
                    entries.add(Entry(timeDiffSeconds, record.heartRate.toFloat()))
                }

                val dataSet = LineDataSet(entries, "Heart Rate")
                dataSet.color = MaterialColors.getColor(
                    binding.root, androidx.appcompat.R.attr.colorPrimary
                )
                dataSet.lineWidth = 1.5f
                dataSet.setDrawCircles(false) // 不画数据点，让触摸更高精准
                dataSet.setDrawValues(false)
                dataSet.highLightColor = Color.RED // 设置高亮线的颜色

                val lineData = LineData(dataSet)
                binding.historyChart.data = lineData
                binding.historyChart.invalidate()
                }
            }
        }
    }
}