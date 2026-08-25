package io.riverark.ferret.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticCode {
    AUTHENTICATION,
    KEYSTORE,
    CONNECTIVITY,
    DEPLOYMENT,
    BACKUP,
    OPERATION,
}

class RuntimeDiagnostics {
    private val mutableCode = MutableStateFlow<DiagnosticCode?>(null)
    val code = mutableCode.asStateFlow()

    fun record(code: DiagnosticCode) {
        mutableCode.value = code
    }

    fun clear() {
        mutableCode.value = null
    }
}
