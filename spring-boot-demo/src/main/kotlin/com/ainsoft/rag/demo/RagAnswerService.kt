package com.ainsoft.rag.demo

import com.ainsoft.rag.api.LlmProviderConfig
import com.ainsoft.rag.api.SearchResponse
import com.ainsoft.rag.api.TextGenerationProvider
import com.ainsoft.rag.api.TextGenerationProviderFactory
import com.ainsoft.rag.spring.LlmProperties
import com.ainsoft.rag.spring.RagProperties
import org.springframework.stereotype.Service

@Service
class RagAnswerService(
    private val properties: RagProperties,
    private val llmProperties: LlmProperties,
    private val textGenerationProviderFactory: TextGenerationProviderFactory
) {
    private data class ProviderSelection(
        val config: LlmProviderConfig,
        val provider: TextGenerationProvider
    )

    private val selectedProvider by lazy {
        resolveAnswerProviderConfig()?.let { config ->
            ProviderSelection(
                config = config,
                provider = textGenerationProviderFactory.create(config)
            )
        }
    }

    fun answer(request: AnswerApiRequest, searchResponse: SearchResponse): AnswerApiResponse {
        val selectedHits = searchResponse.hits.take(maxOf(1, request.topK))
        val citations = selectedHits.mapIndexed { index, hit ->
            AnswerCitation(
                index = index + 1,
                docId = hit.source.docId,
                chunkId = hit.source.chunkId,
                sourceUri = hit.source.sourceUri,
                score = hit.score,
                contentKind = hit.contentKind,
                sourceSnippet = compactText(hit.text.orEmpty(), 240)
            )
        }

        val context = buildContext(request.query, selectedHits)
        val generation = generateAnswer(
            question = request.query,
            context = context,
            citations = citations
        )
        val answerStructure = buildAnswerStructure(generation.answer, citations)

        return AnswerApiResponse(
            schemaVersion = "answer-v1",
            tenantId = request.tenantId,
            query = request.query,
            answer = answerStructure,
            citations = citations,
            meta = AnswerMetaResponse(
                retrievedCount = searchResponse.hits.size,
                selectedCount = selectedHits.size,
                answerMode = generation.mode,
                usedModel = generation.usedModel,
                fallbackApplied = generation.fallbackApplied,
                fallbackReason = generation.fallbackReason,
                queryRewriteApplied = searchResponse.telemetry.queryRewriteApplied,
                rerankerType = searchResponse.telemetry.rerankerType,
                summaryCandidatesUsed = searchResponse.telemetry.summaryCandidatesUsed,
                notes = searchResponse.telemetry.notes
            )
        )
    }

    private fun generateAnswer(
        question: String,
        context: String,
        citations: List<AnswerCitation>
    ): AnswerGenerationResult {
        val selection = selectedProvider
        if (selection == null) {
            return AnswerGenerationResult(
                answer = buildHeuristicAnswer(question, context, citations),
                mode = "heuristic",
                fallbackApplied = false,
                fallbackReason = null,
                usedModel = null
            )
        }

        return try {
            val answer = selection.provider.complete(
                systemPrompt = "You answer questions using only the provided context. If the context is insufficient, say so. Keep the answer concise and factual. Cite sources inline using square brackets like [1], [2] and only use numbers from the provided citations.",
                userPrompt = buildString {
                    appendLine("Question:")
                    appendLine(question)
                    appendLine()
                    appendLine("Output schema:")
                    appendLine("- Return plain text only.")
                    appendLine("- Put citation markers directly after supported sentences, for example: sentence [1].")
                    appendLine("- Keep one idea per sentence when possible.")
                    appendLine()
                    appendLine("Citations:")
                    citations.forEach { citation ->
                        appendLine("[${citation.index}] ${citation.docId} / ${citation.chunkId}")
                    }
                    appendLine()
                    appendLine("Context:")
                    appendLine(context)
                    appendLine()
                    appendLine("Instructions:")
                    appendLine("- Use only the context above.")
                    appendLine("- Mention uncertainty when needed.")
                    appendLine("- Use inline citations like [1], [2] next to the supported sentence.")
                    appendLine("- Do not invent citations that are not listed above.")
                }
            ).trim()

            if (answer.isBlank()) {
                AnswerGenerationResult(
                    answer = buildHeuristicAnswer(question, context, citations),
                    mode = "heuristic",
                    fallbackApplied = true,
                    fallbackReason = "empty_model_response",
                    usedModel = selection.config.model
                )
            } else {
                AnswerGenerationResult(
                    answer = answer,
                    mode = selection.config.kind,
                    fallbackApplied = false,
                    fallbackReason = null,
                    usedModel = selection.config.model
                )
            }
        } catch (ex: Exception) {
            AnswerGenerationResult(
                answer = buildHeuristicAnswer(question, context, citations),
                mode = "heuristic",
                fallbackApplied = true,
                fallbackReason = ex.message ?: ex::class.simpleName,
                usedModel = selection.config.model
            )
        }
    }

    private fun resolveAnswerProviderConfig(): LlmProviderConfig? {
        llmProperties.resolveSummarizer()?.let { return it }

        val legacyType = properties.summarizerType.trim().lowercase()
        if (legacyType != "openai-compatible" && legacyType != "openai") return null

        val apiKey = resolveApiKey(properties.summarizerApiKey, "OPENAI_API_KEY") ?: return null
        return LlmProviderConfig(
            kind = "openai-compatible",
            baseUrl = properties.summarizerApiBaseUrl,
            apiKey = apiKey,
            model = properties.summarizerModel,
            requestTimeoutMillis = properties.summarizerRequestTimeoutMillis
        )
    }

    private fun buildContext(question: String, hits: List<com.ainsoft.rag.api.SearchHit>): String {
        val contextBudget = 6_000
        val blockBudget = 1_800
        val builder = StringBuilder()
        for ((index, hit) in hits.withIndex()) {
            val block = buildString {
                appendLine("[${index + 1}] docId=${hit.source.docId}")
                appendLine("chunkId=${hit.source.chunkId}")
                appendLine("score=${"%.4f".format(hit.score)}")
                hit.source.sourceUri?.let { appendLine("sourceUri=$it") }
                if (hit.metadata.isNotEmpty()) {
                    appendLine("metadata=" + hit.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" })
                }
                appendLine("text=" + compactText(hit.text.orEmpty(), blockBudget))
            }
            if (builder.length + block.length > contextBudget) break
            builder.append(block)
            if (index < hits.lastIndex) builder.appendLine()
        }
        if (builder.isBlank()) {
            return "질문: $question\n근거: 없음"
        }
        return builder.toString()
    }

    private fun buildHeuristicAnswer(
        question: String,
        context: String,
        citations: List<AnswerCitation>
    ): String {
        val lines = context.lineSequence().toList()
        val snippets = lines.filter { it.startsWith("text=") }
            .map { it.removePrefix("text=").trim() }
            .filter { it.isNotBlank() }
            .take(3)
        val sentenceLines = snippets.mapIndexed { index, snippet ->
            val citationIndex = citations.getOrNull(index)?.index
            val marker = citationIndex?.let { " [$it]" }.orEmpty()
            "${snippet.take(500)}$marker"
        }

        return buildString {
            if (sentenceLines.isEmpty()) {
                append("근거가 충분하지 않아 답변할 수 없습니다.")
            } else {
                sentenceLines.forEachIndexed { index, sentence ->
                    if (index > 0) append(' ')
                    append(sentence.trimEnd().trimEnd('.', '!', '?'))
                    append('.')
                }
            }
        }.trim()
    }

    private fun buildAnswerStructure(answerText: String, citations: List<AnswerCitation>): AnswerBodyResponse {
        val sentences = splitSentences(answerText).mapIndexed { index, sentenceText ->
            val citationIndexes = extractCitationIndexes(sentenceText)
                .filter { citation -> citations.any { it.index == citation } }
            AnswerSentenceResponse(
                index = index + 1,
                text = normalizeAnswerSentence(sentenceText),
                citationIndexes = citationIndexes
            )
        }
        return AnswerBodyResponse(
            text = answerText.trim(),
            sentences = sentences
        )
    }

    private fun splitSentences(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        return normalized
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeAnswerSentence(sentence: String): String =
        sentence.trim().trimEnd('.', '!', '?')

    private fun extractCitationIndexes(sentence: String): List<Int> =
        Regex("\\[(\\d+)]").findAll(sentence).mapNotNull { matchResult ->
            matchResult.groupValues.getOrNull(1)?.toIntOrNull()
        }.distinct().toList()

    private fun compactText(text: String, maxChars: Int): String {
        if (text.isBlank()) return ""
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return normalized.take(maxChars)
    }

    private fun resolveApiKey(configuredKey: String?, envName: String): String? {
        return configuredKey?.takeIf { it.isNotBlank() }
            ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
    }
}

data class AnswerApiResponse(
    val schemaVersion: String,
    val tenantId: String,
    val query: String,
    val answer: AnswerBodyResponse,
    val citations: List<AnswerCitation>,
    val meta: AnswerMetaResponse
)

data class AnswerApiRequest(
    val tenantId: String,
    val principals: List<String>,
    val query: String,
    val topK: Int = 8,
    val filter: Map<String, String> = emptyMap()
)

data class AnswerCitation(
    val index: Int,
    val docId: String,
    val chunkId: String,
    val sourceUri: String?,
    val score: Double,
    val contentKind: String,
    val sourceSnippet: String?
)

data class AnswerBodyResponse(
    val text: String,
    val sentences: List<AnswerSentenceResponse>
)

data class AnswerSentenceResponse(
    val index: Int,
    val text: String,
    val citationIndexes: List<Int>
)

data class AnswerMetaResponse(
    val retrievedCount: Int,
    val selectedCount: Int,
    val answerMode: String,
    val usedModel: String?,
    val fallbackApplied: Boolean,
    val fallbackReason: String?,
    val queryRewriteApplied: Boolean,
    val rerankerType: String?,
    val summaryCandidatesUsed: Boolean,
    val notes: List<String>
)

private data class AnswerGenerationResult(
    val answer: String,
    val mode: String,
    val fallbackApplied: Boolean,
    val fallbackReason: String?,
    val usedModel: String?
)
