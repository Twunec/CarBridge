package org.carbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.carbridge.patch.ApkSigning
import org.carbridge.patch.KeystoreSigner
import org.carbridge.patch.ManifestPatcher
import org.carbridge.patch.PatchPipeline

enum class CheckState { Pass, Warn, Fail, Manual }

/** One check of an installed car copy; [manualKey] marks a test only the user can do in the car. */
data class CheckItem(
    val title: String,
    val state: CheckState,
    val detail: String,
    val manualKey: String? = null,
    val done: Boolean = false,
)

data class CheckSection(val title: String, val items: List<CheckItem>)

/**
 * Checks an installed car copy in separate parts, because a copy that patched and signed fine can
 * still fail to install, stay hidden in Android Auto, or not play. What the phone can check is
 * checked; what only the car can show is a checklist the user ticks.
 */
data class CopyChecks(val packageName: String, val label: String, val versionCode: Long, val sections: List<CheckSection>) {
    private val automatic get() = sections.flatMap { it.items }.filter { it.state != CheckState.Manual }
    private val manual get() = sections.flatMap { it.items }.filter { it.state == CheckState.Manual }
    val passed get() = automatic.count { it.state == CheckState.Pass }
    val total get() = automatic.size
    val failed get() = automatic.count { it.state == CheckState.Fail }
    val manualDone get() = manual.count { it.done }
    val manualTotal get() = manual.size
    val needsLocation get() = automatic.any { it.title == LOCATION_TITLE && it.state == CheckState.Fail }

    val summary get() = buildString {
        append("$passed of $total phone checks passed")
        if (manualTotal > 0) append(" · $manualDone of $manualTotal car tests done")
    }

