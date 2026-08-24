package io.riverark.ferret

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.riverark.ferret.core.model.WalletRepository

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FerretApp(FerretDependencies(WalletRepository())) }
    }
}
