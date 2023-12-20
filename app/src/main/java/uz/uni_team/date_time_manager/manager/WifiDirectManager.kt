package uz.uni_team.date_time_manager.manager

import android.net.wifi.p2p.WifiP2pDevice
import uz.uni_team.date_time_manager.MainActivity

class WifiDirectManager(private val activity: MainActivity) {

    fun changeDeviceList() {
        activity.changeDevicesList()
    }

    fun wifiDirectModeEnabled(isEnabled: Boolean) {
        activity.wifiDirectModeEnabled(isEnabled)
    }

    fun updateConnectionP2p(device: WifiP2pDevice?) {
        activity.updateConnection(device)
    }

    fun discoverStart() {
        activity.discoverStart()
    }
}