package com.example.heart_rate_monitor_mobile.ble

import android.util.Log
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import java.util.UUID
import java.util.concurrent.CancellationException

fun String.toUuid(): UUID = UUID.fromString(this)

// 修复：移除未使用的 context 参数
class BleManager {

    private val scanner = Scanner()

    fun scan(): Flow<Advertisement> = scanner.advertisements
        .catch { cause ->
            Log.e("BleManager", "扫描过程中发生错误", cause)
        }

    // 修复：移除未使用的 getPeripheral 函数

    // 修复：移除冗余的 suspend 修饰符
    fun observeHeartRate(peripheral: Peripheral): Flow<Int> {
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID
        )

        return peripheral.observe(characteristic)
            .map { data -> parseHeartRate(data) }
            .onCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    Log.w("BleManager", "心率数据流异常终止", cause)
                } else {
                    Log.d("BleManager", "心率数据流正常结束")
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