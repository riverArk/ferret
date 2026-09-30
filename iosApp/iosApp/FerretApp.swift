import SwiftUI
import Shared
import GoogleSignIn
import UIKit
import Darwin

@main
struct FerretIosApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var host = IosHost()

    var body: some Scene {
        WindowGroup {
            ComposeRoot(host: host)
                .ignoresSafeArea()
                .onAppear { host.sceneChanged(scenePhase) }
                .onChange(of: scenePhase) { _, phase in host.sceneChanged(phase) }
                .onOpenURL { _ = GIDSignIn.sharedInstance.handle($0) }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataWillBecomeUnavailableNotification)) { _ in
                    host.protectedDataUnavailable()
                }
        }
    }
}

private enum ContinuousClock {
    static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        precondition(mach_timebase_info(&info) == KERN_SUCCESS)
        return info
    }()

    static func millis() -> Int64 {
        let nanoseconds = Double(mach_continuous_time()) * Double(timebase.numer) / Double(timebase.denom)
        return Int64(nanoseconds / 1_000_000)
    }
}

private final class IosHost: ObservableObject {
    weak var controller: UIViewController?
    private var cover: IosSensitiveCover?
    private var instance: IosWalletRuntime?
    private var sensitive = false
    private var active = false
    private lazy var google = IosGoogleSignInClient(
        clientID: Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String ?? "",
        presenting: { [weak self] in self?.controller }
    )

    var runtime: IosWalletRuntime {
        if let instance { return instance }
        let created = IosWalletRuntime(
            crypto: IosCryptoKit(),
            google: google,
            sensitiveContentChanged: { [weak self] visible in
                self?.sensitive = visible.boolValue
                self?.cover?.setSensitiveContentVisible(visible.boolValue)
            },
            scannerFactory: { IosQrScannerClient() },
            qrEncoder: IosAddressQrEncoder(),
            continuousMillis: { KotlinLong(value: ContinuousClock.millis()) }
        )
        instance = created
        return created
    }

    func attachWindow() {
        guard let window = controller?.view.window else { return }
        if cover == nil {
            cover = IosSensitiveCover(window: window)
            cover?.setSensitiveContentVisible(sensitive)
            cover?.setSceneActive(active)
            if active { cover?.setSessionValidated(true) }
        }
    }

    func sceneChanged(_ phase: ScenePhase) {
        active = phase == .active
        cover?.setSceneActive(active)
        switch phase {
        case .active:
            runtime.onForeground()
            cover?.setSessionValidated(true)
            attachWindow()
        case .background:
            instance?.onBackground()
        case .inactive:
            break // The authentication sheet makes the scene inactive.
        @unknown default:
            instance?.onBackground()
        }
    }

    func protectedDataUnavailable() {
        cover?.setSessionValidated(false)
        instance?.onProtectedDataUnavailable()
    }

    deinit {
        instance?.close()
        cover?.close()
    }
}

private struct ComposeRoot: UIViewControllerRepresentable {
    @ObservedObject var host: IosHost

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(runtime: host.runtime)
        host.controller = controller
        DispatchQueue.main.async { [weak host] in host?.attachWindow() }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        host.attachWindow()
    }
}
