package org.carbridge.patch

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** apksig signing and verification, independent of where the key lives (Keystore on the phone, a file in tests). */
object ApkSigning {
    private const val PAGE_16K = 16 * 1024

    fun sign(input: File, output: File, minSdk: Int, key: PrivateKey, cert: X509Certificate) {
        val signer = ApkSigner.SignerConfig.Builder("CarBridge", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(minSdk < 24)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setLibraryPageAlignmentBytes(PAGE_16K)
            .setCreatedBy("CarBridge")
            .build()
            .sign()
    }

    /** Outcome of checking a signed APK the way Android's installer will. */
    data class Verification(val verified: Boolean, val certSha256: String?, val problems: List<String>) {
        val summary get() = if (verified) "Signature OK (${certSha256?.take(17)}…)" else problems.joinToString("; ")
    }

    fun verify(apk: File, minSdk: Int, expected: X509Certificate? = null): Verification {
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk).build().verify()
        val problems = (result.errors + result.v2SchemeSigners.flatMap { it.errors } + result.v3SchemeSigners.flatMap { it.errors })
            .map { it.toString() }
            .toMutableList()
        val cert = result.signerCertificates.firstOrNull()
        val sha = cert?.let(::sha256)
        if (expected != null && cert != null && sha != sha256(expected)) problems += "signed with an unexpected certificate"
        if (!result.isVerified && problems.isEmpty()) problems += "verification failed"
        return Verification(result.isVerified && problems.isEmpty(), sha, problems)
    }

    fun sha256(cert: X509Certificate): String = sha256(cert.encoded)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02x".format(it) }
}
