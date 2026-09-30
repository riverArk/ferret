import UIKit

/// Host-owned overlay: app-switcher snapshots and captured sensitive routes are obscured.
final class IosSensitiveCover {
    private weak var window: UIWindow?
    private let cover = UIView()
    private var active = false
    private var validated = false
    private var sensitive = false
    private var observers: [NSObjectProtocol] = []

    init(window: UIWindow) {
        self.window = window
        cover.backgroundColor = UIColor(red: 0.98, green: 0.97, blue: 0.92, alpha: 1)
        cover.isUserInteractionEnabled = true
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        cover.frame = window.bounds
        window.addSubview(cover)
        let center = NotificationCenter.default
        for event in [UIScreen.capturedDidChangeNotification, UIScreen.didConnectNotification,
                      UIScreen.didDisconnectNotification] {
            observers.append(center.addObserver(forName: event, object: nil, queue: .main) { [weak self] _ in
                self?.update()
            })
        }
        update()
    }

    func setSensitiveContentVisible(_ visible: Bool) {
        sensitive = visible
        update()
    }

    /// Call before foreground rendering; only clear after the wallet session has been checked.
    func setSessionValidated(_ valid: Bool) {
        validated = valid
        update()
    }

    /// Invoke synchronously on SwiftUI scenePhase changes, including .inactive (app switcher).
    func setSceneActive(_ isActive: Bool) {
        active = isActive
        if !isActive { validated = false }
        update()
    }

    func close() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        cover.removeFromSuperview()
    }

    private func update() {
        assert(Thread.isMainThread)
        let captured = UIScreen.screens.contains { $0.isCaptured }
        let obscured = !active || !validated || (sensitive && captured)
        cover.isHidden = !obscured
        if obscured { window?.bringSubviewToFront(cover) }
    }
}
