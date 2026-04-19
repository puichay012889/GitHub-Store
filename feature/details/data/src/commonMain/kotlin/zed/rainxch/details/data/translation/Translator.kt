package zed.rainxch.details.data.translation

import zed.rainxch.details.domain.model.TranslationResult

/**
 * Single-shot translator for a chunk of already-sized text. Chunking,
 * caching and result-joining stay in the repository layer so each
 * provider implementation only has to answer the question "translate
 * this string and tell me what language it was in."
 */
internal interface Translator {
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
    ): TranslationResult

    /**
     * Rough per-chunk upper bound for [text] length, in characters.
     * Used by the repository's chunker to avoid tripping provider
     * limits (Google's GET query length, Youdao's POST body length).
     */
    val maxChunkSize: Int
}

/**
 * Raised when the selected provider isn't configured (e.g. Youdao
 * selected but `appKey` missing). UI surfaces this as "provider not
 * configured" rather than a generic network error.
 */
internal class TranslationProviderNotConfiguredException(message: String) : RuntimeException(message)
