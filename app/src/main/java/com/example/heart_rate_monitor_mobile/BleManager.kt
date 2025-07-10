package com.example.heart_rate_monitor_mobile

import android.content.Context
import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.CancellationException

fun String.toUuid(): UUID = UUID.fromString(this)

class BleManager(private val context: Context) {

    private val scanner = Scanner {
//        filters = listOf(BleConstants.heartRateServiceFilter)
    }

    fun scan(): Flow<Advertisement> = scanner.advertisements
//        .filter { it.name != null }
        .catch {
            // Handle scanning errors
            println("Error while scanning: $it")
        }

    fun getPeripheral(advertisement: Advertisement, scope: CoroutineScope): Peripheral {
        return scope.peripheral(advertisement)
    }

    suspend fun observeHeartRate(peripheral: Peripheral): Flow<Int> {
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID
        )

        return peripheral.observe(characteristic)
            .map { data -> parseHeartRate(data) }
            .catch { cause ->
                if (cause is CancellationException) {
                    println("Heart rate observation cancelled.")
                } else {
                    println("Error observing heart rate: $cause")
                }
            }
    }

    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flag = data[0].toInt()
        val is16bit = (flag and 0x01) != 0
        return if (is16bit) {
            // 16-bit heart rate value
            (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
        } else {
            // 8-bit heart rate value
            data[1].toInt() and 0xFF
        }
    }
}