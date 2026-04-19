package zed.rainxch.details.data.repository

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import zed.rainxch.core.data.network.TranslationClientProvider
import zed.rainxch.core.data.services.LocalizationManager
import zed.rainxch.core.domain.model.TranslationProvider
import zed.rainxch.core.domain.repository.TweaksRepository
import zed.rainxch.details.data.translation.GoogleTranslator
import zed.rainxch.details.data.translation.Translator
import zed.rainxch.details.data.translation.YoudaoTranslator
import zed.rainxch.details.domain.model.TranslationResult
import zed.rainxch.details.domain.repository.TranslationRepository
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Orchestrates translation: picks the user-configured [Translator]
 * ([TranslationProvider]), drives chunking + caching in this layer
 * (so each concrete translator only has to round-trip a single
 * chunk), and stitches results back together.
 */
class TranslationRepositoryImpl(
    private val localizationManager: LocalizationManager,
    private val clientProvider: TranslationClientProvider,
    private val tweaksRepository: TweaksRepository,
) : TranslationRepository {
    private val httpClient: HttpClient get() = clientProvider.client

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // Google's provider has no per-install config — share a single
    // instance for the lifetime of the repository.
    private val googleTranslator: GoogleTranslator =
        GoogleTranslator(httpClient = { httpClient }, json = json)

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<CacheKey, CachedTranslation>(MAX_CACHE_SIZE, 0.75f, true)

    @OptIn(ExperimentalTime::class)
    override suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
    ): TranslationResult {
        val cacheKey = CacheKey(text, targetLanguage, sourceLanguage)

        cacheMutex.withLock {
            cache[cacheKey]?.let { cached ->
                if (!cached.isExpired()) return cached.result
                cache.remove(cacheKey)
            }
        }

        val translator = resolveTranslator()
        val chunks = chunkText(text, translator.maxChunkSize)
        val translatedParts = mutableListOf<Pair<String, String>>()
        var detectedLang: String? = null

        for ((chunkText, delimiter) in chunks) {
            val response = translator.translate(chunkText, targetLanguage, sourceLanguage)
            translatedParts.add(response.translatedText to delimiter)
            if (detectedLang == null) {
                detectedLang = response.detectedSourceLanguage
            }
        }

        val result =
            TranslationResult(
                translatedText =
                    translatedParts
                        .dropLast(1)
                        .joinToString("") { (text, delim) -> text + delim } +
                        translatedParts.lastOrNull()?.first.orEmpty(),
                detectedSourceLanguage = detectedLang,
            )

        cacheMutex.withLock {
            if (cache.size >= MAX_CACHE_SIZE) {
                val firstKey = cache.keys.first()
                cache.remove(firstKey)
            }
            cache[cacheKey] = CachedTranslation(result)
        }
        return result
    }

    override fun getDeviceLanguageCode(): String = localizationManager.getPrimaryLanguageCode()

    /**
     * Resolves the currently-selected translator from preferences.
     * Called per request rather than held as a field so provider /
     * credential changes take effect on the next translation without
     * requiring the repository to be rebuilt.
     */
    private suspend fun resolveTranslator(): Translator {
        val provider = tweaksRepository.getTranslationProvider().first()
        return when (provider) {
            TranslationProvider.GOOGLE -> googleTranslator
            TranslationProvider.YOUDAO -> {
                val appKey = tweaksRepository.getYoudaoAppKey().first()
                val appSecret = tweaksRepository.getYoudaoAppSecret().first()
                YoudaoTranslator(
                    httpClient = { httpClient },
                    json = json,
                    appKey = appKey,
                    appSecret = appSecret,
                )
            }
        }
    }

    private fun chunkText(text: String, maxChunkSize: Int): List<Pair<String, String>> {
        val paragraphs = text.split("\n\n")
        val chunks = mutableListOf<Pair<String, String>>()
        val currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.length > maxChunkSize) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(Pair(currentChunk.toString(), "\n\n"))
                    currentChunk.clear()
                }
                chunkLargeParagraph(paragraph, chunks, maxChunkSize)
            } else if (currentChunk.length + paragraph.length + 2 > maxChunkSize) {
                chunks.add(Pair(currentChunk.toString(), "\n\n"))
                currentChunk.clear()
                currentChunk.append(paragraph)
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(paragraph)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(Pair(currentChunk.toString(), "\n\n"))
        }

        return chunks
    }

    private fun chunkLargeParagraph(
        paragraph: String,
        chunks: MutableList<Pair<String, String>>,
        maxChunkSize: Int,
    ) {
        val lines = paragraph.split("\n")
        val currentChunk = StringBuilder()

        for (line in lines) {
            if (line.length > maxChunkSize) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(Pair(currentChunk.toString(), "\n"))
                    currentChunk.clear()
                }
                var start = 0
                while (start < line.length) {
                    val end = minOf(start + maxChunkSize, line.length)
                    chunks.add(Pair(line.substring(start, end), ""))
                    start = end
                }
            } else if (currentChunk.length + line.length + 1 > maxChunkSize) {
                chunks.add(Pair(currentChunk.toString(), "\n"))
                currentChunk.clear()
                currentChunk.append(line)
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n")
                currentChunk.append(line)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(Pair(currentChunk.toString(), "\n"))
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 50
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }

    @OptIn(ExperimentalTime::class)
    private class CachedTranslation(
        val result: TranslationResult,
        private val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        fun isExpired(): Boolean = Clock.System.now().toEpochMilliseconds() - timestamp > CACHE_TTL_MS
    }

    private data class CacheKey(
        val text: String,
        val targetLanguage: String,
        val sourceLanguage: String,
    )
}
