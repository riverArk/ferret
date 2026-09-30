import Foundation
import CoreImage
import Shared
import XCTest
@testable import Ferret

final class IosCryptoTests: XCTestCase {
    private let kit = IosCryptoKit()

    func testSha256AndHkdfVectors() {
        XCTAssertEqual(hex(kit.sha256(input: bytes(Data("abc".utf8)))),
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(hex(kit.hkdfSha256(input: bytes(Data(repeating: 0x0b, count: 22)),
                                              salt: bytes(Data(0...12)), info: bytes(Data(0xf0...0xf9)), size: 42)),
                       "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    func testAes256GcmCiphertextThenTagWithoutNonce() {
        let key = bytes(Data(repeating: 0, count: 32))
        let nonce = bytes(Data(repeating: 0, count: 12))
        let plain = bytes(Data(repeating: 0, count: 16))
        let aad = bytes(Data())
        guard let sealed = kit.encryptAesGcm(key: key, nonce: nonce, plaintext: plain, aad: aad) else {
            return XCTFail("Encryption failed")
        }
        XCTAssertEqual(hex(sealed), "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
        XCTAssertEqual(hex(kit.decryptAesGcm(key: key, nonce: nonce, ciphertext: sealed, aad: aad)),
                       String(repeating: "00", count: 16))
        XCTAssertNil(kit.decryptAesGcm(key: key, nonce: nonce, ciphertext: sealed, aad: bytes(Data("wrong".utf8))))
        let tampered = bytes(Data((0..<Int(sealed.size)).map {
            UInt8(bitPattern: sealed.get(index: Int32($0))) ^ ($0 == 31 ? 1 : 0)
        }))
        XCTAssertNil(kit.decryptAesGcm(key: key, nonce: nonce, ciphertext: tampered, aad: aad))
    }

    func testBase64UrlWithoutPadding() {
        let adapter = IosBackupCrypto(crypto: kit)
        XCTAssertEqual(adapter.base64Url(input: bytes(Data([0xfb, 0xff, 0xff, 0xfa]))), "-___-g")
        XCTAssertEqual(adapter.base64Url(input: bytes(Data())), "")
    }

    func testAddressQrRoundTrip() {
        let address = "addr_test1vrpynvza5vswczszkjhe5cvqz2awmzukf84xa5wway8durqpmfm2m"
        guard let encoded = IosAddressQrEncoder().encode(address: address) else {
            return XCTFail("QR generation failed")
        }
        let width = Int(UInt8(bitPattern: encoded.get(index: 0))) * 256
            + Int(UInt8(bitPattern: encoded.get(index: 1)))
        let size = width + 8
        var pixels = [UInt8](repeating: 255, count: size * size)
        for y in 0..<width {
            for x in 0..<width {
                pixels[(y + 4) * size + x + 4] =
                    encoded.get(index: Int32(2 + y * width + x)) == 1 ? 0 : 255
            }
        }
        let image = CIImage(bitmapData: Data(pixels), bytesPerRow: size,
                            size: CGSize(width: size, height: size), format: .L8,
                            colorSpace: CGColorSpaceCreateDeviceGray())
            .transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(),
                                  options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let found = detector?.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        XCTAssertEqual(found, [address])
    }

    private func bytes(_ data: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }

    private func hex(_ data: KotlinByteArray?) -> String? {
        guard let data else { return nil }
        return (0..<Int(data.size)).map {
            String(format: "%02x", UInt8(bitPattern: data.get(index: Int32($0))))
        }.joined()
    }
}
