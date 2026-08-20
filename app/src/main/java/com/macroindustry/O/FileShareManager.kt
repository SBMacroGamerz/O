package com.macroindustry.O

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Host-side file access for the "File Sharing" service. Scoped to the
 * standard public directories (Pictures, Downloads, Documents) that are
 * accessible without SAF/scoped-storage prompts on most Android versions
 * for an app with READ_MEDIA_* / READ_EXTERNAL_STORAGE granted.
 *
 * Transfer goes over the same WebRTC data channel as control commands and
 * location — fine for typical photos/documents, but not efficient for
 * large video files given data-channel framing overhead. Flagged as a
 * known limitation rather than silently degrading on big files.
 */
class FileShareManager(private val context: Context) {

    companion object {
        private const val TAG = "FileShareManager"
        private const val MAX_FILE_BYTES = 15 * 1024 * 1024 // 15MB soft cap for data-channel transfer
    }

    private fun sharedDirs(): Map<String, File> = mapOf(
        "Photos" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    )

    /** Returns a JSON listing of the given category ("Photos"/"Downloads"/"Documents"). */
    fun listCategory(category: String): String {
        val dir = sharedDirs()[category]
        val result = JSONObject().apply {
            put("type", "file_list")
            put("category", category)
        }
        if (dir == null || !dir.exists()) {
            result.put("files", JSONArray())
            return result.toString()
        }
        val files = JSONArray()
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(100)?.forEach { f ->
            files.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("modified", f.lastModified())
            })
        }
        result.put("files", files)
        return result.toString()
    }

    /** Returns a JSON payload with base64 file content, or an error if too large/missing. */
    fun readFile(category: String, fileName: String): String {
        val dir = sharedDirs()[category]
        val file = dir?.let { File(it, fileName) }

        val result = JSONObject().apply {
            put("type", "file_data")
            put("category", category)
            put("name", fileName)
        }

        if (file == null || !file.exists() || !file.isFile) {
            result.put("error", "not_found")
            return result.toString()
        }
        if (file.length() > MAX_FILE_BYTES) {
            result.put("error", "too_large")
            return result.toString()
        }

        return try {
            val bytes = file.readBytes()
            result.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            result.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read file: ${e.message}")
            result.put("error", "read_failed")
            result.toString()
        }
    }
}
