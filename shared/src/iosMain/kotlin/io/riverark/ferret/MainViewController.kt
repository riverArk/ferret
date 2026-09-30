package io.riverark.ferret

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(runtime: IosWalletRuntime) = ComposeUIViewController {
    FerretApp(runtime.dependencies, runtime::unlock, runtime::setSensitiveContent, runtime.unlockError)
}
