package io.riverark.ferret.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class IosTlsPinsTest {
    @Test fun rsaAndEcSpkiAndCorruptLengths() {
        val spkis = listOf(
            hex("3012300d06092a864886f70d0101010500030100"),
            hex("3014301006072a8648ce3d020106052b8104000a0300"),
        )
        for (spki in spkis) {
            val cert = certificate(spki)
            assertContentEquals(spki, certificateSpki(cert))
            assertNull(certificateSpki(cert.copyOf(cert.size - 1)))
            assertNull(certificateSpki(cert.clone().apply { this[1] = 0x80.toByte() }))
        }
    }

    private fun certificate(spki: ByteArray): ByteArray = seq(
        seq(byteArrayOf(2, 1, 1), seq(), seq(), seq(), seq(), spki), seq(), byteArrayOf(3, 1, 0),
    )
    private fun seq(vararg fields: ByteArray): ByteArray {
        val content = fields.fold(ByteArray(0)) { bytes, field -> bytes + field }
        require(content.size < 256)
        return byteArrayOf(0x30) +
            (if (content.size < 128) byteArrayOf(content.size.toByte())
             else byteArrayOf(0x81.toByte(), content.size.toByte())) + content
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
