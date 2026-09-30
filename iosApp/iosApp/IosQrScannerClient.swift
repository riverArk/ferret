import AVFoundation
import Shared
import UIKit

private final class QrPreview: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var videoLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

final class IosQrScannerClient: NSObject, IosQrScanner, AVCaptureMetadataOutputObjectsDelegate {
    let view: UIView
    private let preview = QrPreview()
    private let captureQueue = DispatchQueue(label: "io.riverark.ferret.qr-camera")
    private var session: AVCaptureSession? // captureQueue only
    private var generation = 0 // main thread only
    private var delivered = false // main thread only
    private var backgroundObserver: NSObjectProtocol?
    private var foregroundObserver: NSObjectProtocol?
    private var events: IosQrScannerEvents?
    private var resumeEvents: IosQrScannerEvents?

    override init() {
        view = preview
        super.init()
        preview.videoLayer.videoGravity = .resizeAspectFill
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            let saved = self.events
            self.stop()
            self.resumeEvents = saved
        }
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, let saved = self.resumeEvents else { return }
            self.resumeEvents = nil
            self.start(events: saved)
        }
    }

    deinit {
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
        // Composition calls stop before releasing this object.
    }

    func start(events: IosQrScannerEvents) {
        precondition(Thread.isMainThread)
        stop()
        self.events = events
        delivered = false
        let token = generation
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure(token: token)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self, self.generation == token else { return }
                    if granted { self.configure(token: token) }
                    else { self.events?.onPermissionDenied() }
                }
            }
        default:
            events.onPermissionDenied()
        }
    }

    func stop() {
        precondition(Thread.isMainThread)
        generation &+= 1
        delivered = true
        events = nil
        resumeEvents = nil
        preview.videoLayer.session = nil
        captureQueue.async { [self] in
            self.session?.stopRunning()
            self.session = nil
        }
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func configure(token: Int) {
        guard generation == token, UIApplication.shared.applicationState == .active else { return }
        captureQueue.async { [weak self] in
            guard let self else { return }
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device) else {
                self.fail(token: token)
                return
            }
            let session = AVCaptureSession()
            let output = AVCaptureMetadataOutput()
            session.beginConfiguration()
            guard session.canAddInput(input), session.canAddOutput(output) else {
                session.commitConfiguration()
                self.fail(token: token)
                return
            }
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            DispatchQueue.main.async { [weak self] in
                guard let self, self.generation == token, UIApplication.shared.applicationState == .active else { return }
                self.preview.videoLayer.session = session
                self.captureQueue.async { [self] in
                    // stop() can invalidate the request before this queued start executes.
                    guard DispatchQueue.main.sync(execute: { self.generation == token }) else { return }
                    self.session = session
                    session.startRunning()
                }
            }
        }
    }

    private func fail(token: Int) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.generation == token, !self.delivered else { return }
            self.delivered = true
            self.events?.onError()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard Thread.isMainThread, !delivered, UIApplication.shared.applicationState == .active,
              let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                  .first(where: { $0.type == .qr && $0.stringValue != nil })?.stringValue else { return }
        let callback = events
        stop()
        callback?.onInvoice(value: code)
    }
}
