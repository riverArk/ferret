@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.riverark.ferret.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.*
import platform.CommonCrypto.CC_SHA256
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

fun iosFerretHttpClient(): HttpClient = ferretHttpClient(Darwin.create {
    handleChallenge { _, _, challenge, complete ->
        val space = challenge.protectionSpace
        val host = space.host.lowercase()
        val pins = iosTlsPins[host]
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust ||
            (pins == null && !host.endsWith(".ferret.channel") && !host.endsWith(".crustypants.com"))) {
            complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            val trust = space.serverTrust
            val accepted = pins != null && trust != null && trustedPinnedChain(trust, host, pins)
            if (accepted) complete(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust!!))
            else complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }
})

private fun trustedPinnedChain(trust: SecTrustRef, host: String, pins: Set<String>): Boolean {
    val name = CFBridgingRetain(host)
    try {
        val policy = SecPolicyCreateSSL(true, name)
        try {
            if (SecTrustSetPolicies(trust, policy) != errSecSuccess || !SecTrustEvaluateWithError(trust, null)) return false
        } finally { CFRelease(policy) }
    } finally { CFBridgingRelease(name) }
    for (index in 0 until SecTrustGetCertificateCount(trust).toInt()) {
        val certificate = SecTrustGetCertificateAtIndex(trust, index.toLong()) ?: return false
        val data = SecCertificateCopyData(certificate) ?: return false
        try {
            val size = CFDataGetLength(data).toInt()
            if (size !in 1..65_536) return false
            val der = CFDataGetBytePtr(data)!!.readBytes(size)
            val pin = certificatePin(der) ?: return false
            if (pin in pins) return true
        } finally { CFRelease(data) }
    }
    return false
}

internal fun certificatePin(der: ByteArray): String? {
    val spki = certificateSpki(der) ?: return null
    val digest = ByteArray(32)
    spki.usePinned { input -> digest.usePinned { output ->
        CC_SHA256(input.addressOf(0), spki.size.convert(), output.addressOf(0))
    } }
    return digest.usePinned { bytes ->
        NSData.dataWithBytes(bytes.addressOf(0), 32u).base64EncodedStringWithOptions(0u)
    }
}

// Return the original DER SPKI TLV: a reconstructed key is not the certificate's pin input.
internal fun certificateSpki(der: ByteArray): ByteArray? {
    if (der.size !in 1..65_536) return null
    var cursor = 0
    fun tlv(end: Int): Pair<Int, Int>? {
        if (cursor + 2 > end) return null
        val start = cursor
        cursor++ // tag
        val first = der[cursor++].toInt() and 255
        val length = if (first < 128) first else {
            val count = first and 127
            if (count !in 1..3 || cursor + count > end || der[cursor].toInt() == 0) return null
            var value = 0
            repeat(count) { value = (value shl 8) or (der[cursor++].toInt() and 255) }
            if (value < 128) return null
            value
        }
        if (length > end - cursor) return null
        return start to (cursor + length)
    }
    val outer = tlv(der.size) ?: return null
    if (der[outer.first] != 0x30.toByte() || outer.second != der.size) return null
    val certificateEnd = outer.second
    val tbs = tlv(certificateEnd) ?: return null
    if (der[tbs.first] != 0x30.toByte()) return null
    val tbsEnd = tbs.second
    if (cursor < tbsEnd && der[cursor] == 0xa0.toByte()) {
        val version = tlv(tbsEnd) ?: return null
        if (version.second <= cursor || der.getOrNull(cursor) != 0x02.toByte()) return null
        val integer = tlv(version.second) ?: return null
        if (integer.second != version.second) return null
        cursor = version.second
    }
    for (tag in listOf(0x02, 0x30, 0x30, 0x30, 0x30)) {
        val field = tlv(tbsEnd) ?: return null
        if ((der[field.first].toInt() and 255) != tag) return null
        cursor = field.second
    }
    val spki = tlv(tbsEnd) ?: return null
    if (der[spki.first] != 0x30.toByte()) return null
    return der.copyOfRange(spki.first, spki.second)
}
