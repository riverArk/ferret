package io.riverark.ferret.feature.wallet

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

fun addressQrCode(address: String): QrCode {
    require(address.isNotBlank())
    val encoded = Encoder.encode(address, ErrorCorrectionLevel.M).matrix
    val quietZone = 4
    val size = encoded.width + quietZone * 2
    val modules = BooleanArray(size * size)
    for (y in 0 until encoded.height) {
        for (x in 0 until encoded.width) {
            modules[(y + quietZone) * size + x + quietZone] = encoded[x, y].toInt() == 1
        }
    }
    return QrCode(size, modules)
}
