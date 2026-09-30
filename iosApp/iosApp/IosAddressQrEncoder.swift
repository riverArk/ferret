import CoreImage
import Foundation
import Shared

final class IosAddressQrEncoder: IosQrEncoder {
    private let context = CIContext(options: [.useSoftwareRenderer: false])

    func encode(address: String) -> KotlinByteArray? {
        guard !address.isEmpty,
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(address.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let image = filter.outputImage else { return nil }
        let rect = image.extent.integral
        let width = Int(rect.width)
        guard width > 0, width <= 177, rect.height == rect.width else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * width * 4)
        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        rgba.withUnsafeMutableBytes { buffer in
            context.render(image, toBitmap: buffer.baseAddress!, rowBytes: width * 4,
                           bounds: rect, format: .RGBA8, colorSpace: colorSpace)
        }
        let result = KotlinByteArray(size: Int32(width * width + 2))
        result.set(index: 0, value: Int8(bitPattern: UInt8(width >> 8)))
        result.set(index: 1, value: Int8(bitPattern: UInt8(width & 255)))
        for index in 0..<(width * width) {
            result.set(index: Int32(index + 2), value: rgba[index * 4] < 128 ? 1 : 0)
        }
        return result
    }
}
