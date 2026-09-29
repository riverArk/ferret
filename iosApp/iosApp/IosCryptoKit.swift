import CryptoKit
import Foundation
import Shared

final class IosCryptoKit: IosCrypto {
    func sha256(input: KotlinByteArray) -> KotlinByteArray? {
        var message = data(input)
        defer { wipe(&message) }
        return kotlinBytes(Data(SHA256.hash(data: message)))
    }

    func hkdfSha256(input: KotlinByteArray, salt: KotlinByteArray, info: KotlinByteArray, size: Int32) -> KotlinByteArray? {
        guard (1...8160).contains(size) else { return nil }
        var key = data(input)
        var saltData = data(salt)
        var infoData = data(info)
        defer { wipe(&key); wipe(&saltData); wipe(&infoData) }
        let output = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: key),
                                              salt: saltData, info: infoData, outputByteCount: Int(size))
        return output.withUnsafeBytes { kotlinBytes($0) }
    }

    func encryptAesGcm(key: KotlinByteArray, nonce: KotlinByteArray, plaintext: KotlinByteArray, aad: KotlinByteArray) -> KotlinByteArray? {
        guard key.size == 32, nonce.size == 12 else { return nil }
        var keyData = data(key)
        var nonceData = data(nonce)
        var message = data(plaintext)
        var authenticatedData = data(aad)
        defer { wipe(&keyData); wipe(&nonceData); wipe(&message); wipe(&authenticatedData) }
        do {
            let box = try AES.GCM.seal(message, using: SymmetricKey(data: keyData),
                                       nonce: AES.GCM.Nonce(data: nonceData), authenticating: authenticatedData)
            var result = box.ciphertext + box.tag
            defer { wipe(&result) }
            return kotlinBytes(result)
        } catch {
            return nil
        }
    }

    func decryptAesGcm(key: KotlinByteArray, nonce: KotlinByteArray, ciphertext: KotlinByteArray, aad: KotlinByteArray) -> KotlinByteArray? {
        guard key.size == 32, nonce.size == 12, ciphertext.size >= 16 else { return nil }
        var keyData = data(key)
        var nonceData = data(nonce)
        var encrypted = data(ciphertext)
        var authenticatedData = data(aad)
        defer { wipe(&keyData); wipe(&nonceData); wipe(&encrypted); wipe(&authenticatedData) }
        do {
            let split = encrypted.count - 16
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonceData),
                                            ciphertext: encrypted.prefix(split), tag: encrypted.suffix(16))
            var result = try AES.GCM.open(box, using: SymmetricKey(data: keyData), authenticating: authenticatedData)
            defer { wipe(&result) }
            return kotlinBytes(result)
        } catch {
            return nil
        }
    }

    private func data(_ bytes: KotlinByteArray) -> Data {
        var result = Data(count: Int(bytes.size))
        result.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            for index in 0..<Int(bytes.size) {
                buffer[index] = UInt8(bitPattern: bytes.get(index: Int32(index)))
            }
        }
        return result
    }

    private func kotlinBytes(_ bytes: Data) -> KotlinByteArray {
        bytes.withUnsafeBytes { kotlinBytes($0) }
    }

    private func kotlinBytes(_ buffer: UnsafeRawBufferPointer) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(buffer.count))
        for index in 0..<buffer.count {
            result.set(index: Int32(index), value: Int8(bitPattern: buffer[index]))
        }
        return result
    }

    private func wipe(_ bytes: inout Data) {
        bytes.resetBytes(in: 0..<bytes.count)
    }
}
