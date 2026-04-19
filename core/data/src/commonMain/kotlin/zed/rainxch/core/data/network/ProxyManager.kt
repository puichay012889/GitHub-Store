package zed.rainxch.core.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zed.rainxch.core.domain.model.ProxyConfig
import zed.rainxch.core.domain.model.ProxyScope

/**
 * Live in-memory cache of the three per-scope proxy configurations.
 * Writers (the repository) push updates via [setConfig]; consumers
 * subscribe to [configFlow] so they rebuild their HTTP clients when
 * the user flips a setting mid-session.
 */
object ProxyManager {
    private val flows: Map<ProxyScope, MutableStateFlow<ProxyConfig>> =
        ProxyScope.entries.associateWith { MutableStateFlow<ProxyConfig>(ProxyConfig.System) }

    fun configFlow(scope: ProxyScope): StateFlow<ProxyConfig> =
        flows.getValue(scope).asStateFlow()

    fun currentConfig(scope: ProxyScope): ProxyConfig =
        flows.getValue(scope).value

    fun setConfig(
        scope: ProxyScope,
        config: ProxyConfig,
    ) {
        flows.getValue(scope).value = config
    }
}
