package uz.uni_team.date_time_manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.custom.mdm.CustomAPI
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uz.uni_team.date_time_manager.adapter.WifiDirectAdapter
import uz.uni_team.date_time_manager.broadcast.WifiDirectActionBroadcast
import uz.uni_team.date_time_manager.databinding.ActivityMainBinding
import uz.uni_team.date_time_manager.manager.WifiDirectManager
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask


@SuppressLint("RestrictedApi")
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val adapter: WifiDirectAdapter by lazy {
        WifiDirectAdapter()
    }

    private var serverPort: Int = 8888

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

    private lateinit var connectedDevice: WifiP2pDevice

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
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
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
                        // transferData()
                        connectedDevice = device
                    }

                    override fun onFailure(p0: Int) {
                        Toast.makeText(
                            this@MainActivity, "Connect failed. Retry.", Toast.LENGTH_SHORT
                        ).show()
                    }

                },
            )
        }

        binding.btnReceive.setOnClickListener {
            ReceiveMessageTask().execute()
        }

        binding.btnSend.setOnClickListener {
           /* manager.requestConnectionInfo(channel) { info ->
                val host = info.groupOwnerAddress.hostAddress!!
                SendMessageTask()
                    .execute(host, "Hello")
                // ReceiveMessageTask().execute()
            }*/
            sendMessage()
        }
        CustomAPI.release()
        checkPermission()
    }

    private fun checkPermission() {
        Dexter.withContext(this).withPermissions(permissionsList).withListener(
            object : MultiplePermissionsListener {
                @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
                override fun onPermissionsChecked(p0: MultiplePermissionsReport?) {
                    if (p0?.areAllPermissionsGranted() == true) {
                        val broadcast = WifiDirectActionBroadcast(wifiDirectManager)
                        registerReceiver(broadcast, intentFilter)
                        setDiscoverPeers()
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
        Toast.makeText(this, "Discover peers started", Toast.LENGTH_SHORT).show()
        setDiscoverPeers()
    }

    private var socket: Socket? = null

    inner class ReceiveMessageTask : AsyncTask<Void?, String?, Void?>() {

        override fun onProgressUpdate(vararg values: String?) {
            super.onProgressUpdate(*values)
            Toast.makeText(this@MainActivity, "${values[0]}", Toast.LENGTH_SHORT).show()
        }

        override fun doInBackground(vararg p0: Void?): Void? {
            try {
                // Set up a server socket on a separate thread
                val serverSocket = ServerSocket(8888)
                val clientSocket = serverSocket.accept()

                // Get the input stream from the client socket
                val inputStream = clientSocket.getInputStream()

                // Read data from the input stream
                val buffer = ByteArray(1024)
                var bytesRead: Int
                val builder = StringBuilder()
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val receivedMessage = String(buffer, 0, bytesRead)
                    builder.append(receivedMessage)
                }

                // Toast.makeText(this@MainActivity, "Received", Toast.LENGTH_SHORT).show()

                /*val f = File(
                    Environment.getExternalStorageDirectory().absolutePath +
                            "/${this@MainActivity.packageName}/p2p${System.currentTimeMillis()}.txt"
                )*/

                /* val dirs = File(f.parent)

                 dirs.takeIf { !it.exists() }?.apply {
                     mkdirs()
                 }
                 f.createNewFile()
                 val writer = FileWriter(f, true)*//* writer.append(builder.toString())
                writer.flush()
                writer.close()*/
                Log.e("Received", "$builder")
                // Close the server socket
                serverSocket.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return null
        }
    }


    private fun sendMessage() {
        lifecycleScope.launch(Dispatchers.IO) {
            manager.requestConnectionInfo(channel) { info ->
                val host = info.groupOwnerAddress.hostAddress
                val callable = Callable {
                    val socket = Socket()
                    socket.bind(null)
                    socket.connect(InetSocketAddress(host, 8888), 5000)
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
                    Log.e("Send","Success send")
                }
                val executor = Executors.newSingleThreadExecutor()
                val futureTask = FutureTask(callable)
                executor.execute(futureTask)

            }
        }
    }

    @Deprecated("Deprecated in Java")
    private class SendMessageTask : AsyncTask<String?, Void?, Void?>() {
        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg p0: String?): Void? {
            val hostAddress = p0[0]
            val socket = Socket()
            try {
                // Connect to the group owner (server) using the host address and a predefined port
                socket.bind(null)
                socket.connect(InetSocketAddress(hostAddress, 8888), 50000)

                // Get the output stream from the socket
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

                // Write the message to the output stream
                outputStream.write(message?.toByteArray())

                // Close the socket and output stream
                socket.close()
                outputStream.close()
            } catch (e: IOException) {
                e.printStackTrace();
                Log.e("SendMessageTask", "Error connecting to server", e);
            }
            return null
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(WifiDirectActionBroadcast(wifiDirectManager))
    }
}




