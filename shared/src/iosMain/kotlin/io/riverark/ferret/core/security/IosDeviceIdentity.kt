@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.riverark.ferret.core.security

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

class IosDeviceIdentity {
    private val path = applicationSupport() + "/device-identity.v1"

    fun publicKeyHex(): String {
        val manager = NSFileManager.defaultManager
        val bytes = if (manager.fileExistsAtPath(path)) {
            val data = NSData.dataWithContentsOfFile(path) ?: error("Device identity is unreadable.")
            require(data.length == 32uL) { "Device identity is corrupt." }
            ByteArray(32).also { buffer ->
                buffer.usePinned { memcpy(it.addressOf(0), data.bytes, 32.convert()) }
            }
        } else {
            ByteArray(32).also { buffer ->
                try {
                    buffer.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, 32.convert(), it.addressOf(0)) == errSecSuccess) }
                    protectedAtomicWrite(path, buffer)
                } catch (failure: Throwable) {
                    buffer.fill(0)
                    throw failure
                }
            }
        }
        return try {
            val alphabet = "0123456789abcdef"
            buildString(64) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 255
                    append(alphabet[unsigned ushr 4])
                    append(alphabet[unsigned and 15])
                }
            }
        }
        finally { bytes.fill(0) }
    }
}