    companion object {
        private const val CAR_APP_META = "com.google.android.gms.car.application"
        private const val CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        private const val LOCATION_TITLE = "Location allowed for the copy"

        val MANUAL = listOf(
            "visible" to ("Shows in the car's app launcher" to "Reconnect the phone, open the launcher on the car screen and look for the copy."),
            "opens" to ("Opens on the car screen" to "Tap it on the car screen. If it closes at once, the app is probably refusing to run re-signed."),
            "plays" to ("A video plays while parked" to "With the parking brake on, start a video. Signed-out playback is the expected baseline."),
            "blocks" to ("Video is blocked when moving" to "As a passenger test: when the car moves off, the screen should switch to “Available when parked”."),
        )

        fun manualPrefKey(pkg: String, versionCode: Long, key: String) = "manual:$pkg:$versionCode:$key"
        fun verifiedPrefKey(pkg: String) = "verified:$pkg"

        @Suppress("DEPRECATION")
        fun run(context: Context, prefs: SharedPreferences, carPackage: String): CopyChecks? {
            val pm = context.packageManager
            val info = runCatching {
                pm.getPackageInfo(
                    carPackage,
                    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
                )
            }.getOrNull() ?: return null
            val app = info.applicationInfo ?: return null
            val originalPkg = carPackage.removeSuffix(PatchPipeline.carPackageName(""))
            val original = runCatching { pm.getPackageInfo(originalPkg, 0) }.getOrNull()
            val label = app.loadLabel(pm).toString()

            // Signing
            val installedCert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(ApkSigning::sha256)
            val ourCert = runCatching { ApkSigning.sha256(KeystoreSigner().certificate()) }.getOrNull()
            val verified = prefs.getString(verifiedPrefKey(carPackage), null)
            val signing = listOf(
                when {
                    installedCert == null -> CheckItem("Signed with this phone's key", CheckState.Fail, "Android reported no signer.")
                    installedCert == ourCert -> CheckItem("Signed with this phone's key", CheckState.Pass, "Certificate ${installedCert.take(23)}…")
                    else -> CheckItem(
                        "Signed with this phone's key",
                        CheckState.Warn,
                        "Signed with another key (${installedCert.take(23)}…), so CarBridge can't update it in place. Uninstall and add it again.",
                    )
                },
                if (verified != null) {
                    CheckItem("Signatures verified before install", CheckState.Pass, verified)
                } else {
                    CheckItem("Signatures verified before install", CheckState.Warn, "No record: this copy was made by an older CarBridge or another install.")
                },
            )

            // Installation
            val splitCount = app.splitSourceDirs?.size ?: 0
            val originalSplits = original?.applicationInfo?.splitSourceDirs?.size
            val install = listOfNotNull(
                CheckItem("Installed as $carPackage", CheckState.Pass, "Version ${info.versionName} (${info.longVersionCode})"),
                when {
                    original == null -> CheckItem("Original app kept", CheckState.Warn, "$originalPkg isn't installed any more.")
                    original.longVersionCode != info.longVersionCode -> CheckItem(
                        "Matches the original's version",
                        CheckState.Warn,
                        "Original is ${original.versionName}, copy is ${info.versionName}. Tap Update.",
                    )
                    else -> CheckItem("Original app kept and matching", CheckState.Pass, "$originalPkg ${original.versionName} is untouched.")
                },
                if (originalSplits == null) null
                else if (splitCount == originalSplits) {
                    CheckItem("All APK parts installed", CheckState.Pass, "Base + $splitCount split APK(s)")
                } else {
                    CheckItem("All APK parts installed", CheckState.Fail, "Copy has $splitCount splits, original has $originalSplits.")
                },
            )

            // Android Auto visibility
            val hasMeta = app.metaData?.containsKey(CAR_APP_META) == true
            val carLauncher = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(CAR_LAUNCHER).setPackage(carPackage), 0,
            )
            val aa = runCatching { pm.getPackageInfo(ANDROID_AUTO, 0) }.getOrNull()
            val unknownSources = prefs.getBoolean("unknown_sources", false)
            val auto = listOf(
                CheckItem(
                    "Declares itself a car app",
                    if (hasMeta) CheckState.Pass else CheckState.Fail,
                    if (hasMeta) "$CAR_APP_META is present" else "Car meta-data missing from the installed copy.",
                ),
                CheckItem(
                    "Has a car launcher entry",
                    if (carLauncher.isNotEmpty()) CheckState.Pass else CheckState.Fail,
                    carLauncher.firstOrNull()?.activityInfo?.name?.substringAfterLast('.') ?: "No CAR_LAUNCHER activity found.",
                ),
                CheckItem(
                    "Android Auto installed",
                    if (aa != null) CheckState.Pass else CheckState.Fail,
                    aa?.let { "Version ${it.versionName}" } ?: "Install Android Auto from the Play Store.",
                ),
                CheckItem(
                    "Unknown sources on in Android Auto",
                    if (unknownSources) CheckState.Pass else CheckState.Warn,
                    if (unknownSources) "You confirmed this in setup." else "Android Auto hides sideloaded apps until this is on. See setup.",
                ),
            )

            // Parked-only lock
            val hasLock = info.providers.orEmpty().any { it.name == ManifestPatcher.GUARD_PROVIDER }
            val location = pm.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, carPackage) == PackageManager.PERMISSION_GRANTED
            val lock = if (hasLock) {
                listOf(
                    CheckItem("Parked-only lock included", CheckState.Pass, "Video on the car screen needs GPS to show the car is still."),
                    CheckItem(
                        LOCATION_TITLE,
                        if (location) CheckState.Pass else CheckState.Fail,
                        if (location) "Precise location granted." else "Without it the car screen stays locked. Tap Allow location below, then Permissions → Location → Allow only while using the app.",
                    ),
                )
            } else {
                listOf(CheckItem("Parked-only lock included", CheckState.Warn, "This copy was made without the lock. Update it to add the lock."))
            }

            val manual = MANUAL.map { (key, text) ->
                CheckItem(
                    text.first,
                    CheckState.Manual,
                    text.second,
                    manualKey = key,
                    done = prefs.getBoolean(manualPrefKey(carPackage, info.longVersionCode, key), false),
                )
            }

            return CopyChecks(
                carPackage,
                label,
                info.longVersionCode,
                listOf(
                    CheckSection("Signing", signing),
                    CheckSection("Installation", install),
                    CheckSection("Android Auto visibility", auto),
                    CheckSection("Parked-only lock", lock),
                    CheckSection("In the car (tick when tested)", manual),
                ),
            )
        }
    }
}
