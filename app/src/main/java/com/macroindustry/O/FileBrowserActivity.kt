package com.macroindustry.O

import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.macroindustry.O.databinding.ActivityFileBrowserBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Viewer-side file browser. Opens its own WebRTC connection to the paired
 * Host (data channel only — no video needed here) to request file listings
 * and downloads. Saved files land in this device's own Downloads folder.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private var webRtcClient: WebRtcClient? = null
    private var signaling: RoomSignaling? = null
    private var currentCategory = "Photos"
    private var currentFiles: List<JSONObject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = PairingStore.getPairingCode(this)
        if (code == null) {
            Toast.makeText(this, "Not paired with a device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        connect(code)

        binding.catPhotosButton.setOnClickListener { requestCategory("Photos") }
        binding.catDownloadsButton.setOnClickListener { requestCategory("Downloads") }
        binding.catDocumentsButton.setOnClickListener { requestCategory("Documents") }

        binding.fileListView.setOnItemClickListener { _, _, position, _ ->
            val file = currentFiles.getOrNull(position) ?: return@setOnItemClickListener
            requestFile(currentCategory, file.optString("name"))
        }
    }

    private fun connect(code: String) {
        signaling = RoomSignaling(code)
        val client = WebRtcClient(this, isHost = false, signaling = signaling!!)
        webRtcClient = client
        client.init()
        client.createPeerConnection()

        client.onDataChannelOpen = {
            runOnUiThread { requestCategory("Photos") }
        }
        client.onControlMessage = { message -> handleMessage(message) }
        client.onDisconnected = {
            runOnUiThread { Toast.makeText(this, "Disconnected from device", Toast.LENGTH_SHORT).show() }
        }

        signaling?.listenForOffer { offer -> client.handleOfferAndCreateAnswer(offer) }
        signaling?.listenForIceCandidates(isHost = false) { candidate -> client.addRemoteIceCandidate(candidate) }
    }

    private fun requestCategory(category: String) {
        currentCategory = category
        binding.fileBrowserTitle.text = "Shared Files — $category"
        val json = JSONObject().apply { put("type", "file_list"); put("category", category) }
        webRtcClient?.sendControlMessage(json.toString())
    }

    private fun requestFile(category: String, name: String) {
        Toast.makeText(this, "Downloading $name...", Toast.LENGTH_SHORT).show()
        val json = JSONObject().apply {
            put("type", "file_get")
            put("category", category)
            put("name", name)
        }
        webRtcClient?.sendControlMessage(json.toString())
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "file_list" -> {
                    val filesArray = json.optJSONArray("files") ?: JSONArray()
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until filesArray.length()) list.add(filesArray.getJSONObject(i))
                    currentFiles = list
                    runOnUiThread {
                        val names = list.map { it.optString("name") }
                        binding.fileListView.adapter =
                            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
                    }
                }
                "file_data" -> {
                    val error = json.optString("error", "")
                    if (error.isNotEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this, "Download failed: $error", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }
                    val name = json.optString("name")
                    val data = json.optString("data")
                    saveDownloadedFile(name, data)
                }
            }
        } catch (e: Exception) {
            // Ignore malformed/unrelated messages.
        }
    }

    private fun saveDownloadedFile(name: String, base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, name)
            outFile.writeBytes(bytes)
            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads: $name", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.close()
        signaling?.stopListening()
    }
}
