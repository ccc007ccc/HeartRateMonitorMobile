package com.example.heart_rate_monitor_mobile

import com.juul.kable.Filter
import com.juul.kable.peripheral


object BleConstants {
    const val HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

    // Optional: Filter for devices that advertise the Heart Rate Service
    val heartRateServiceFilter = Filter.Service(HEART_RATE_SERVICE_UUID.toUuid())
}