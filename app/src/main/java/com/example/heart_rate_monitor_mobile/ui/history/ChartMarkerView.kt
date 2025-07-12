package com.example.heart_rate_monitor_mobile.ui.history

import android.content.Context
import android.widget.TextView
import com.example.heart_rate_monitor_mobile.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ChartMarkerView(context: Context, layoutResource: Int, private val startTime: Long) : MarkerView(context, layoutResource) {

    private val textView: TextView = findViewById(R.id.markerText)
    private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 回调函数，每当MarkerView被重绘时调用
    override fun refreshContent(e: Entry, highlight: Highlight) {
        val timeInMillis = startTime + TimeUnit.SECONDS.toMillis(e.x.toLong())
        val timeString = format.format(Date(timeInMillis))
        val heartRate = e.y.toInt()
        textView.text = "心率: $heartRate bpm\n时间: $timeString"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        // 让标记视图显示在数据点的上方
        return MPPointF(-(width / 2f), -height.toFloat())
    }
}