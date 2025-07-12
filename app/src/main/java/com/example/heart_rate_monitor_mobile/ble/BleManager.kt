package com.example.heart_rate_monitor_mobile.ble

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.onCompletion

fun String.toUuid(): UUID = UUID.fromString(this)

class BleManager(private val context: Context) {

    private val scanner = Scanner()

    fun scan(): Flow<Advertisement> = scanner.advertisements
        .catch {
            Log.e("BleManager", "扫描时出错", it)
        }

    fun getPeripheral(advertisement: Advertisement, scope: CoroutineScope): Peripheral {
        return scope.peripheral(advertisement)
    }

    suspend fun observeHeartRate(peripheral: Peripheral): Flow<Int> {
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID
        )

        // **【关键修复】** 移除 .catch 操作符，让异常可以向上传播
        return peripheral.observe(characteristic)
            .map { data -> parseHeartRate(data) }
            .onCompletion { cause ->
                // onCompletion 会在Flow正常结束或因异常/取消而结束时调用
                if (cause != null && cause !is CancellationException) {
                    Log.w("BleManager", "心率监听Flow因异常而终止", cause)
                } else {
                    Log.d("BleManager", "心率监听Flow完成。")
                }
            }
    }

    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        val flag = data[0].toInt()
        val is16bit = (flag and 0x01) != 0

        return if (is16bit) {
            if (data.size >= 3) {
                (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
            } else {
                0
            }
        } else {
            if (data.size >= 2) {
                data[1].toInt() and 0xFF
            } else {
                0
            }
        }
    }
}