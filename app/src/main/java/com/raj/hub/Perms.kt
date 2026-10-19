package com.raj.hub

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One permission row on the Permission Center screen.
 *
 *  - [runtime]  : normal Android pop-up permissions (empty on Android versions that do not need them)
 *  - [special]  : custom "is it allowed?" check, for permissions that live in Settings screens
 *  - [settings] : Settings screens to try (in order) when a pop-up is not possible
 */
class PermItem(
    val id: String,
    val title: String,
    val desc: String,
    private val runtime: () -> Array<String> = { emptyArray() },
    private val special: ((Context) -> Boolean)? = null,
    val settings: ((Context) -> List<Intent>)? = null
) {
    fun runtimeList(): Array<String> = runtime()

    fun granted(c: Context): Boolean {
        val sp = special
        if (sp != null) return sp(c)
        return runtime().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
    }
}

object Perms {

    private fun pkgUri(c: Context): Uri = Uri.parse("package:${c.packageName}")

    fun appDetails(c: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    private fun a11yEnabled(c: Context): Boolean {
        val me = ComponentName(c, ClusterAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ==== ANCHOR: PERMISSION LIST (add or remove a permission row here) ====
    val all: List<PermItem> = listOf(

        PermItem(
            id = "notif",
            title = "Notifications",
            desc = "Show alerts and reminders from MockPad.",
            runtime = {
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            },
            special = { NotificationManagerCompat.from(it).areNotificationsEnabled() },
            settings = { c ->
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                )
            }
        ),

        PermItem(
            id = "media",
            title = "Photos, videos & audio",
            desc = "Pick pictures, videos and music from your gallery.",
            runtime = {
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    )
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        ),

        PermItem(
            id = "files",
            title = "All files access (read & write)",
            desc = "Pick any file and save files anywhere on the phone.",
            runtime = {
                if (Build.VERSION.SDK_INT < 30) {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    emptyArray()
                }
            },
            special = { c ->
                if (Build.VERSION.SDK_INT >= 30) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            },
            settings = { c ->
                if (Build.VERSION.SDK_INT >= 30) {
                    listOf(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(c)),
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } else {
                    emptyList()
                }
            }
        ),

        PermItem(
            id = "overlay",
            title = "Display over other apps",
            desc = "Let MockPad show floating windows on top of other apps.",
            special = { Settings.canDrawOverlays(it) },
            settings = { c -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))) }
        ),

        PermItem(
            id = "a11y",
            title = "Accessibility service",
            desc = "Kept ready for future features. Turn on MockPad in the list.",
            special = { a11yEnabled(it) },
            settings = { listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),

        PermItem(
            id = "camera",
            title = "Camera",
            desc = "Take photos and scan when MockPad needs the camera.",
            runtime = { arrayOf(Manifest.permission.CAMERA) }
        ),

        PermItem(
            id = "mic",
            title = "Microphone",
            desc = "Record your voice for speaking and shadowing practice.",
            runtime = { arrayOf(Manifest.permission.RECORD_AUDIO) }
        ),

        PermItem(
            id = "location",
            title = "Location",
            desc = "Let MockPad use your location when it asks.",
            runtime = {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        ),

        PermItem(
            id = "nearby",
            title = "Nearby devices",
            desc = "Bluetooth and Wi-Fi devices around you.",
            runtime = {
                when {
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    )
                    Build.VERSION.SDK_INT >= 31 -> arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                    else -> emptyArray()
                }
            }
        ),

        PermItem(
            id = "writesettings",
            title = "Modify system settings",
            desc = "Let MockPad change settings such as brightness or ringtone.",
            special = { Settings.System.canWrite(it) },
            settings = { c -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(c))) }
        ),

        PermItem(
            id = "install",
            title = "Install unknown apps",
            desc = "Let MockPad open and install APK files.",
            special = { it.packageManager.canRequestPackageInstalls() },
            settings = { c -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(c))) }
        ),

        PermItem(
            id = "battery",
            title = "Run without battery limits",
            desc = "Stops Android from putting MockPad to sleep.",
            special = { c ->
                val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(c.packageName)
            },
            settings = { c ->
                listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c)),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        ),

        PermItem(
            id = "alarms",
            title = "Alarms & reminders",
            desc = "Let MockPad fire reminders at the exact time.",
            special = { c ->
                if (Build.VERSION.SDK_INT >= 31) {
                    (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                } else {
                    true
                }
            },
            settings = { c ->
                if (Build.VERSION.SDK_INT >= 31) {
                    listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(c)))
                } else {
                    emptyList()
                }
            }
        )
    )

    fun missingCount(c: Context): Int = all.count { !it.granted(c) }
}

/**
 * Empty accessibility service. It exists so "Accessibility" shows up in Android settings
 * and can be switched on now; real features can be added to it later.
 */
class ClusterAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // nothing yet
    }

    override fun onInterrupt() {
        // nothing yet
    }
}
