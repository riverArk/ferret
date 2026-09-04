package io.riverark.ferret.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticCode(val value: String) {
    AUTHENTICATION("FRT-001"),
    KEYSTORE("FRT-002"),
    CONNECTIVITY("FRT-003"),
    DEPLOYMENT("FRT-004"),
    BACKUP("FRT-005"),
    OPERATION("FRT-006"),
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
