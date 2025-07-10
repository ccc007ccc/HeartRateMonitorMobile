package com.example.heart_rate_monitor_mobile


import android.Manifest
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.heart_rate_monitor_mobile.databinding.ActivityMainBinding
import com.permissionx.guolindev.PermissionX
import kotlin.math.absoluteValue


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupRecyclerView()
        setupObservers()
        setupClickListeners()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { advertisement ->
            viewModel.connectToDevice(advertisement)
        }
        binding.devicesRecyclerView.apply {
            adapter = deviceAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupObservers() {
        viewModel.statusMessage.observe(this) {
            binding.statusTextView.text = it
        }

        viewModel.scanResults.observe(this) { results ->
            deviceAdapter.submitList(results)
        }

        viewModel.heartRate.observe(this) { rate ->
            if (rate > 0) {
                binding.heartRateTextView.text = "$rate"
                updateHeartbeatAnimation(rate)
            } else {
                binding.heartRateTextView.text = "--"
                updateHeartbeatAnimation(0)
            }
        }

        viewModel.appStatus.observe(this) { status ->
            binding.statusProgressBar.visibility =
                if (status == AppStatus.SCANNING || status == AppStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.statusIcon.visibility =
                if (binding.statusProgressBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.scanFab.visibility = if (status == AppStatus.DISCONNECTED) View.VISIBLE else View.GONE

            val listVisible = status == AppStatus.DISCONNECTED || status == AppStatus.SCANNING
            binding.devicesRecyclerView.visibility = if (listVisible) View.VISIBLE else View.GONE
            binding.deviceListTitle.visibility = if (listVisible) View.VISIBLE else View.GONE
            binding.disconnectButton.visibility = if (status == AppStatus.CONNECTED) View.VISIBLE else View.GONE

            when (status) {
                AppStatus.CONNECTED -> {
                    binding.heartRateCard.background =
                        ContextCompat.getDrawable(this, R.drawable.background_heart_rate_connected)
                    binding.heartIcon.text = "❤️"
                    binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_connected)
                    binding.statusIcon.setColorFilter(
                        ContextCompat.getColor(this, R.color.primary_light)
                    )
                }
                else -> {
                    binding.heartRateCard.background =
                        ContextCompat.getDrawable(this, R.drawable.background_heart_rate_disconnected)
                    binding.heartIcon.text = "💔"
                    binding.statusIcon.setImageResource(R.drawable.ic_bluetooth_disabled)
                    binding.statusIcon.setColorFilter(
                        ContextCompat.getColor(this, R.color.red_error)
                    )
                    updateHeartbeatAnimation(0)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.scanFab.setOnClickListener {
            viewModel.startScan()
        }
        binding.disconnectButton.setOnClickListener {
            viewModel.disconnectDevice()
        }
    }

    /**
     * 平滑更新心跳动画，根据 BPM 调整动画节奏
     */
    private var heartRateAnimator: ValueAnimator? = null
    private var currentBpm: Int = 0
    private var currentDuration: Long = 0L
    private var targetDuration: Long = 0L

    private val beatInterpolator = AccelerateDecelerateInterpolator()

    private fun updateHeartbeatAnimation(bpm: Int) {
        val heartIcon = binding.heartIcon

        if (bpm > 30 && viewModel.appStatus.value == AppStatus.CONNECTED) {
            targetDuration = (60000f / bpm).toLong()

            if (heartRateAnimator == null) {
                // 初次启动动画
                currentDuration = targetDuration

                heartRateAnimator = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
                    duration = currentDuration
                    interpolator = beatInterpolator
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART

                    addUpdateListener { animation ->
                        val scale = animation.animatedValue as Float
                        heartIcon.scaleX = scale
                        heartIcon.scaleY = scale
                    }

                    start()

                    // 用 Handler 平滑更新 duration，避免硬切换卡顿
                    startDurationAdjuster()
                }

            } else {
                // 更新目标 duration，稍后平滑过渡
                targetDuration = (60000f / bpm).toLong()
            }

            currentBpm = bpm

        } else {
            // 停止动画
            heartRateAnimator?.cancel()
            heartRateAnimator = null
            currentBpm = 0
            currentDuration = 0L
            targetDuration = 0L

            heartIcon.scaleX = 1f
            heartIcon.scaleY = 1f
        }
    }

    private fun startDurationAdjuster() {
        // 用一个调速器线程平滑改变 duration（约每 200ms 调整一次）
        val handler = android.os.Handler(mainLooper)

        val adjuster = object : Runnable {
            override fun run() {
                if (heartRateAnimator == null) return

                val diff = targetDuration - currentDuration
                if (diff.absoluteValue > 50) {
                    // 差异显著时，逐步调整
                    currentDuration += diff / 4  // 调整速度快慢可调
                    heartRateAnimator?.duration = currentDuration
                    handler.postDelayed(this, 200)
                } else {
                    // 已接近目标值，不再继续调整
                    currentDuration = targetDuration
                    heartRateAnimator?.duration = currentDuration
                }
            }
        }

        handler.post(adjuster)
    }



    private fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        PermissionX.init(this)
            .permissions(permissionsToRequest)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "应用需要这些权限才能发现并连接到您的心率监测设备。",
                    "好的",
                    "取消"
                )
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    "您需要手动在设置中允许这些权限。",
                    "好的",
                    "取消"
                )
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    binding.statusTextView.text = "部分权限被拒绝，应用可能无法正常工作！"
                }
            }
    }
}
