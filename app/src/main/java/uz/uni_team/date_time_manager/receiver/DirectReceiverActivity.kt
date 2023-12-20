package uz.uni_team.date_time_manager.receiver

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ChannelListener
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.uni_team.date_time_manager.Constants
import uz.uni_team.date_time_manager.databinding.ActivityDirectReceiverBinding
import uz.uni_team.date_time_manager.device.FileTransfer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.coroutines.resume

@SuppressLint("RestrictedApi")
class DirectReceiverActivity : ComponentActivity() {

    private lateinit var wifiP2pManager: WifiP2pManager

    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    private lateinit var viewBinding: ActivityDirectReceiverBinding

    private var job: Job? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityDirectReceiverBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        initViews()
        initDevice()
    }

    private fun initViews() {
        viewBinding.apply {

            btnCreateGroup.setOnClickListener {
                createGroup()
            }
            btnRemoveGroup.setOnClickListener {
                removeGroup()
            }
            btnStartReceive.setOnClickListener {
                startListener()
            }
        }
    }

    private fun initDevice() {
        val mWifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mWifiP2pManager == null) {
            finish()
            return
        }
        wifiP2pManager = mWifiP2pManager
        wifiP2pChannel = mWifiP2pManager.initialize(
            this,
            mainLooper,
            ChannelListener {

            },
        )
    }


    @SuppressLint("MissingPermission")
    private fun createGroup() {
        lifecycleScope.launch {
            removeGroupIfNeed()
            wifiP2pManager.createGroup(
                wifiP2pChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val log = "createGroup onSuccess"
                        showToast(log)

                    }

                    override fun onFailure(reason: Int) {
                        val log = "createGroup onFailure: $reason"
                        showToast(log)
                    }
                },
            )
        }
    }

    private fun removeGroup() {
        lifecycleScope.launch {
            removeGroupIfNeed()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun removeGroupIfNeed() {
        return suspendCancellableCoroutine { continuation ->
            wifiP2pManager.requestGroupInfo(wifiP2pChannel) { group ->
                if (group == null) {
                    continuation.resume(value = Unit)
                } else {
                    wifiP2pManager.removeGroup(
                        wifiP2pChannel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                val log = "removeGroup onSuccess"

                                continuation.resume(value = Unit)
                            }

                            override fun onFailure(reason: Int) {
                                val log = "removeGroup onFailure: $reason"

                                continuation.resume(value = Unit)
                            }
                        },
                    )
                }
            }
        }
    }

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.d("Receiver", message)
    }

    private fun startListener() {
        if (job != null) {
            return
        }
        job = lifecycleScope.launch(context = Dispatchers.IO) {

            var serverSocket: ServerSocket? = null
            var clientInputStream: InputStream? = null
            var objectInputStream: ObjectInputStream? = null
            var fileOutputStream: FileOutputStream? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.bind(InetSocketAddress(Constants.PORT))
                serverSocket.reuseAddress = true
                serverSocket.soTimeout = 30000

                val client = serverSocket.accept()
                //RECEIVING

                clientInputStream = client.getInputStream()
                objectInputStream = ObjectInputStream(clientInputStream)
                val fileTransfer = objectInputStream.readObject() as FileTransfer
                val file = generateFile(fileTransfer.fileName)


                fileOutputStream = FileOutputStream(file)
                val buffer = ByteArray(1024 * 100)
                while (true) {
                    val length = clientInputStream.read(buffer)
                    if (length > 0) {
                        fileOutputStream.write(buffer, 0, length)
                    } else {
                        break
                    }

                }
                //Success
            } catch (e: Throwable) {
                //Fail
            } finally {
                serverSocket?.close()
                clientInputStream?.close()
                objectInputStream?.close()
                fileOutputStream?.close()
            }
        }
        job?.invokeOnCompletion {
            job = null
        }
    }

    private fun generateFile(fileName: String): File? {
        var file: File? = null
        if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            val absolutePath: String? = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> this.getExternalFilesDir(null)?.absolutePath

                else -> Environment.getExternalStorageDirectory().absolutePath
            }

            val root = File(absolutePath, "P2P" + File.separator + "Log")
            var dirExists = true
            if (!root.exists()) dirExists = root.mkdirs()
            if (dirExists) file = File(root, fileName)
        }
        return file
    }

}




