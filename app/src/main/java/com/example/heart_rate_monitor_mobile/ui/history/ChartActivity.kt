package com.example.heart_rate_monitor_mobile.ui.history

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.heart_rate_monitor_mobile.R
import com.example.heart_rate_monitor_mobile.databinding.ActivityChartBinding
import com.example.heart_rate_monitor_mobile.ui.BaseActivity
import com.example.heart_rate_monitor_mobile.ui.chart.HeartRateChartController
import com.example.heart_rate_monitor_mobile.util.EdgeToEdgeUtils
import kotlinx.coroutines.launch
import java.util.Locale

/** 会话详情：全部参与设备曲线叠加 + 对比设备准度小结（共用 HeartRateChartController） */
class ChartActivity : BaseActivity() {

    private lateinit var binding: ActivityChartBinding
    private lateinit var chartController: HeartRateChartController
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
        chartController = HeartRateChartController(binding.historyChart)
        viewModel.loadSessionDetail(sessionId)
        observeDetail()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun observeDetail() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionDetail.collect { detail ->
                    if (detail.isNullOrEmpty()) return@collect
                    chartController.setSeries(
                        detail.map { series ->
                            HeartRateChartController.SeriesData(
                                id = series.deviceRowId.toString(),
                                label = series.name,
                                points = series.points.map {
                                    HeartRateChartController.Point(it.first, it.second)
                                },
                            )
                        }
                    )
                    renderAccuracySummary(detail)
                }
            }
        }
    }

    private fun renderAccuracySummary(detail: List<HistoryViewModel.DeviceSeries>) {
        val lines = detail.mapNotNull { series ->
            val accuracy = series.accuracy ?: return@mapNotNull null
            getString(
                R.string.chart_accuracy_line,
                series.name,
                String.format(Locale.US, "%.1f", accuracy.meanAbsDiff),
                accuracy.maxAbsDiff.toString(),
            )
        }
        binding.accuracySummary.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.accuracySummary.text = lines.joinToString("\n")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chart, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_orientation -> {
                requestedOrientation =
                    if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
