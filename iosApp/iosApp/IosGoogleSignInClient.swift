import GoogleSignIn
import Shared
import UIKit

/// The SDK keeps its own credentials in Keychain. Ferret never persists access tokens.
final class IosGoogleSignInClient: NSObject, IosGoogleSignIn {
    private static let driveScope = "https://www.googleapis.com/auth/drive.appdata"
    private let clientID: String
    private let presenting: () -> UIViewController?
    private var selectedAccount: String?
    private var busy = false
    private var configurationReady = false

    init(clientID: String, presenting: @escaping () -> UIViewController?) {
        self.clientID = clientID
        self.presenting = presenting
        super.init()
        guard clientID.hasSuffix(".apps.googleusercontent.com") else { return }
        DispatchQueue.main.async {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            GIDSignIn.sharedInstance.configure { [weak self] error in
                guard let self, error == nil else { return }
                self.configurationReady = true
                GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, error in
                    guard let self, !self.busy, error == nil,
                          user?.grantedScopes?.contains(Self.driveScope) == true,
                          GIDSignIn.sharedInstance.currentUser?.profile?.email == user?.profile?.email else { return }
                    self.selectedAccount = user?.profile?.email
                }
            }
        }
    }

    var accountName: String? {
        let current = {
            guard let selectedAccount,
                  GIDSignIn.sharedInstance.currentUser?.profile?.email == selectedAccount else { return nil as String? }
            return selectedAccount
        }
        if Thread.isMainThread { return current() }
        return DispatchQueue.main.sync(execute: current)
    }

    func connect(completion: @escaping @Sendable (String?, String?) -> Void) {
        DispatchQueue.main.async { [self] in
            guard clientID.hasSuffix(".apps.googleusercontent.com") else { _ = completion(nil, "Google iOS client ID is not configured."); return }
            guard configurationReady else { _ = completion(nil, "Google Drive authentication is unavailable."); return }
            guard !busy else { _ = completion(nil, "Google Drive account selection is already active."); return }
            guard let presenter = presenting(), presenter.viewIfLoaded?.window != nil else {
                _ = completion(nil, "Google Drive account selection is unavailable."); return
            }
            busy = true
            // Always show the account selector; never silently select a different Google account.
            GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil,
                                            additionalScopes: [Self.driveScope]) { [self] result, error in
                busy = false
                guard error == nil, let user = result?.user,
                      user.grantedScopes?.contains(Self.driveScope) == true,
                      let email = user.profile?.email, !email.isEmpty else {
                    _ = completion(nil, "Google Drive account selection or consent was cancelled.")
                    return
                }
                selectedAccount = email
                _ = completion(email, nil)
            }
        }
    }

    func accessToken(completion: @escaping @Sendable (String?, String?) -> Void) {
        DispatchQueue.main.async { [self] in
            guard clientID.hasSuffix(".apps.googleusercontent.com") else { _ = completion(nil, "Google iOS client ID is not configured."); return }
            guard configurationReady else { _ = completion(nil, "Google Drive authentication is unavailable."); return }
            guard !busy else { _ = completion(nil, "Google Drive authorization is already active."); return }
            guard let account = selectedAccount,
                  let user = GIDSignIn.sharedInstance.currentUser,
                  user.profile?.email == account,
                  user.grantedScopes?.contains(Self.driveScope) == true else {
                _ = completion(nil, "Google Drive account is not connected."); return
            }
            busy = true
            user.refreshTokensIfNeeded { [self] refreshed, error in
                busy = false
                guard error == nil, let refreshed,
                      refreshed.profile?.email == selectedAccount,
                      refreshed.grantedScopes?.contains(Self.driveScope) == true else {
                    _ = completion(nil, "Google Drive authorization failed.")
                    return
                }
                _ = completion(refreshed.accessToken.tokenString, nil)
            }
        }
    }
}
