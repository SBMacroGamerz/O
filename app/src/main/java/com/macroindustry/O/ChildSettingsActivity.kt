package com.macroindustry.O

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PIN-gated settings screen (default PIN 6146, changeable). Behind the
 * gate: request Device Admin (uninstall friction + warning), change the
 * PIN, or stop sharing immediately.
 */
class ChildSettingsActivity : AppCompatActivity() {

    private val REQUEST_DEVICE_ADMIN = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_settings)

        val pinStep = findViewById<android.view.View>(R.id.pinStep)
        val settingsStep = findViewById<android.view.View>(R.id.settingsStep)
        val pinInput = findViewById<EditText>(R.id.pinInput)
        val pinError = findViewById<TextView>(R.id.pinErrorText)

        findViewById<Button>(R.id.pinSubmitButton).setOnClickListener {
            val entered = pinInput.text.toString()
            if (PinGate.check(this, entered)) {
                pinStep.visibility = android.view.View.GONE
                settingsStep.visibility = android.view.View.VISIBLE
            } else {
                pinError.visibility = android.view.View.VISIBLE
            }
        }

        findViewById<Button>(R.id.deviceAdminButton).setOnClickListener {
            requestDeviceAdmin()
        }

        findViewById<Button>(R.id.changePinButton).setOnClickListener {
            showChangePinDialog()
        }

        findViewById<Button>(R.id.stopSharingButton).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            Session.isSharing = false
            finish()
        }
    }

    private fun requestDeviceAdmin() {
        val compName = ComponentName(this, DeviceAdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Adds a confirmation step before this app can be removed."
            )
        }
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }

    private fun showChangePinDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("New 4-digit PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newPin = input.text.toString()
                if (newPin.length == 4) {
                    PinGate.setPin(this, newPin)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
