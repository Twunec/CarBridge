package org.carbridge.patch

import com.reandroid.apk.ApkModule
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.carbridge.core.CarOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManifestPatcherTest {

    @Test
    fun renameValueSwapsPrefixAndPrefixesForeignParts() {
        assertEquals("a.b.car.provider", ManifestPatcher.renameValue("a.b.provider", "a.b", "a.b.car"))
        assertEquals("a.b.car.x;a.b.car.y", ManifestPatcher.renameValue("a.b.x;y", "a.b", "a.b.car"))
        assertEquals("a.b.car", ManifestPatcher.renameValue("a.b", "a.b", "a.b.car"))
    }

    /**
     * The phone's pipeline on the JVM: patches a real APK, or a directory holding base.apk plus
     * split_*.apk, adds the parked-only lock, signs every part with a throwaway key and verifies
     * the signatures the way Android's installer does. The output can then be checked with
     * aapt2 and apksigner, and installed on an emulator. Skipped without -PpatchFixture.
     */
    @Test
    fun patchesLocksSignsAndVerifiesFixture() {
        val fixture = System.getProperty("patchFixture")?.let(::File)
        assumeTrue("no -PpatchFixture given", fixture != null)
        val outDir = File(System.getProperty("patchOut")).parentFile.apply { deleteRecursively(); mkdirs() }
        val guardDex = File(System.getProperty("guardDex")).readBytes()

        val base = if (fixture!!.isDirectory) File(fixture, "base.apk") else fixture
        val splits = if (fixture.isDirectory) fixture.listFiles { f -> f.name.startsWith("split_") }!!.toList() else emptyList()
        val patcher = ManifestPatcher(::println)

        val newPackage = ApkModule.loadApkFile(base).use { it.androidManifest.packageName + ".car" }
        val unsigned = mutableListOf<File>()
        val minSdk = ApkModule.loadApkFile(base).use { apk ->
            PatchPipeline.stripSignatures(apk)
            // Real PNG layers, so the emulator can draw the installed copy's icon
            val png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            )
            patcher.patch(apk, newPackage, ManifestPatcher.Branding("Fixture (Car)", png, png), CarOptions(fillScreen = true))
            patcher.addParkedGuard(apk, newPackage, guardDex)
            File(outDir, "base.unsigned.apk").also { PatchPipeline.write(apk, it); unsigned += it }
            apk.androidManifest.minSdkVersion ?: 1
        }
        for (split in splits) {
            ApkModule.loadApkFile(split).use { apk ->
                PatchPipeline.stripSignatures(apk)
                patcher.patchSplit(apk, newPackage)
                File(outDir, split.name.replace(".apk", ".unsigned.apk")).also { PatchPipeline.write(apk, it); unsigned += it }
            }
        }

        // The written base carries the rename, the lock's code, provider and permissions
        ApkModule.loadApkFile(unsigned.first()).use { apk ->
            val manifest = apk.androidManifest
            assertEquals(newPackage, manifest.packageName)
            val provider = manifest.applicationElement.listElements("provider").firstOrNull { el ->
                el.searchAttributeByResourceId(0x01010003)?.valueAsString == ManifestPatcher.GUARD_PROVIDER
            }
            assertNotNull("guard provider missing", provider)
            assertEquals(
                newPackage + ManifestPatcher.GUARD_AUTHORITY_SUFFIX,
                provider!!.searchAttributeByResourceId(0x01010018)?.valueAsString,
            )
            ManifestPatcher.GUARD_PERMISSIONS.forEach { assertNotNull("$it missing", manifest.getUsesPermission(it)) }
            val dexes = apk.listDexFiles().map { it.name }
            println("Dex files: $dexes")
            assertTrue("guard dex missing", dexes.any { name -> apk.getInputSource(name).openStream().use { it.readBytes() }.contentEquals(guardDex) })
        }

        val (key, cert) = testKey(outDir)
        val signed = unsigned.map { input ->
            val name = if (input == unsigned.first()) "out.apk" else input.name.replace(".unsigned", "")
            File(outDir, name).also { ApkSigning.sign(input, it, minSdk, key, cert) }
        }
        for (apk in signed) {
            val result = ApkSigning.verify(apk, minSdk, cert)
            println("${apk.name}: ${result.summary}")
            assertTrue("${apk.name}: ${result.problems}", result.verified)
            assertEquals(ApkSigning.sha256(cert), result.certSha256)
        }
        unsigned.forEach(File::delete)
    }

    /** A throwaway RSA key from the JDK's keytool, like the phone's Keystore key. */
    private fun testKey(dir: File): Pair<PrivateKey, X509Certificate> {
        val store = File(dir, "test.p12").apply { delete() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "password",
            "-keypass", "password", "-alias", "test", "-keyalg", "RSA", "-keysize", "3072", "-validity", "30",
            "-dname", "CN=CarBridge test signer",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "password".toCharArray()) } }
        return ks.getKey("test", "password".toCharArray()) as PrivateKey to ks.getCertificate("test") as X509Certificate
    }
}
