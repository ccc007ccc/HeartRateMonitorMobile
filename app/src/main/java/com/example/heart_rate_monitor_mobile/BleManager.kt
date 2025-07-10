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
import kotlinx.coroutines.flow.onCompletion

fun String.toUuid(): UUID = UUID.fromString(this)

class BleManager(private val context: Context) {

    // Kable 的扫描器实例
    private val scanner = Scanner {
        // 你可以在这里添加全局过滤器，比如只扫描包含心率服务的设备
        // filters = listOf(BleConstants.heartRateServiceFilter)
    }

    /**
     * 开始扫描蓝牙设备，返回一个 Advertisement 的 Flow。
     */
    fun scan(): Flow<Advertisement> = scanner.advertisements
        // 可以过滤掉没有名字的设备，如果需要的话
        // .filter { it.name != null }
        .catch {
            // 处理扫描过程中可能出现的异常
            println("扫描时出错: $it")
        }

    /**
     * 根据扫描到的 Advertisement 创建一个 Peripheral 实例。
     * @param advertisement 扫描到的设备广告。
     * @param scope 用于管理 Peripheral 生命周期的协程作用域。
     */
    fun getPeripheral(advertisement: Advertisement, scope: CoroutineScope): Peripheral {
        return scope.peripheral(advertisement)
    }

    /**
     * 监听并解析心率数据。
     * @param peripheral 要监听的设备。
     * @return 返回一个包含心率值的 Flow<Int>。
     */
    suspend fun observeHeartRate(peripheral: Peripheral): Flow<Int> {
        // 定义心率测量特征
        val characteristic = characteristicOf(
            service = BleConstants.HEART_RATE_SERVICE_UUID,
            characteristic = BleConstants.HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID
        )

        // 开始监听特征变化
        return peripheral.observe(characteristic)
            .map { data -> parseHeartRate(data) } // 将接收到的 ByteArray 解析为心率值
            .catch { cause ->
                // 处理监听过程中的异常
                if (cause is CancellationException) {
                    println("心率监听被取消。")
                } else {
                    println("监听心率时出错: $cause")
                }
            }
            .onCompletion {
                // 当Flow完成（无论是正常结束还是被取消）时，确保断开连接
                println("心率监听 Flow 完成，断开设备连接。")
                try {
                    peripheral.disconnect()
                } catch (e: Exception) {
                    println("在 onCompletion 中断开连接失败: ${e.message}")
                }
            }
    }

    /**
     * 解析蓝牙设备发送的原始心率数据。
     * 根据心率测量特征的规范，第一个字节是标志位，
     * 其中最低位（bit 0）决定了心率值是 8 位还是 16 位。
     * @param data 从蓝牙特征接收到的原始字节数组。
     * @return 解析后的心率值（BPM）。
     */
    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        val flag = data[0].toInt()
        val is16bit = (flag and 0x01) != 0 // 检查最低位

        return if (is16bit) {
            // 如果是 16-bit 值，心率值在第二个和第三个字节（小端序）
            if (data.size >= 3) {
                (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
            } else {
                0
            }
        } else {
            // 如果是 8-bit 值，心率值在第二个字节
            if (data.size >= 2) {
                data[1].toInt() and 0xFF
            } else {
                0
            }
        }
    }
}