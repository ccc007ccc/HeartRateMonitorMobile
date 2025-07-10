package com.example.heart_rate_monitor_mobile

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.heart_rate_monitor_mobile.databinding.ListItemDeviceBinding
import com.juul.kable.Advertisement

class DeviceAdapter(
    private val onDeviceClick: (Advertisement) -> Unit,
    private val onFavoriteClick: (Advertisement) -> Unit,
    private val isFavorite: (String) -> Boolean
) : ListAdapter<Advertisement, DeviceAdapter.ViewHolder>(DeviceDiffCallback) {

    class ViewHolder(
        private val binding: ListItemDeviceBinding,
        private val onDeviceClick: (Advertisement) -> Unit,
        private val onFavoriteClick: (Advertisement) -> Unit,
        private val isFavorite: (String) -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentAdvertisement: Advertisement? = null
        private val context: Context = binding.root.context

        init {
            itemView.setOnClickListener {
                currentAdvertisement?.let(onDeviceClick)
            }
            binding.favoriteButton.setOnClickListener {
                currentAdvertisement?.let(onFavoriteClick)
            }
        }

        fun bind(advertisement: Advertisement) {
            currentAdvertisement = advertisement
            binding.deviceName.text = advertisement.name ?: "Unknown Device"
            binding.deviceAddress.text = advertisement.identifier

            // Update favorite icon state
            val favoriteIconRes = if (isFavorite(advertisement.identifier)) {
                R.drawable.ic_star_filled
            } else {
                R.drawable.ic_star_border
            }
            binding.favoriteButton.setImageResource(favoriteIconRes)


            val rssi = advertisement.rssi
            binding.rssiText.text = "${rssi}dBm"

            val strongColor = ContextCompat.getColor(context, R.color.primary_light)
            val mediumColor = Color.parseColor("#F59E0B") // Amber
            val weakColor = ContextCompat.getColor(context, R.color.red_error)

            when {
                rssi > -65 -> {
                    binding.signalIcon.setImageResource(R.drawable.ic_signal_cellular_alt)
                    binding.signalIcon.setColorFilter(strongColor)
                    binding.rssiText.setTextColor(strongColor)
                }
                rssi > -80 -> {
                    binding.signalIcon.setImageResource(R.drawable.ic_signal_cellular_alt_2_bar)
                    binding.signalIcon.setColorFilter(mediumColor)
                    binding.rssiText.setTextColor(mediumColor)
                }
                else -> {
                    binding.signalIcon.setImageResource(R.drawable.ic_signal_cellular_alt_1_bar)
                    binding.signalIcon.setColorFilter(weakColor)
                    binding.rssiText.setTextColor(weakColor)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDeviceClick, onFavoriteClick, isFavorite)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

object DeviceDiffCallback : DiffUtil.ItemCallback<Advertisement>() {
    override fun areItemsTheSame(oldItem: Advertisement, newItem: Advertisement): Boolean {
        return oldItem.identifier == newItem.identifier
    }

    override fun areContentsTheSame(oldItem: Advertisement, newItem: Advertisement): Boolean {
        // Also check favorite status for visual updates
        return oldItem.name == newItem.name && oldItem.rssi == newItem.rssi
    }
}