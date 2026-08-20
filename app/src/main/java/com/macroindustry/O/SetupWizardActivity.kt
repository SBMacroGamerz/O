package com.macroindustry.O

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.macroindustry.O.databinding.ActivitySetupWizardBinding

/**
 * Guided first-run setup, one permission/step at a time — mirrors how
 * Qustodio/Family Link onboard, instead of scattering permission buttons
 * around the app for the person to discover on their own.
 *
 * Steps differ by role:
 *  HOST:   notifications -> location -> camera -> microphone ->
 *          accessibility -> battery exemption -> device admin ->
 *          generate + show pairing code -> done
 *  VIEWER: notifications -> enter pairing code -> done
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private var role: Session.Role = Session.Role.NONE
    private var stepIndex = 0
    private lateinit var steps: List<WizardStep>

    private data class WizardStep(
        val icon: String,
        val title: String,
        val description: String,
        val actionLabel: String,
        val skippable: Boolean,
        val isPairingCodeStep: Boolean = false,
        val onAction: () -> Unit
    )

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            advance()
        }
    private val multiPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            advance()
        }
    private val settingsReturn =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            advance()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        role = intent.getStringExtra("role")?.let {
            runCatching { Session.Role.valueOf(it) }.getOrDefault(Session.Role.NONE)
        } ?: Session.Role.NONE

        steps = if (role == Session.Role.HOST) hostSteps() else viewerSteps()
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            finishSetup()
            return
        }
        stepIndex = index
        val step = steps[index]

        binding.stepProgressText.text = "Step ${index + 1} of ${steps.size}"
        binding.stepIcon.text = step.icon
        binding.stepTitle.text = step.title
        binding.stepDescription.text = step.description
        binding.stepActionButton.text = step.actionLabel
        binding.stepSkipButton.visibility = if (step.skippable) View.VISIBLE else View.GONE
        binding.roomCodeDisplay.visibility = View.GONE
        binding.roomCodeEntry.visibility = View.GONE

        if (step.isPairingCodeStep) {
            renderPairingCodeWidgets()
        }

        binding.stepActionButton.setOnClickListener { step.onAction() }
        binding.stepSkipButton.setOnClickListener { advance() }
    }

    private fun renderPairingCodeWidgets() {
        if (role == Session.Role.HOST) {
            val existing = PairingStore.getPairingCode(this)
            val code = existing ?: Session.generateRoomCode().also {
                PairingStore.savePairingCode(this, it)
            }
            binding.roomCodeDisplay.text = code
            binding.roomCodeDisplay.visibility = View.VISIBLE
        } else {
            binding.roomCodeEntry.visibility = View.VISIBLE
        }
    }

    private fun advance() {
        showStep(stepIndex + 1)
    }

    private fun finishSetup() {
        PairingStore.markSetupComplete(this)
        PairingStore.saveRole(this, role)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- HOST STEP SEQUENCE ----------

    private fun hostSteps(): List<WizardStep> = listOf(
        WizardStep(
            icon = "🔔", title = "Notifications",
            description = "O shows a status notification while sharing is active, so this device always knows it's on.",
            actionLabel = "Allow Notifications", skippable = true,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else advance()
            }
        ),
        WizardStep(
            icon = "📍", title = "Location",
            description = "Lets the parent device see where this phone is.",
            actionLabel = "Allow Location", skippable = true,
            onAction = {
                multiPermission.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        ),
        WizardStep(
            icon = "📷", title = "Camera",
            description = "Allows the parent device to view this phone's camera when approved.",
            actionLabel = "Allow Camera", skippable = true,
            onAction = {
                multiPermission.launch(arrayOf(android.Manifest.permission.CAMERA))
            }
        ),
        WizardStep(
            icon = "🎙️", title = "Microphone",
            description = "Allows audio sharing with the parent device when approved.",
            actionLabel = "Allow Microphone", skippable = true,
            onAction = {
                multiPermission.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            }
        ),
        WizardStep(
            icon = "♿", title = "Accessibility Access",
            description = "Required so the parent device can tap and scroll on this phone during remote assistance.",
            actionLabel = "Open Accessibility Settings", skippable = true,
            onAction = {
                settingsReturn.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ),
        WizardStep(
            icon = "🔋", title = "Battery Settings",
            description = "Prevents the system from stopping O in the background. On MIUI, also enable \"Autostart\" for O in the Security app — that step can't be triggered automatically.",
            actionLabel = "Open Battery Settings", skippable = true,
            onAction = { requestBatteryExemption() }
        ),
        WizardStep(
            icon = "🔒", title = "Uninstall Protection",
            description = "Adds a confirmation step before O can be removed from this device.",
            actionLabel = "Enable Protection", skippable = true,
            onAction = { requestDeviceAdmin() }
        ),
        WizardStep(
            icon = "🔗", title = "Pairing Code",
            description = "Enter this code once on the parent device. It won't need to be entered again.",
            actionLabel = "Done", skippable = false,
            isPairingCodeStep = true,
            onAction = { advance() }
        )
    )

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            advance()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        settingsReturn.launch(intent)
    }

    private fun requestDeviceAdmin() {
        val compName = ComponentName(this, DeviceAdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
            advance()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Adds a confirmation step before O can be removed."
            )
        }
        settingsReturn.launch(intent)
    }

    // ---------- VIEWER STEP SEQUENCE ----------

    private fun viewerSteps(): List<WizardStep> = listOf(
        WizardStep(
            icon = "🔔", title = "Notifications",
            description = "O notifies you about connection status and alerts.",
            actionLabel = "Allow Notifications", skippable = true,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else advance()
            }
        ),
        WizardStep(
            icon = "🔗", title = "Pairing Code",
            description = "Enter the 6-digit code shown on the family member's device.",
            actionLabel = "Pair Device", skippable = false,
            isPairingCodeStep = true,
            onAction = { attemptPairing() }
        )
    )

    private fun attemptPairing() {
        val code = binding.roomCodeEntry.text.toString()
        if (!Session.isValidCode(code)) {
            binding.roomCodeEntry.error = "Enter a valid 6-digit code"
            return
        }
        PairingStore.savePairingCode(this, code)
        advance()
    }
}
