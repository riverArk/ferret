package io.riverark.ferret.feature.wallet

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import kotlin.test.Test
import kotlin.test.assertEquals

class AddressQrCodeTest {
    @Test
    fun qrPayloadIsExactWalletAddress() {
        val address = "addr_test1vrpynvza5vswczszkjhe5cvqz2awmzukf84xa5wway8durqpmfm2m"
        val qr = addressQrCode(address)
        val quietZone = 4
        val encoded = BitMatrix(qr.size - quietZone * 2)
        for (y in quietZone until qr.size - quietZone) {
            for (x in quietZone until qr.size - quietZone) {
                if (qr.modules[y * qr.size + x]) encoded.set(x - quietZone, y - quietZone)
            }
        }

        assertEquals(address, Decoder().decode(encoded).text)
    }
}
