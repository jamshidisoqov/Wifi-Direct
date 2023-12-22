package uz.uni_team.date_time_manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.uni_team.date_time_manager.adapter.WifiDirectAdapter
import uz.uni_team.date_time_manager.broadcast.WifiDirectActionBroadcast
import uz.uni_team.date_time_manager.databinding.ActivityMainBinding
import uz.uni_team.date_time_manager.manager.WifiDirectManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.coroutines.resume


@SuppressLint("RestrictedApi")
class MainActivity : ComponentActivity(), WifiP2pManager.ConnectionInfoListener {

    private lateinit var binding: ActivityMainBinding

    private val adapter: WifiDirectAdapter by lazy {
        WifiDirectAdapter()
    }

    private var permissionGranted: Boolean = false

    private val intentFilter by lazy {
        IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }


    private lateinit var channel: WifiP2pManager.Channel

    private lateinit var manager: WifiP2pManager

    private lateinit var wifiDirectManager: WifiDirectManager

    private val permissionsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )

    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        wifiDirectManager = WifiDirectManager(this)


        binding.rvDevices.adapter = adapter
        adapter.setItemClickListener { device ->
            if (permissionGranted) {
                val config = WifiP2pConfig().apply {
                    deviceAddress = device.deviceAddress
                    wps.setup = WpsInfo.PBC
                    groupOwnerIntent = 0
                }
                manager.connect(
                    channel, config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Toast.makeText(
                                this@MainActivity,
                                "Success connected:${device.deviceName}",
                                Toast.LENGTH_SHORT
                            ).show()
                            createGroup(device)
                        }


                        override fun onFailure(p0: Int) {
                            Toast.makeText(
                                this@MainActivity, "Connect failed. Retry.", Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                )
            }
        }

        binding.btnReceive.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { receive() }
        }

        binding.btnSend.setOnClickListener { sendMessage() }
        lifecycleScope.launch { permissionGranted = checkPermission() }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun createGroup(device: WifiP2pDevice) {
        val config = WifiP2pConfig()
        config.groupOwnerIntent = 0
        config.deviceAddress = device.deviceAddress
        config.wps.setup = WpsInfo.PBC
        manager.createGroup(
            channel, config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Device is ready to accept incoming connections from peers.
                }

                override fun onFailure(reason: Int) {
                    Toast.makeText(
                        this@MainActivity, "P2P group creation failed. Retry.", Toast.LENGTH_SHORT
                    ).show()
                }
            },
        )
    }

    private suspend fun checkPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        Dexter.withContext(this).withPermissions(permissionsList).withListener(
            object : MultiplePermissionsListener {
                @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
                override fun onPermissionsChecked(p0: MultiplePermissionsReport?) {
                    if (p0?.areAllPermissionsGranted() == true) {
                        val broadcast = WifiDirectActionBroadcast(wifiDirectManager)
                        registerReceiver(broadcast, intentFilter)
                        setDiscoverPeers()
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: MutableList<PermissionRequest>?, p1: PermissionToken?
                ) {
                    p1?.continuePermissionRequest()
                }
            },
        ).check()
    }

    @SuppressLint("MissingPermission")
    private fun setDiscoverPeers() {
        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Success Loaded", Toast.LENGTH_SHORT).show()
                    changeDevicesList()
                }

                override fun onFailure(p0: Int) {
                    Toast.makeText(this@MainActivity, "Fail:$p0", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun changeDevicesList() {
        val peerListListener = WifiP2pManager.PeerListListener { peerList ->
            updateDeviceList(peerList.deviceList.toList())
        }
        manager.requestPeers(channel, peerListListener)
    }

    private fun updateDeviceList(list: List<WifiP2pDevice>) {
        adapter.submitList(list)
    }

    fun wifiDirectModeEnabled(enabled: Boolean) {
        Toast.makeText(this, "Wifi direct :$enabled", Toast.LENGTH_SHORT).show()
    }

    fun updateConnection(device: WifiP2pDevice?) {
        Toast.makeText(
            this, "Update this device connection is :${device?.status}", Toast.LENGTH_SHORT
        ).show()
    }

    fun discoverStart() {
        manager.requestConnectionInfo(channel, this)
        Toast.makeText(this, "Discover peers started", Toast.LENGTH_SHORT).show()
        setDiscoverPeers()
    }

    private fun sendMessage() {
        lifecycleScope.launch(Dispatchers.IO) {
            manager.requestConnectionInfo(channel) { info ->
                val host = info.groupOwnerAddress.hostAddress

                val callable = Callable {
                    val socket = Socket()
                    socket.bind(null)
                    socket.connect(InetSocketAddress(host, 8888), 50000)
                    val outputStream = socket.getOutputStream()
                    val message = """
                {
                  "branchId": 321106637,
                  "changeAmount": 0,
                  "companyId": 320763645,
                  "companyTin": "306351564",
                  "customerContact": "",
                  "customerName": "",
                  "discountPercent": 0.0,
                  "isFiscalSend": false,
                  "isRefunded": false,
                  "isSms": false,
                  "isSynced": false,
                  "paymentBillId": "",
                  "paymentProviderId": 0,
                  "receiptDate": "Dec 21, 2023 11:03:36 AM",
                  "receiptDetails": [
                    {
                      "advance": {
                        "isEnabled": false
                      },
                      "amount": 129000.0000000000,
                      "barcode": "4780010544119",
                      "credit": {
                        "isEnabled": false
                      },
                      "discountAmount": 0,
                      "discountPercent": 0.0,
                      "isFavourite": true,
                      "label": "",
                      "marks": [],
                      "name": "Водка: Shohrud, \"SHOHRUD PARLAMENT\" крепость 37,5% стеклянная бутылка 0,7 л",
                      "ownerType": 1,
                      "packageCode": "1442555",
                      "packageName": "дона (бутилка) 0.7 литр",
                      "price": 43000.0000,
                      "productId": 321293993,
                      "productOrigins": {
                        "code": "IMPORT",
                        "id": 2,
                        "originAmount": 0,
                        "originName": "Куплено"
                      },
                      "productUnit": {
                        "code": 100001,
                        "description": "штук",
                        "id": 2,
                        "isCountable": false,
                        "name": "шт",
                        "nameRu": "шт",
                        "nameUz": "sht"
                      },
                      "quantity": 3.0,
                      "receiptDetailId": 152,
                      "uid": "1703305494021",
                      "unitId": 2,
                      "vatAmount": 0.000,
                      "vatBarcode": "02208001001277802",
                      "vatPercent": 0.00,
                      "vatRate": 0.00
                    }
                  ],
                  "receiptLatitude": 41.271358489990234,
                  "receiptLongitude": 69.2640609741211,
                  "receiptPayments": [
                    {
                      "amount": 129000.0000000000,
                      "type": "CASH"
                    }
                  ],
                  "status": "SALE",
                  "terminalModel": "P10",
                  "terminalSerialNumber": "P10A4230909000821",
                  "totalCard": 0E-10,
                  "totalCash": 129000.0000000000,
                  "totalCost": 129000.0000000000,
                  "totalDiscount": 0,
                  "totalExcise": 0,
                  "totalLoyaltyCard": 0,
                  "totalPaid": 129000.0000000000,
                  "totalVAT": 0.000,
                  "uid": "17033054940212",
                  "userId": 320763647,
                  "userName": "Nayimov Og'abek"
                }
         
            """.trimIndent()
                    outputStream.write(message.toByteArray())
                    socket.close()
                    outputStream.close()
                }
                val executor = Executors.newSingleThreadExecutor()
                val futureTask = FutureTask(callable)
                executor.execute(futureTask)
                futureTask.get()
                Log.e("TTT", "Successfully send")
            }
        }
    }

    private fun receive() {
        val callable = Callable {
            try {
                val serverSocket = ServerSocket(8888)
                val clientSocket = serverSocket.accept()
                val inputStream = clientSocket.getInputStream()
                val buffer = ByteArray(1024)
                var bytesRead: Int
                val builder = StringBuilder()
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val receivedMessage = String(buffer, 0, bytesRead)
                    builder.append(receivedMessage)
                }
                // serverSocket.close()
                Log.e("TTT", "Received")
                builder.toString()
            } catch (e: Exception) {
                e.message.toString()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val futureTask = FutureTask(callable)
        executor.execute(futureTask)
        Log.e("TTT", "Received message :${futureTask.get()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(WifiDirectActionBroadcast(wifiDirectManager))
    }

    override fun onConnectionInfoAvailable(p0: WifiP2pInfo?) {
        p0?.let {
            binding.connectionStatus.text = if (it.groupFormed && it.isGroupOwner) {
                "Host"
            } else if (it.groupFormed) {
                "Client"
            } else {
                "Not connected"
            }
        }
    }
}




