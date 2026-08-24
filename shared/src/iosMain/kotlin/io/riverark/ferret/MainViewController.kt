package io.riverark.ferret

import androidx.compose.ui.window.ComposeUIViewController
import io.riverark.ferret.core.model.WalletRepository

fun MainViewController() = ComposeUIViewController {
    FerretApp(FerretDependencies(WalletRepository()))
}
