package io.riverark.ferret.core.security

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

class AndroidDeviceIdentity internal constructor(
    private val file: File,
    private val random: (ByteArray) -> Unit,
) {
    constructor(context: Context) : this(
        context.noBackupFilesDir.resolve("channel-device-public-key.v1"),
        SecureRandom()::nextBytes,
    )

    @Synchronized
    fun publicKeyHex(): String {
        val bytes = if (file.exists()) file.readBytes() else create()
        require(bytes.size == 32) { "invalid device identity" }
        return try {
            bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        } finally {
            bytes.fill(0)
        }
    }

    private fun create(): ByteArray {
        val bytes = ByteArray(32).also(random)
        val temporary = file.resolveSibling(file.name + ".new")
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(file)) { "cannot persist device identity" }
            return bytes
        } catch (error: Throwable) {
            temporary.delete()
            bytes.fill(0)
            throw error
        }
    }
}
