package zed.rainxch.details.data.translation

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.details.domain.model.TranslationResult

/**
 * Hits Youdao Translation Open API v3 (`openapi.youdao.com/api`).
 * Directly accessible from mainland China — the reason this provider
 * exists (see issue #429). Requires user-supplied `appKey`/`appSecret`
 * from Youdao's developer portal; missing credentials throw
 * [TranslationProviderNotConfiguredException] up to the UI.
 *
 * Signing: v3 uses
 *     sign = sha256(appKey + input + salt + curtime + appSecret)
 * where `input` is the query truncated to first-10 + length + last-10
 * for strings longer than 20 characters.
 * See https://ai.youdao.com/DOCSIRMA/html/trans/api/wbfy/index.html.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
internal class YoudaoTranslator(
    private val httpClient: () -> HttpClient,
    private val json: Json,
    private val appKey: String,
    private val appSecret: String,
) : Translator {
    // POST body — Youdao accepts up to 5000 chars per call. Leave a
    // little room for URL-encoding inflation.
    override val maxChunkSize: Int = 4500

    override suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
    ): TranslationResult {
        if (appKey.isBlank() || appSecret.isBlank()) {
            throw TranslationProviderNotConfiguredException(
                "Youdao appKey/appSecret not configured",
            )
        }

        val salt = Uuid.random().toString()
        val curtime = (Clock.System.now().toEpochMilliseconds() / 1000L).toString()
        val signInput = buildSignInput(text)
        val sign = sha256Hex(appKey + signInput + salt + curtime + appSecret)

        val from = mapLanguageCode(sourceLanguage)
        val to = mapLanguageCode(targetLanguage)

        val response =
            httpClient().submitForm(
                url = "https://openapi.youdao.com/api",
                formParameters =
                    Parameters.build {
                        append("q", text)
                        append("from", from)
                        append("to", to)
                        append("appKey", appKey)
                        append("salt", salt)
                        append("sign", sign)
                        append("signType", "v3")
                        append("curtime", curtime)
                    },
            )

        val body = response.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val errorCode = root["errorCode"]?.jsonPrimitive?.content
        if (errorCode != "0") {
            throw RuntimeException("Youdao translate error: $errorCode")
        }

        val translation =
            root["translation"]
                ?.jsonArray
                ?.joinToString("\n") { it.jsonPrimitive.content }
                .orEmpty()

        // `l` is "<from>2<to>" — e.g. "en2zh-CHS". First half is the
        // auto-detected source language when `from=auto` was requested.
        val detected =
            root["l"]
                ?.jsonPrimitive
                ?.content
                ?.substringBefore('2')
                ?.takeIf { it.isNotBlank() }
                ?.let { reverseMapLanguageCode(it) }

        return TranslationResult(
            translatedText = translation,
            detectedSourceLanguage = detected,
        )
    }

    private fun buildSignInput(q: String): String {
        // Youdao's documented truncation rule for the signed `input`:
        // if q.length > 20 use first 10 + q.length + last 10, else use q.
        return if (q.length <= 20) {
            q
        } else {
            q.substring(0, 10) + q.length.toString() + q.substring(q.length - 10)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val v = byte.toInt() and 0xff
                if (v < 0x10) append('0')
                append(v.toString(16))
            }
        }
    }

    /**
     * Translate Google-style BCP-47 codes (the rest of the app uses
     * these) to Youdao's expected language codes. Anything we don't
     * know passes through — if it's wrong Youdao will respond with
     * errorCode 102 and the caller surfaces the error.
     */
    private fun mapLanguageCode(code: String): String =
        when (code.lowercase()) {
            "auto", "" -> "auto"
            "zh", "zh-cn", "zh-hans" -> "zh-CHS"
            "zh-tw", "zh-hant" -> "zh-CHT"
            else -> code
        }

    private fun reverseMapLanguageCode(code: String): String =
        when (code) {
            "zh-CHS" -> "zh"
            "zh-CHT" -> "zh-TW"
            else -> code
        }
}
