package com.example.heart_rate_monitor_mobile.ui.chart

import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.github.mikephil.charting.formatter.ValueFormatter

/**
 * 心率图表控制器：主页实时图与历史详情图共用的唯一图表实现。
 *
 * 设计目标（图表系统重构）：
 * - **多序列叠加**：每台设备一条曲线，自动配色 + 图例，支撑多设备对比评测；
 * - **绝对时间轴**：X 轴基于真实时间戳（内部以首个点为基准归一），
 *   重连/中断不会破坏时间基准，轴标签直接显示时钟时间；
 * - **主题化**：颜色全部解析自当前主题（深浅色/莫奈取色自动正确），样式只在此一处定义。
 */
class HeartRateChartController(private val chart: LineChart) {

    /** 一条曲线的数据（时间戳为毫秒墙钟） */
    data class SeriesData(
        val id: String,
        val label: String,
        val points: List<Point>,
    )

    data class Point(val timestampMs: Long, val bpm: Int)

    private var baseTimeMs: Long = 0L
    private val seriesIndex = linkedMapOf<String, Int>()
    private val palette: IntArray

    init {
        val axisTextColor = MaterialColors.getColor(
            chart, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        palette = intArrayOf(
            MaterialColors.getColor(chart, androidx.appcompat.R.attr.colorPrimary),
            MaterialColors.getColor(chart, com.google.android.material.R.attr.colorTertiary),
            MaterialColors.getColor(chart, androidx.appcompat.R.attr.colorError),
            MaterialColors.getColor(chart, com.google.android.material.R.attr.colorSecondary),
            0xFF2E7D32.toInt(), // 备用绿
            0xFF6D4C41.toInt(), // 备用棕
        )

        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setDrawGridBackground(false)
            setPinchZoom(true)
            setBackgroundColor(Color.TRANSPARENT)
            data = LineData()

            legend.isEnabled = false
            legend.textColor = axisTextColor

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = axisTextColor
            xAxis.setDrawGridLines(false)
            xAxis.setAvoidFirstLastClipping(true)
            xAxis.valueFormatter = object : ValueFormatter() {
                private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                override fun getFormattedValue(value: Float): String =
                    format.format(Date(baseTimeMs + (value * 1000L).toLong()))
            }

            axisLeft.textColor = axisTextColor
            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
        }
    }

    /** 整体刷新（进入页面 / 序列增减时调用） */
    fun setSeries(seriesList: List<SeriesData>) {
        ensureBaseTime(seriesList.firstOrNull { it.points.isNotEmpty() }?.points?.firstOrNull()?.timestampMs)
        val data = LineData()
        seriesIndex.clear()
        seriesList.forEachIndexed { index, series ->
            seriesIndex[series.id] = index
            val entries = series.points.map { Entry(toX(it.timestampMs), it.bpm.toFloat()) }
            data.addDataSet(styledDataSet(entries, series.label, index, seriesList.size == 1))
        }
        chart.legend.isEnabled = seriesList.size > 1
        chart.data = data
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /** 增量追加一个点（实时流），序列不存在时忽略（等下一次 setSeries 建立） */
    fun appendPoint(seriesId: String, timestampMs: Long, bpm: Int) {
        val index = seriesIndex[seriesId] ?: return
        val data = chart.data ?: return
        if (index >= data.dataSetCount) return
        ensureBaseTime(timestampMs)
        data.addEntry(Entry(toX(timestampMs), bpm.toFloat()), index)
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(VISIBLE_WINDOW_SECONDS)
        chart.moveViewToX(toX(timestampMs))
    }

    fun clear() {
        baseTimeMs = 0L
        seriesIndex.clear()
        chart.data = LineData()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /** 序列 [index] 的展示颜色（供设备行等外部 UI 对齐图例配色） */
    fun colorForIndex(index: Int): Int = palette[index % palette.size]

    private fun ensureBaseTime(timestampMs: Long?) {
        if (baseTimeMs == 0L && timestampMs != null && timestampMs > 0) {
            baseTimeMs = timestampMs
        }
    }

    private fun toX(timestampMs: Long): Float = (timestampMs - baseTimeMs) / 1000f

    private fun styledDataSet(entries: List<Entry>, label: String, index: Int, single: Boolean): LineDataSet {
        val color = colorForIndex(index)
        return LineDataSet(entries.toMutableList(), label).apply {
            mode = LineDataSet.Mode.LINEAR
            this.color = color
            lineWidth = 1.5f
            setDrawCircles(single)
            circleRadius = 2f
            setCircleColor(color)
            setDrawValues(false)
            highLightColor = color
        }
    }

    private companion object {
        const val VISIBLE_WINDOW_SECONDS = 300f
    }
}
