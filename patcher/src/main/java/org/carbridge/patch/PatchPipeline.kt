package org.carbridge.patch

import android.content.Context
import android.content.pm.ApplicationInfo
import com.reandroid.apk.ApkModule
import com.reandroid.archive.writer.ZipAligner
import java.io.File
import org.carbridge.core.CarOptions

/** Every step of making and installing a car copy, in order, as the user sees them. */
enum class Stage(val title: String) {
    Check("Checking the app and free space"),
    Read("Reading the app"),
    Patch("Adding Android Auto entries"),
    Lock("Adding the parked-only video lock"),
    Write("Writing the car copy"),
    Sign("Signing it on this phone"),
    Verify("Verifying the signatures"),
    Install("Handing it to Android's installer"),
    Confirm("Confirm the install in Android's prompt"),
    Inspect("Checking the installed copy"),
}

/** A failure at a known [stage], with a reason the user can act on. */
class StageFailure(val stage: Stage, val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/** What the pipeline produced: signed APKs (base first) and how their signatures checked out. */
class PatchResult(val apks: List<File>, val newPackage: String, val verification: ApkSigning.Verification)

/** Original APK(s) in, signed car-enabled APK(s) out, base first. Runs off the main thread. */
class PatchPipeline(
    private val context: Context,
    private val onStage: (Stage, String?) -> Unit = { _, _ -> },
    private val log: (String) -> Unit,
) {

    fun patch(app: ApplicationInfo, options: CarOptions, parkedOnly: Boolean = true): PatchResult {
        val work = File(context.cacheDir, "patch").apply { deleteRecursively(); mkdirs() }
        val newPackage = carPackageName(app.packageName)
        val splits = app.splitSourceDirs.orEmpty().map(::File)
        val base = File(app.sourceDir)

        val guardDex = stage(Stage.Check) {
            preflight(base, splits, work)
            if (parkedOnly) readGuardDex(context) else null
        }

        val patcher = ManifestPatcher(log)
        val branding = IconBadger.branding(context, app)
        val unsignedBase = File(work, "base.unsigned.apk")
        val minSdk = ApkModule.loadApkFile(base).use { apk ->
            stage(Stage.Read) { stripSignatures(apk) }
            stage(Stage.Patch) { patcher.patch(apk, newPackage, branding, options) }
            if (guardDex != null) {
                stage(Stage.Lock) { patcher.addParkedGuard(apk, newPackage, guardDex) }
            } else {
                log("Parked-only lock: off for this copy")
            }
            stage(Stage.Write, splitDetail(0, splits.size)) { write(apk, unsignedBase) }
            apk.androidManifest.minSdkVersion ?: 1
        }
        val unsignedSplits = splits.mapIndexed { i, split ->
            File(work, "split$i.unsigned.apk").also { out ->
                stage(Stage.Write, splitDetail(i + 1, splits.size)) {
                    ApkModule.loadApkFile(split).use { apk ->
                        stripSignatures(apk)
                        patcher.patchSplit(apk, newPackage)
                        write(apk, out)
                    }
                }
            }
        }

        val signer = KeystoreSigner()
        val unsigned = listOf(unsignedBase) + unsignedSplits
        val signed = unsigned.mapIndexed { i, input ->
            File(work, input.name.replace(".unsigned", "")).also { output ->
                stage(Stage.Sign, "${i + 1} of ${unsigned.size}") {
                    signer.sign(input, output, minSdk)
                    input.delete()
                }
            }
        }
        log("Signed ${signed.size} APK(s)")

        val verification = stage(Stage.Verify) {
            val cert = signer.certificate()
            val results = signed.map { it.name to ApkSigning.verify(it, minSdk, cert) }
            val bad = results.filterNot { it.second.verified }
            if (bad.isNotEmpty()) {
                throw StageFailure(Stage.Verify, bad.joinToString("\n") { (name, v) -> "$name: ${v.summary}" })
            }
            results.first().second.also { log(it.summary) }
        }
        return PatchResult(signed, newPackage, verification)
    }

    private fun preflight(base: File, splits: List<File>, work: File) {
        val all = listOf(base) + splits
        all.firstOrNull { !it.canRead() }?.let {
            throw StageFailure(Stage.Check, "Android didn't let CarBridge read ${it.name}.")
        }
        val total = all.sumOf { it.length() }
        log("Reading ${total / MB} MB in ${all.size} APK(s): " + all.joinToString { it.name })
        // Unsigned and signed copies exist side by side while signing
        val needed = total * 2 + 64 * MB
        val free = work.usableSpace
        if (free < needed) {
            throw StageFailure(Stage.Check, "Not enough free space: needs about ${needed / MB} MB, ${free / MB} MB free.")
        }
    }

    /** Runs one stage, reporting it first and turning any failure into a [StageFailure]. */
    private inline fun <T> stage(stage: Stage, detail: String? = null, block: () -> T): T {
        onStage(stage, detail)
        return try {
            block()
        } catch (e: StageFailure) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw StageFailure(stage, "Ran out of memory. Close other apps and try again.", e)
        } catch (e: Throwable) {
            // Throwable: apksig/ARSCLib can throw Errors (e.g. missing JDK classes on Android)
            throw StageFailure(stage, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun splitDetail(index: Int, splits: Int) =
        if (splits == 0) null else if (index == 0) "base APK" else "split $index of $splits"

    companion object {
        private const val MB = 1024L * 1024
        private val V1_SIGNATURE_FILES =
            Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))", RegexOption.IGNORE_CASE).toPattern()

        const val CAR_LABEL_SUFFIX = " (Car)"
        const val GUARD_ASSET = "carbridge_guard.dex"

        fun carPackageName(original: String) = "$original.car"

        /** The original signatures would be invalid after patching; all parts get re-signed. */
        fun stripSignatures(apk: ApkModule) {
            apk.setApkSignatureBlock(null)
            apk.zipEntryMap.removeIf(V1_SIGNATURE_FILES)
        }

        fun write(apk: ApkModule, output: File) {
            val writer = apk.createApkFileWriter(output)
            writer.zipAligner = ZipAligner().apply {
                setDefaultAlignment(4)
                // Uncompressed native libs must be page-aligned; 16 KB covers 4 KB devices too
                setFileAlignment(ZipAligner.PREDICATE_NATIVE_LIBS, 16 * 1024)
            }
            writer.write()
        }

        fun readGuardDex(context: Context): ByteArray =
            context.assets.open(GUARD_ASSET).use { it.readBytes() }
    }
}
