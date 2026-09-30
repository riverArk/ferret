package io.riverark.ferret.feature.wallet

import platform.Foundation.NSDate
import platform.UIKit.UIPasteboard

/** Core Image returns a width followed by exactly width² grayscale modules. */
interface IosQrEncoder {
    fun encode(address: String): ByteArray?
}

fun addressQrCode(address: String, encoder: IosQrEncoder): QrCode {
    require(address.isNotBlank())
    val encoded = encoder.encode(address) ?: error("Address QR encoding failed.")
    require(encoded.size >= 3) { "Address QR encoding failed." }
    val width = ((encoded[0].toInt() and 255) shl 8) or (encoded[1].toInt() and 255)
    require(width > 0 && encoded.size.toLong() == width.toLong() * width + 2) { "Address QR encoding failed." }
    val size = width + 8
    val modules = BooleanArray(size * size)
    for (y in 0 until width) for (x in 0 until width) {
        val pixel = encoded[2 + y * width + x].toInt() and 255
        require(pixel == 0 || pixel == 1) { "Address QR encoding failed." }
        modules[(y + 4) * size + x + 4] = pixel == 1
    }
    return QrCode(size, modules)
}

/** iOS expires this item itself; never clear an unrelated later clipboard value. */
fun copyIosAddress(address: String) {
    require(address.isNotBlank())
    UIPasteboard.generalPasteboard.setItems(
        listOf(mapOf("public.utf8-plain-text" to address)),
        options = mapOf(
            platform.UIKit.UIPasteboardOptionLocalOnly to true,
            platform.UIKit.UIPasteboardOptionExpirationDate to NSDate(timeIntervalSinceReferenceDate = platform.CoreFoundation.CFAbsoluteTimeGetCurrent() + 60.0),
        ),
    )
}
