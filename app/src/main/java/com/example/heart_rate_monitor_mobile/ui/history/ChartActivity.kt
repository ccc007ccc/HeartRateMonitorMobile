package com.example.heart_rate_monitor_mobile.ui.history

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.data.db.AppDatabase
import com.example.heart_rate_monitor_mobile.databinding.ActivityChartBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChartBinding
    private lateinit var db: AppDatabase
    private var sessionId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getLongExtra("SESSION_ID", -1)
        if (sessionId == -1L) {
            finish()
            return
        }

        db = AppDatabase.getDatabase(this)

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
            setBackgroundColor(Color.WHITE)

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
        lifecycleScope.launch {
            val records = db.heartRateDao().getRecordsForSession(sessionId)
            if (records.isNotEmpty()) {
                val startTime = records.first().timestamp
                // 先设置好图表，确保MarkerView能拿到startTime
                setupChart(startTime)

                val entries = ArrayList<Entry>()
                records.forEach { record ->
                    val timeDiffSeconds = (record.timestamp - startTime) / 1000f
                    entries.add(Entry(timeDiffSeconds, record.heartRate.toFloat()))
                }

                val dataSet = LineDataSet(entries, "Heart Rate")
                dataSet.color = ContextCompat.getColor(this@ChartActivity, R.color.primary_light)
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