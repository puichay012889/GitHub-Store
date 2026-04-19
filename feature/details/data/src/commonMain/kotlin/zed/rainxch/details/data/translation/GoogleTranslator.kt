package zed.rainxch.details.data.translation

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.details.domain.model.TranslationResult

/**
 * Hits Google's undocumented `translate_a/single` endpoint. Works
 * everywhere Google does, no credentials. May rate-limit or break
 * without notice; Youdao is the escape hatch.
 *
 * Uses POST (form-encoded body) instead of GET: the repository chunks
 * on `String.length`, but URL encoding a non-ASCII character — CJK,
 * Arabic, etc. — expands ~3× for UTF-8 and ×3 again for percent
 * encoding (roughly 9×). A 4500-char CJK chunk would produce a ~40 KB
 * URL, well past most HTTP stacks' ~8 KB cap. POST bodies have no
 * such limit.
 */
internal class GoogleTranslator(
    private val httpClient: () -> HttpClient,
    private val json: Json,
) : Translator {
    // POST body — bounded by server-side payload limits, not URL
    // length. 4500 chars stays well within Google's accepted payload
    // even for maximum-expansion CJK text.
    override val maxChunkSize: Int = 4500

    override suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
    ): TranslationResult {
        val body =
            httpClient()
                .submitForm(
                    url = "https://translate.googleapis.com/translate_a/single",
                    formParameters =
                        Parameters.build {
                            append("client", "gtx")
                            append("sl", sourceLanguage)
                            append("tl", targetLanguage)
                            append("dt", "t")
                            append("q", text)
                        },
                ).bodyAsText()

        val root = json.parseToJsonElement(body).jsonArray
        val segments = root[0].jsonArray
        val translated =
            segments.joinToString("") { segment ->
                segment.jsonArray[0].jsonPrimitive.content
            }
        val detected =
            runCatching { root[2].jsonPrimitive.content }.getOrNull()

        return TranslationResult(
            translatedText = translated,
            detectedSourceLanguage = detected,
        )
    }
}
