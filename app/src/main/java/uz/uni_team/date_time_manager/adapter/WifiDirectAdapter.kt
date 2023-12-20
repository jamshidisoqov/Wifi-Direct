package uz.uni_team.date_time_manager.adapter

import android.net.wifi.p2p.WifiP2pDevice
import android.service.controls.DeviceTypes
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import uz.uni_team.date_time_manager.R
import uz.uni_team.date_time_manager.databinding.ListItemDevicesBinding

val wifiDirectAdapterDiffUtil = object : DiffUtil.ItemCallback<WifiP2pDevice>() {
    override fun areItemsTheSame(oldItem: WifiP2pDevice, newItem: WifiP2pDevice): Boolean {
        return oldItem.deviceName == newItem.deviceName
    }

    override fun areContentsTheSame(oldItem: WifiP2pDevice, newItem: WifiP2pDevice): Boolean {
        return oldItem.deviceName == newItem.deviceName && oldItem.deviceAddress == newItem.deviceAddress
    }

}

class WifiDirectAdapter : ListAdapter<WifiP2pDevice, WifiDirectAdapter.ViewHolder>(
    wifiDirectAdapterDiffUtil
) {

    private var itemClickListener: ((WifiP2pDevice) -> Unit)? = null

    fun setItemClickListener(block: (WifiP2pDevice) -> Unit) {
        itemClickListener = block
    }

    inner class ViewHolder(private val viewBinding: ListItemDevicesBinding) :
        RecyclerView.ViewHolder(viewBinding.root) {
        fun onBind(data: WifiP2pDevice) {
            viewBinding.tvDeviceName.text = data.deviceName
            println("Primary device type :${data.primaryDeviceType}")
            println("device address :${data.deviceAddress}")
            println("Secondary device address :${data.secondaryDeviceType}")
        }

        init {
            viewBinding.root.setOnClickListener {
                itemClickListener?.invoke(getItem(absoluteAdapterPosition))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ListItemDevicesBinding.bind(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_devices, parent, false)
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        return holder.onBind(getItem(position))
    }
}