package zed.rainxch.tweaks.presentation.model

/**
 * Per-scope form backing state for the proxy section. Each
 * [zed.rainxch.core.domain.model.ProxyScope] card owns one of these
 * — keeps the in-progress, test, and visibility flags independent
 * across scopes so the user can edit one card while a test runs on
 * another.
 */
data class ProxyScopeFormState(
    val type: ProxyType = ProxyType.SYSTEM,
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isTestInProgress: Boolean = false,
    /**
     * True once the user has edited any field in this scope's form.
     * Gates the preferences-to-form hydration path in the ViewModel:
     * once a scope is dirty, incoming emissions from DataStore are
     * ignored for that scope until the user saves (commits) or resets,
     * so a concurrent write to *another* preference key doesn't
     * clobber the in-progress edit when its Flow re-emits.
     */
    val isDraftDirty: Boolean = false,
)
