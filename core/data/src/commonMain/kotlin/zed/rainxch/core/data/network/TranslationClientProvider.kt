package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import zed.rainxch.core.domain.model.ProxyConfig

/**
 * Reactive holder for the HTTP client used by translation requests.
 * Rebuilds the underlying client whenever the translation-scope
 * proxy config changes so README translation picks up proxy updates
 * without requiring an app restart. Mirrors [GitHubClientProvider]
 * but keeps translation on its own client — translation endpoints
 * (e.g. Google Translate) don't need any of the GitHub-specific
 * interceptors, auth headers, or base URL defaults.
 */
class TranslationClientProvider(
    proxyConfigFlow: StateFlow<ProxyConfig>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    private var currentClient: HttpClient = createPlatformHttpClient(proxyConfigFlow.value)

    init {
        proxyConfigFlow
            .drop(1)
            .distinctUntilChanged()
            .onEach { config ->
                mutex.withLock {
                    // Build the replacement *before* closing the old one
                    // so the volatile read from [client] never observes a
                    // closed-but-not-yet-reassigned client.
                    val replacement = createPlatformHttpClient(config)
                    val previous = currentClient
                    currentClient = replacement
                    previous.close()
                }
            }.launchIn(scope)
    }

    val client: HttpClient get() = currentClient

    fun close() {
        currentClient.close()
        scope.cancel()
    }
}
