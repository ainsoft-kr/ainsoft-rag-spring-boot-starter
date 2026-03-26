package com.ainsoft.rag.demo

import com.ainsoft.rag.api.Acl
import com.ainsoft.rag.api.PageMarker
import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchRequest
import com.ainsoft.rag.api.SearchDiagnostics
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.embeddings.EmbeddingProvider
import com.ainsoft.rag.spring.RagAdminService
import com.ainsoft.rag.spring.RagAdminWebIngestRequest
import com.ainsoft.rag.spring.RagAdminWebIngestResponse
import com.ainsoft.rag.parsers.PlainTextParser
import com.ainsoft.rag.parsers.TikaDocumentParser
import com.ainsoft.rag.spring.RagProperties
import com.ainsoft.rag.impl.providerTelemetrySnapshot
import com.ainsoft.rag.support.SourceLoadOptions
import com.ainsoft.rag.support.SourceLoaders
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executor

@RestController
@RequestMapping("/api/rag")
class RagController(
    private val engine: RagEngine,
    private val properties: RagProperties,
    private val ragConfig: RagConfig,
    private val embeddingProvider: EmbeddingProvider,
    private val ragAnswerService: RagAnswerService,
    private val adminService: RagAdminService,
    private val streamExecutor: Executor
) {
    private val objectMapper = ObjectMapper()
    private val plainTextParser = PlainTextParser()
    private val tikaDocumentParser = TikaDocumentParser()

    @PostMapping("/ingest")
    fun ingest(@RequestBody request: IngestRequest): IngestResponse {
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        require(request.text.isNotBlank()) { "text must not be blank" }
        val normalizedTenantId = normalizeIdentifier(request.tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(request.docId, "docId")
        engine.upsert(
            UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = request.text,
                metadata = request.metadata,
                acl = Acl(request.acl),
                sourceUri = request.sourceUri,
                page = request.page,
                pageMarkers = request.pageMarkers.orEmpty().mapNotNull {
                    val page = it.page ?: return@mapNotNull null
                    val offsetStart = it.offsetStart ?: return@mapNotNull null
                    val offsetEnd = it.offsetEnd ?: return@mapNotNull null
                    PageMarker(
                        page = page,
                        offsetStart = offsetStart,
                        offsetEnd = offsetEnd
                    )
                }
            )
        )
        return IngestResponse(
            status = "ingested",
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            metadata = request.metadata,
            sourceUri = request.sourceUri,
            page = request.page
        )
    }

    @PostMapping(
        "/ingest-file",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun ingestFile(
        @RequestParam tenantId: String,
        @RequestParam docId: String,
        @RequestParam acl: List<String>,
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) sourceUri: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false, defaultValue = "UTF-8") charset: String,
        @RequestParam(required = false) metadata: String?
    ): IngestResponse {
        val normalizedTenantId = normalizeIdentifier(tenantId, "tenantId")
        val normalizedDocId = normalizeIdentifier(docId, "docId")
        require(acl.isNotEmpty()) { "acl must not be empty" }
        require(!file.isEmpty) { "file must not be empty" }
        require(file.size <= properties.uploadMaxBytes) {
            "file exceeds uploadMaxBytes=${properties.uploadMaxBytes}"
        }
        val contentType = file.contentType?.trim().orEmpty()
        require(
            contentType.isBlank() || properties.uploadAllowedContentTypes.isEmpty() || contentType in properties.uploadAllowedContentTypes
        ) {
            "unsupported contentType='$contentType'"
        }
        val normalizedFilename = normalizeFilename(file.originalFilename)
        val effectiveSourceUri = sourceUri ?: normalizedFilename?.let { "upload://$it" }
        val parsed = if (isBinaryDoc(file.originalFilename)) {
            tikaDocumentParser.parseBytes(file.bytes, sourceUri = effectiveSourceUri, page = page)
        } else {
            val text = file.bytes.toString(Charset.forName(charset))
            plainTextParser.parseText(text, sourceUri = effectiveSourceUri, page = page)
        }

        engine.upsert(
            UpsertDocumentRequest(
                tenantId = normalizedTenantId,
                docId = normalizedDocId,
                normalizedText = parsed.normalizedText,
                metadata = parseMetadata(metadata),
                acl = Acl(acl),
                sourceUri = parsed.sourceUri,
                page = parsed.page,
                pageMarkers = parsed.pageMarkers
            )
        )
        return IngestResponse(
            status = "ingested",
            tenantId = normalizedTenantId,
            docId = normalizedDocId,
            sourceUri = parsed.sourceUri,
            fileName = normalizedFilename ?: file.originalFilename,
            contentType = contentType,
            page = parsed.page,
            metadata = parseMetadata(metadata)
        )
    }

    @PostMapping("/site-ingest")
    fun siteIngest(@RequestBody request: RagAdminWebIngestRequest): RagAdminWebIngestResponse {
        require(request.urls.isNotEmpty()) { "urls must not be empty" }
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        return adminService.webIngest(role = "DEMO", request = request)
    }

    @PostMapping(
        "/site-ingest/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun siteIngestStream(@RequestBody request: RagAdminWebIngestRequest): ResponseEntity<StreamingResponseBody> {
        require(request.urls.isNotEmpty()) { "urls must not be empty" }
        require(request.acl.isNotEmpty()) { "acl must not be empty" }
        val body = StreamingResponseBody { outputStream ->
            outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                fun writeEvent(name: String, payload: Any) {
                    writer.write("event: ")
                    writer.write(name)
                    writer.write("\n")
                    writer.write("data: ")
                    writer.write(objectMapper.writeValueAsString(payload))
                    writer.write("\n\n")
                    writer.flush()
                }

                try {
                    val effectiveMaxPages = (request.maxPages ?: 25).coerceAtLeast(1)
                    val effectiveMaxDepth = (request.maxDepth ?: 1).coerceAtLeast(0)
                    writeEvent(
                        "meta",
                        mapOf(
                            "message" to "starting site ingest",
                            "tenantId" to request.tenantId,
                            "urlCount" to request.urls.size,
                            "maxPages" to effectiveMaxPages,
                            "maxDepth" to effectiveMaxDepth,
                            "incrementalIngest" to request.incrementalIngest
                        )
                    )
                    val response = adminService.webIngest(
                        role = "DEMO",
                        request = request,
                        progressSink = { event ->
                            writeEvent("progress", event)
                        }
                    )
                    writeEvent("result", response)
                    writeEvent("done", mapOf("status" to response.status))
                } catch (_: IOException) {
                    // client disconnected; nothing to do
                } catch (error: Exception) {
                    runCatching {
                        writeEvent(
                            "error",
                            mapOf(
                                "message" to (error.message ?: "site ingest failed"),
                                "type" to error::class.java.simpleName
                            )
                        )
                    }
                }
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body)
    }

    @PostMapping("/search")
    fun search(@RequestBody request: SearchApiRequest): SearchApiResponse {
        require(request.principals.isNotEmpty()) { "principals must not be empty" }
        require(request.query.isNotBlank()) { "query must not be blank" }
        val effectiveTopK = request.topK?.coerceAtLeast(1) ?: 8
        val response = engine.searchDetailed(
            SearchRequest(
                tenantId = request.tenantId,
                principals = request.principals,
                query = request.query,
                topK = effectiveTopK,
                filter = request.filter
            )
        )
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = request.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        val hitResponses = response.hits.map { hit ->
            val snippetResult = if (request.openSource) {
                loadSourceSnippet(
                    sourceUri = hit.source.sourceUri,
                    offsetStart = hit.source.offsetStart,
                    offsetEndExclusive = hit.source.offsetEnd,
                    context = request.snippetContext ?: 160,
                    charsetName = request.sourceCharset ?: "UTF-8",
                    profileName = request.sourceLoadProfile
                )
            } else {
                SnippetResult(status = "DISABLED", snippet = null, detail = null)
            }
            SearchHitResponse(
                docId = hit.source.docId,
                chunkId = hit.source.chunkId,
                score = hit.score,
                text = hit.text,
                contentKind = hit.contentKind,
                page = hit.source.page,
                sourceUri = hit.source.sourceUri,
                offsetStart = hit.source.offsetStart,
                offsetEnd = hit.source.offsetEnd,
                sourceSnippet = snippetResult.snippet,
                snippetStatus = snippetResult.status,
                snippetDetail = snippetResult.detail,
                metadata = hit.metadata
            )
        }
        val snippetStatusCounts = hitResponses.groupingBy { it.snippetStatus }.eachCount()
        return SearchApiResponse(
            tenantId = request.tenantId,
            query = request.query,
            hits = hitResponses,
            meta = SearchMetaResponse(
                resultCount = hitResponses.size,
                requestedTopK = effectiveTopK,
                principalCount = request.principals.size,
                aclApplied = true,
                filterApplied = request.filter.isNotEmpty(),
                openSourceRequested = request.openSource,
                sourceLoadProfile = request.sourceLoadProfile ?: properties.sourceLoadDefaultProfile,
                emptyReason = if (hitResponses.isEmpty()) computeEmptyReason(request) else null,
                snippetAvailableCount = hitResponses.count { it.sourceSnippet != null },
                snippetStatusCounts = snippetStatusCounts,
                executedQuery = response.telemetry.executedQuery,
                queryRewriteApplied = response.telemetry.queryRewriteApplied,
                queryRewriterType = response.telemetry.queryRewriterType,
                correctiveRetryApplied = response.telemetry.correctiveRetryApplied,
                initialConfidence = response.telemetry.initialConfidence,
                finalConfidence = response.telemetry.finalConfidence,
                rerankerType = response.telemetry.rerankerType,
                summaryCandidatesUsed = response.telemetry.summaryCandidatesUsed,
                providerFallbackApplied = response.telemetry.providerFallbackApplied,
                providerFallbackReason = response.telemetry.providerFallbackReason,
                providersUsed = response.telemetry.providersUsed,
                notes = response.telemetry.notes,
                providerHealthSummary = globalProviderHealth.toResponse(request.providerHealthDetail),
                recentProviderWindowMillis = request.recentProviderWindowMillis,
                recentProviderHealthSummary = recentProviderHealth?.toResponse(request.providerHealthDetail),
                providerTelemetryDelta = recentProviderHealth?.let { providerHealthDelta(globalProviderHealth, it) },
                providerEndpointDeltas = recentProviderHealth?.let { providerEndpointDeltas(globalProviderHealth, it) }.orEmpty(),
                providerTenantScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("tenant", globalProviderHealth.tenantScopes, it.tenantScopes) }.orEmpty(),
                providerCommandScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("command", globalProviderHealth.commandScopes, it.commandScopes) }.orEmpty()
            )
        )
    }

    @PostMapping("/answer")
    fun answer(@RequestBody request: AnswerApiRequest): AnswerApiResponse {
        require(request.principals.isNotEmpty()) { "principals must not be empty" }
        require(request.query.isNotBlank()) { "query must not be blank" }
        val effectiveTopK = request.topK?.coerceAtLeast(1) ?: 8
        val searchResponse = engine.searchDetailed(
            SearchRequest(
                tenantId = request.tenantId,
                principals = request.principals,
                query = request.query,
                topK = effectiveTopK,
                filter = request.filter
            )
        )
        return ragAnswerService.answer(request, searchResponse)
    }

    @PostMapping(
        "/answer/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun answerStream(@RequestBody request: AnswerApiRequest): SseEmitter {
        val emitter = SseEmitter(0L)
        streamExecutor.execute {
            try {
                val effectiveTopK = request.topK?.coerceAtLeast(1) ?: 8
                val searchResponse = engine.searchDetailed(
                    SearchRequest(
                        tenantId = request.tenantId,
                        principals = request.principals,
                        query = request.query,
                        topK = effectiveTopK,
                        filter = request.filter
                    )
                )
                val answer = ragAnswerService.answer(request, searchResponse)

                emitter.send(
                    SseEmitter.event()
                        .name("meta")
                        .data(
                            mapOf(
                                "schemaVersion" to answer.schemaVersion,
                                "tenantId" to answer.tenantId,
                                "query" to answer.query,
                                "meta" to answer.meta
                            )
                        )
                )
                answer.citations.forEach { citation ->
                    emitter.send(
                        SseEmitter.event()
                            .name("citation")
                            .data(citation)
                    )
                }
                answer.answer.sentences.forEach { sentence ->
                    emitter.send(
                        SseEmitter.event()
                            .name("sentence")
                            .data(sentence)
                    )
                }
                emitter.send(
                    SseEmitter.event()
                        .name("done")
                        .data(mapOf("status" to "done"))
                )
                emitter.complete()
            } catch (ex: Exception) {
                runCatching {
                    emitter.send(
                        SseEmitter.event()
                            .name("error")
                            .data(
                                mapOf(
                                    "message" to (ex.message ?: "answer stream failed"),
                                    "type" to ex::class.java.simpleName
                                )
                            )
                    )
                }
                emitter.complete()
            }
        }
        return emitter
    }

    @PostMapping("/diagnose-search")
    fun diagnoseSearch(@RequestBody request: SearchApiRequest): SearchDiagnosticsApiResponse {
        require(request.principals.isNotEmpty()) { "principals must not be empty" }
        require(request.query.isNotBlank()) { "query must not be blank" }
        val effectiveTopK = request.topK?.coerceAtLeast(1) ?: 8
        val searchRequest = SearchRequest(
            tenantId = request.tenantId,
            principals = request.principals,
            query = request.query,
            topK = effectiveTopK,
            filter = request.filter
        )
        val diagnostics = SearchDiagnostics.analyze(
            indexPath = ragConfig.indexPath,
            embeddingProvider = embeddingProvider,
            request = searchRequest,
            maxSamples = request.diagnosticMaxSamples ?: 5,
            scoreThreshold = request.diagnosticScoreThreshold ?: Double.NEGATIVE_INFINITY
        )
        val search = engine.searchDetailed(searchRequest)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = request.recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return SearchDiagnosticsApiResponse(
            tenantId = request.tenantId,
            query = request.query,
            derivedEmptyReason = computeEmptyReason(request),
            tenantDocs = diagnostics.tenantDocs,
            lexicalMatchesWithoutAcl = diagnostics.lexicalMatchesWithoutAcl,
            vectorMatchesWithoutAcl = diagnostics.vectorMatchesWithoutAcl,
            lexicalMatchesWithAcl = diagnostics.lexicalMatchesWithAcl,
            vectorMatchesWithAcl = diagnostics.vectorMatchesWithAcl,
            lexicalSampleDocIdsWithoutAcl = diagnostics.lexicalSampleDocIdsWithoutAcl,
            vectorSampleDocIdsWithoutAcl = diagnostics.vectorSampleDocIdsWithoutAcl,
            lexicalSampleDocIdsWithAcl = diagnostics.lexicalSampleDocIdsWithAcl,
            vectorSampleDocIdsWithAcl = diagnostics.vectorSampleDocIdsWithAcl,
            executedQuery = search.telemetry.executedQuery,
            queryRewriteApplied = search.telemetry.queryRewriteApplied,
            queryRewriterType = search.telemetry.queryRewriterType,
            correctiveRetryApplied = search.telemetry.correctiveRetryApplied,
            initialConfidence = search.telemetry.initialConfidence,
            finalConfidence = search.telemetry.finalConfidence,
            rerankerType = search.telemetry.rerankerType,
            summaryCandidatesUsed = search.telemetry.summaryCandidatesUsed,
            providerFallbackApplied = search.telemetry.providerFallbackApplied,
            providerFallbackReason = search.telemetry.providerFallbackReason,
            providersUsed = search.telemetry.providersUsed,
            notes = search.telemetry.notes,
            providerHealthSummary = globalProviderHealth.toResponse(request.providerHealthDetail),
            recentProviderWindowMillis = request.recentProviderWindowMillis,
            recentProviderHealthSummary = recentProviderHealth?.toResponse(request.providerHealthDetail),
            providerTelemetryDelta = recentProviderHealth?.let { providerHealthDelta(globalProviderHealth, it) },
            providerEndpointDeltas = recentProviderHealth?.let { providerEndpointDeltas(globalProviderHealth, it) }.orEmpty(),
            providerTenantScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("tenant", globalProviderHealth.tenantScopes, it.tenantScopes) }.orEmpty(),
            providerCommandScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("command", globalProviderHealth.commandScopes, it.commandScopes) }.orEmpty()
        )
    }

    @GetMapping("/stats")
    fun stats(
        @RequestParam tenantId: String?,
        @RequestParam(required = false) recentProviderWindowMillis: Long?
    ): StatsResponse {
        val stats = engine.stats(tenantId)
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return StatsResponse(
            tenantId = stats.tenantId,
            docs = stats.docs,
            chunks = stats.chunks,
            snapshotCount = stats.snapshotCount,
            indexSizeBytes = stats.indexSizeBytes,
            lastCommitEpochMillis = stats.lastCommitEpochMillis,
            lastCommitIso = stats.lastCommitEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
            statsCacheEntries = stats.statsCacheEntries,
            statsCacheHitCount = stats.statsCacheHitCount,
            statsCacheMissCount = stats.statsCacheMissCount,
            statsCacheEvictionCount = stats.statsCacheEvictionCount,
            statsCacheExpiredCount = stats.statsCacheExpiredCount,
            statsCacheHitRatePct = formatHitRate(stats.statsCacheHitCount, stats.statsCacheMissCount),
            statsCacheTtlMillis = stats.statsCacheTtlMillis,
            statsCacheMaxEntries = stats.statsCacheMaxEntries,
            statsCacheMaxEntriesPerTenant = stats.statsCacheMaxEntriesPerTenant,
            statsCachePersistenceMode = stats.statsCachePersistenceMode,
            providerTelemetry = globalProviderHealth.toResponse(),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(),
            providerTelemetryDelta = recentProviderHealth?.let { providerHealthDelta(globalProviderHealth, it) },
            providerEndpointDeltas = recentProviderHealth?.let { providerEndpointDeltas(globalProviderHealth, it) }.orEmpty(),
            providerTenantScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("tenant", globalProviderHealth.tenantScopes, it.tenantScopes) }.orEmpty(),
            providerCommandScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("command", globalProviderHealth.commandScopes, it.commandScopes) }.orEmpty()
        )
    }

    @GetMapping("/provider-health")
    fun providerHealth(
        @RequestParam(required = false) recentProviderWindowMillis: Long?,
        @RequestParam(required = false, defaultValue = "true") detailed: Boolean
    ): ProviderHealthApiResponse {
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return ProviderHealthApiResponse(
            providerTelemetry = globalProviderHealth.toResponse(detailed),
            recentProviderWindowMillis = recentProviderWindowMillis,
            recentProviderTelemetry = recentProviderHealth?.toResponse(detailed),
            providerTelemetryDelta = recentProviderHealth?.let { providerHealthDelta(globalProviderHealth, it) },
            providerEndpointDeltas = recentProviderHealth?.let { providerEndpointDeltas(globalProviderHealth, it) }.orEmpty(),
            providerTenantScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("tenant", globalProviderHealth.tenantScopes, it.tenantScopes) }.orEmpty(),
            providerCommandScopeDeltas = recentProviderHealth?.let { providerScopeDeltas("command", globalProviderHealth.commandScopes, it.commandScopes) }.orEmpty()
        )
    }

    @GetMapping("/observability/provider-health")
    fun providerHealthObservability(
        @RequestParam(required = false) recentProviderWindowMillis: Long?,
        @RequestParam(required = false, defaultValue = "true") detailed: Boolean
    ): ProviderHealthObservabilityResponse {
        val globalProviderHealth = providerTelemetrySnapshot()
        val recentProviderHealth = recentProviderWindowMillis?.let { providerTelemetrySnapshot(it) }
        return ProviderHealthObservabilityResponse(
            generatedAtEpochMillis = System.currentTimeMillis(),
            global = ProviderHealthSnapshotSection(
                windowMillis = null,
                telemetry = globalProviderHealth.toResponse(detailed)
            ),
            recent = recentProviderHealth?.let {
                ProviderHealthSnapshotSection(
                    windowMillis = recentProviderWindowMillis,
                    telemetry = it.toResponse(detailed)
                )
            },
            delta = recentProviderHealth?.let {
                ProviderHealthDeltaSection(
                    overall = providerHealthDelta(globalProviderHealth, it),
                    endpoints = providerEndpointDeltas(globalProviderHealth, it),
                    tenantScopes = providerScopeDeltas("tenant", globalProviderHealth.tenantScopes, it.tenantScopes),
                    commandScopes = providerScopeDeltas("command", globalProviderHealth.commandScopes, it.commandScopes)
                )
            }
        )
    }

    private fun parseMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.length - 1) {
                    null
                } else {
                    entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
                }
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
    }

    private fun isBinaryDoc(filename: String?): Boolean {
        val name = filename?.lowercase() ?: return false
        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".pptx")
    }

    private fun normalizeIdentifier(raw: String, fieldName: String): String {
        val normalized = raw.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._:-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        require(normalized.isNotBlank()) { "$fieldName must not be blank after normalization" }
        return normalized
    }

    private fun normalizeFilename(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun loadSourceSnippet(
        sourceUri: String?,
        offsetStart: Int?,
        offsetEndExclusive: Int?,
        context: Int,
        charsetName: String,
        profileName: String?
    ): SnippetResult {
        if (sourceUri.isNullOrBlank()) {
            return SnippetResult("MISSING_SOURCE_URI", null, SnippetDetailResponse("MISSING_SOURCE_URI", "sourceUri is missing", false))
        }
        if (offsetStart == null || offsetEndExclusive == null) {
            return SnippetResult("MISSING_OFFSETS", null, SnippetDetailResponse("MISSING_OFFSETS", "offsetStart/offsetEnd are missing", false))
        }
        if (offsetStart < 0 || offsetEndExclusive <= offsetStart) {
            return SnippetResult("INVALID_OFFSETS", null, SnippetDetailResponse("INVALID_OFFSETS", "offset range is invalid", false))
        }

        val resolvedProfile = properties.resolveSourceLoadProfile(profileName)

        val text = runCatching {
            SourceLoaders.loadTextFromUri(
                uriValue = sourceUri,
                charset = Charset.forName(charsetName),
                options = SourceLoadOptions(
                    authHeaders = resolvedProfile.authHeaders ?: emptyMap(),
                    timeout = java.time.Duration.ofMillis(resolvedProfile.timeoutMillis ?: properties.sourceLoadTimeoutMillis),
                    allowedHosts = resolvedProfile.allowHosts?.toSet() ?: emptySet(),
                    insecureSkipTlsVerify = resolvedProfile.insecureSkipTlsVerify ?: false,
                    customCaCertPath = resolvedProfile.customCaCertPath
                )
            )
        }.getOrElse { ex ->
            return SnippetResult(
                "LOAD_FAILED",
                null,
                SnippetDetailResponse(
                    code = "LOAD_FAILED",
                    message = ex.message ?: ex::class.simpleName ?: "source load failed",
                    retryable = true
                )
            )
        } ?: return SnippetResult(
            "LOAD_BLOCKED_OR_UNAVAILABLE",
            null,
            SnippetDetailResponse("LOAD_BLOCKED_OR_UNAVAILABLE", "source load was blocked or no content was returned", true)
        )

        val start = offsetStart.coerceIn(0, text.length)
        val end = offsetEndExclusive.coerceIn(0, text.length)
        if (end <= start) {
            return SnippetResult("OUT_OF_RANGE", null, SnippetDetailResponse("OUT_OF_RANGE", "offset range falls outside the loaded source", false))
        }

        val left = (start - context).coerceAtLeast(0)
        val right = (end + context).coerceAtMost(text.length)
        val prefix = if (left > 0) "..." else ""
        val suffix = if (right < text.length) "..." else ""
        val before = text.substring(left, start)
        val mid = text.substring(start, end)
        val after = text.substring(end, right)
        return SnippetResult("LOADED", prefix + before + "[[[" + mid + "]]]" + after + suffix, null)
    }

    private fun computeEmptyReason(request: SearchApiRequest): String {
        val effectiveTopK = request.topK?.coerceAtLeast(1) ?: 8
        val diagnostics = SearchDiagnostics.analyze(
            indexPath = ragConfig.indexPath,
            embeddingProvider = embeddingProvider,
            request = SearchRequest(
                tenantId = request.tenantId,
                principals = request.principals,
                query = request.query,
                topK = effectiveTopK,
                filter = request.filter
            )
        )
        if (diagnostics.tenantDocs == 0) return "TENANT_EMPTY"
        if (request.filter.isNotEmpty() && diagnostics.lexicalMatchesWithAcl == 0 && diagnostics.vectorMatchesWithAcl == 0) {
            val unfiltered = SearchDiagnostics.analyze(
                indexPath = ragConfig.indexPath,
                embeddingProvider = embeddingProvider,
                request = SearchRequest(
                    tenantId = request.tenantId,
                    principals = request.principals,
                    query = request.query,
                    topK = effectiveTopK,
                    filter = emptyMap()
                )
            )
            if (unfiltered.lexicalMatchesWithAcl > 0 || unfiltered.vectorMatchesWithAcl > 0) return "FILTER_MISMATCH"
        }
        if ((diagnostics.lexicalMatchesWithoutAcl > 0 || diagnostics.vectorMatchesWithoutAcl > 0) &&
            diagnostics.lexicalMatchesWithAcl == 0 && diagnostics.vectorMatchesWithAcl == 0
        ) {
            return "ACL_FILTERED"
        }
        if (diagnostics.lexicalMatchesWithoutAcl > 0 && diagnostics.vectorMatchesWithoutAcl == 0) {
            return "NO_VECTOR_MATCH"
        }
        if (diagnostics.vectorMatchesWithoutAcl > 0 && diagnostics.lexicalMatchesWithoutAcl == 0) {
            return "NO_LEXICAL_MATCH"
        }
        return "NO_MATCH"
    }

    private fun formatHitRate(hitCount: Long, missCount: Long): Double {
        val total = hitCount + missCount
        if (total == 0L) return 0.0
        return (hitCount.toDouble() * 100.0 / total.toDouble())
    }
}

data class IngestRequest(
    val tenantId: String,
    val docId: String,
    val text: String,
    val acl: List<String>,
    val metadata: Map<String, String> = emptyMap(),
    val sourceUri: String? = null,
    val page: Int? = null,
    val pageMarkers: List<PageMarkerRequest>? = null
)

data class PageMarkerRequest(
    val page: Int? = null,
    val offsetStart: Int? = null,
    val offsetEnd: Int? = null
)

data class IngestResponse(
    val status: String,
    val tenantId: String,
    val docId: String,
    val message: String? = null,
    val previousPreview: String? = null,
    val currentPreview: String? = null,
    val changeSummary: String? = null,
    val sourceUri: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val page: Int? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class SearchApiRequest(
    val tenantId: String,
    val principals: List<String>,
    val query: String,
    val topK: Int? = null,
    val filter: Map<String, String> = emptyMap(),
    val openSource: Boolean = false,
    val snippetContext: Int? = null,
    val sourceCharset: String? = null,
    val sourceLoadProfile: String? = null,
    val providerHealthDetail: Boolean = false,
    val recentProviderWindowMillis: Long? = null,
    val diagnosticScoreThreshold: Double? = null,
    val diagnosticMaxSamples: Int? = null
)

data class SearchApiResponse(
    val tenantId: String,
    val query: String,
    val hits: List<SearchHitResponse>,
    val meta: SearchMetaResponse
)

data class SearchDiagnosticsApiResponse(
    val tenantId: String,
    val query: String,
    val derivedEmptyReason: String,
    val tenantDocs: Int,
    val lexicalMatchesWithoutAcl: Int,
    val vectorMatchesWithoutAcl: Int,
    val lexicalMatchesWithAcl: Int,
    val vectorMatchesWithAcl: Int,
    val lexicalSampleDocIdsWithoutAcl: List<String>,
    val vectorSampleDocIdsWithoutAcl: List<String>,
    val lexicalSampleDocIdsWithAcl: List<String>,
    val vectorSampleDocIdsWithAcl: List<String>,
    val executedQuery: String,
    val queryRewriteApplied: Boolean,
    val queryRewriterType: String?,
    val correctiveRetryApplied: Boolean,
    val initialConfidence: Double?,
    val finalConfidence: Double?,
    val rerankerType: String?,
    val summaryCandidatesUsed: Boolean,
    val providerFallbackApplied: Boolean,
    val providerFallbackReason: String?,
    val providersUsed: List<String>,
    val notes: List<String>,
    val providerHealthSummary: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderHealthSummary: ProviderTelemetryResponse? = null,
    val providerTelemetryDelta: ProviderTelemetryDeltaResponse? = null,
    val providerEndpointDeltas: List<ProviderEndpointDeltaResponse> = emptyList(),
    val providerTenantScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList(),
    val providerCommandScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList()
)

data class SearchHitResponse(
    val docId: String,
    val chunkId: String,
    val score: Double,
    val text: String?,
    val contentKind: String,
    val page: Int?,
    val sourceUri: String?,
    val offsetStart: Int?,
    val offsetEnd: Int?,
    val sourceSnippet: String?,
    val snippetStatus: String,
    val snippetDetail: SnippetDetailResponse?,
    val metadata: Map<String, String>
)

data class SearchMetaResponse(
    val resultCount: Int,
    val requestedTopK: Int,
    val principalCount: Int,
    val aclApplied: Boolean,
    val filterApplied: Boolean,
    val openSourceRequested: Boolean,
    val sourceLoadProfile: String?,
    val emptyReason: String?,
    val snippetAvailableCount: Int,
    val snippetStatusCounts: Map<String, Int>,
    val executedQuery: String,
    val queryRewriteApplied: Boolean,
    val queryRewriterType: String?,
    val correctiveRetryApplied: Boolean,
    val initialConfidence: Double?,
    val finalConfidence: Double?,
    val rerankerType: String?,
    val summaryCandidatesUsed: Boolean,
    val providerFallbackApplied: Boolean,
    val providerFallbackReason: String?,
    val providersUsed: List<String>,
    val notes: List<String>,
    val providerHealthSummary: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderHealthSummary: ProviderTelemetryResponse? = null,
    val providerTelemetryDelta: ProviderTelemetryDeltaResponse? = null,
    val providerEndpointDeltas: List<ProviderEndpointDeltaResponse> = emptyList(),
    val providerTenantScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList(),
    val providerCommandScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList()
)

data class StatsResponse(
    val tenantId: String?,
    val docs: Long,
    val chunks: Long,
    val snapshotCount: Int,
    val indexSizeBytes: Long,
    val lastCommitEpochMillis: Long?,
    val lastCommitIso: String?,
    val statsCacheEntries: Int,
    val statsCacheHitCount: Long,
    val statsCacheMissCount: Long,
    val statsCacheEvictionCount: Long,
    val statsCacheExpiredCount: Long,
    val statsCacheHitRatePct: Double,
    val statsCacheTtlMillis: Long,
    val statsCacheMaxEntries: Int,
    val statsCacheMaxEntriesPerTenant: Int,
    val statsCachePersistenceMode: String,
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null,
    val providerTelemetryDelta: ProviderTelemetryDeltaResponse? = null,
    val providerEndpointDeltas: List<ProviderEndpointDeltaResponse> = emptyList(),
    val providerTenantScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList(),
    val providerCommandScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList()
)

data class ProviderHealthApiResponse(
    val providerTelemetry: ProviderTelemetryResponse,
    val recentProviderWindowMillis: Long? = null,
    val recentProviderTelemetry: ProviderTelemetryResponse? = null,
    val providerTelemetryDelta: ProviderTelemetryDeltaResponse? = null,
    val providerEndpointDeltas: List<ProviderEndpointDeltaResponse> = emptyList(),
    val providerTenantScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList(),
    val providerCommandScopeDeltas: List<ProviderScopeDeltaResponse> = emptyList()
)

data class ProviderHealthObservabilityResponse(
    val generatedAtEpochMillis: Long,
    val global: ProviderHealthSnapshotSection,
    val recent: ProviderHealthSnapshotSection? = null,
    val delta: ProviderHealthDeltaSection? = null
)

data class ProviderHealthSnapshotSection(
    val windowMillis: Long? = null,
    val telemetry: ProviderTelemetryResponse
)

data class ProviderHealthDeltaSection(
    val overall: ProviderTelemetryDeltaResponse,
    val endpoints: List<ProviderEndpointDeltaResponse> = emptyList(),
    val tenantScopes: List<ProviderScopeDeltaResponse> = emptyList(),
    val commandScopes: List<ProviderScopeDeltaResponse> = emptyList()
)

data class ProviderTelemetryDeltaResponse(
    val requestDelta: Long,
    val successDelta: Long,
    val failureDelta: Long,
    val retryDelta: Long,
    val circuitOpenDelta: Long,
    val failureRateDeltaPct: Double,
    val avgLatencyDeltaMillis: Double,
    val p95LatencyDeltaMillis: Double
)

data class ProviderEndpointDeltaResponse(
    val provider: String,
    val requestDelta: Long,
    val successDelta: Long,
    val failureDelta: Long,
    val retryDelta: Long,
    val circuitOpenDelta: Long,
    val avgLatencyDeltaMillis: Double,
    val p95LatencyDeltaMillis: Double
)

data class ProviderScopeDeltaResponse(
    val scopeType: String,
    val scope: String,
    val requestDelta: Long,
    val successDelta: Long,
    val failureDelta: Long,
    val retryDelta: Long,
    val circuitOpenDelta: Long,
    val avgLatencyDeltaMillis: Double,
    val p95LatencyDeltaMillis: Double
)

data class ProviderTelemetryResponse(
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double,
    val endpoints: List<ProviderEndpointResponse>,
    val tenantScopes: List<ProviderScopeResponse>,
    val commandScopes: List<ProviderScopeResponse>
)

data class ProviderEndpointResponse(
    val provider: String,
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double,
    val circuitOpen: Boolean,
    val lastError: String?
)

data class ProviderScopeResponse(
    val scope: String,
    val requestCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val retryCount: Long,
    val circuitOpenCount: Long,
    val avgLatencyMillis: Double,
    val p95LatencyMillis: Double
)

private fun com.ainsoft.rag.api.ProviderTelemetryStats.toResponse(detailed: Boolean = true): ProviderTelemetryResponse =
    ProviderTelemetryResponse(
        requestCount = requestCount,
        successCount = successCount,
        failureCount = failureCount,
        retryCount = retryCount,
        circuitOpenCount = circuitOpenCount,
        avgLatencyMillis = avgLatencyMillis,
        p95LatencyMillis = p95LatencyMillis,
        endpoints = if (detailed) endpoints.map { endpoint ->
            ProviderEndpointResponse(
                provider = endpoint.provider,
                requestCount = endpoint.requestCount,
                successCount = endpoint.successCount,
                failureCount = endpoint.failureCount,
                retryCount = endpoint.retryCount,
                circuitOpenCount = endpoint.circuitOpenCount,
                avgLatencyMillis = endpoint.avgLatencyMillis,
                p95LatencyMillis = endpoint.p95LatencyMillis,
                circuitOpen = endpoint.circuitOpen,
                lastError = endpoint.lastError
            )
        } else emptyList(),
        tenantScopes = if (detailed) tenantScopes.map { scope ->
            ProviderScopeResponse(
                scope = scope.scope,
                requestCount = scope.requestCount,
                successCount = scope.successCount,
                failureCount = scope.failureCount,
                retryCount = scope.retryCount,
                circuitOpenCount = scope.circuitOpenCount,
                avgLatencyMillis = scope.avgLatencyMillis,
                p95LatencyMillis = scope.p95LatencyMillis
            )
        } else emptyList(),
        commandScopes = if (detailed) commandScopes.map { scope ->
            ProviderScopeResponse(
                scope = scope.scope,
                requestCount = scope.requestCount,
                successCount = scope.successCount,
                failureCount = scope.failureCount,
                retryCount = scope.retryCount,
                circuitOpenCount = scope.circuitOpenCount,
                avgLatencyMillis = scope.avgLatencyMillis,
                p95LatencyMillis = scope.p95LatencyMillis
            )
        } else emptyList()
    )

private fun providerHealthDelta(
    global: com.ainsoft.rag.api.ProviderTelemetryStats,
    recent: com.ainsoft.rag.api.ProviderTelemetryStats
): ProviderTelemetryDeltaResponse =
    ProviderTelemetryDeltaResponse(
        requestDelta = recent.requestCount - global.requestCount,
        successDelta = recent.successCount - global.successCount,
        failureDelta = recent.failureCount - global.failureCount,
        retryDelta = recent.retryCount - global.retryCount,
        circuitOpenDelta = recent.circuitOpenCount - global.circuitOpenCount,
        failureRateDeltaPct = failureRatePct(recent) - failureRatePct(global),
        avgLatencyDeltaMillis = recent.avgLatencyMillis - global.avgLatencyMillis,
        p95LatencyDeltaMillis = recent.p95LatencyMillis - global.p95LatencyMillis
    )

private fun failureRatePct(stats: com.ainsoft.rag.api.ProviderTelemetryStats): Double =
    if (stats.requestCount == 0L) 0.0 else stats.failureCount.toDouble() * 100.0 / stats.requestCount.toDouble()

private fun providerEndpointDeltas(
    global: com.ainsoft.rag.api.ProviderTelemetryStats,
    recent: com.ainsoft.rag.api.ProviderTelemetryStats
): List<ProviderEndpointDeltaResponse> {
    val globalByProvider = global.endpoints.associateBy { it.provider }
    val recentByProvider = recent.endpoints.associateBy { it.provider }
    return (globalByProvider.keys + recentByProvider.keys).sorted().map { provider ->
        val globalEndpoint = globalByProvider[provider]
        val recentEndpoint = recentByProvider[provider]
        ProviderEndpointDeltaResponse(
            provider = provider,
            requestDelta = (recentEndpoint?.requestCount ?: 0L) - (globalEndpoint?.requestCount ?: 0L),
            successDelta = (recentEndpoint?.successCount ?: 0L) - (globalEndpoint?.successCount ?: 0L),
            failureDelta = (recentEndpoint?.failureCount ?: 0L) - (globalEndpoint?.failureCount ?: 0L),
            retryDelta = (recentEndpoint?.retryCount ?: 0L) - (globalEndpoint?.retryCount ?: 0L),
            circuitOpenDelta = (recentEndpoint?.circuitOpenCount ?: 0L) - (globalEndpoint?.circuitOpenCount ?: 0L),
            avgLatencyDeltaMillis = (recentEndpoint?.avgLatencyMillis ?: 0.0) - (globalEndpoint?.avgLatencyMillis ?: 0.0),
            p95LatencyDeltaMillis = (recentEndpoint?.p95LatencyMillis ?: 0.0) - (globalEndpoint?.p95LatencyMillis ?: 0.0)
        )
    }
}

private fun providerScopeDeltas(
    scopeType: String,
    global: List<com.ainsoft.rag.api.ProviderScopeStats>,
    recent: List<com.ainsoft.rag.api.ProviderScopeStats>
): List<ProviderScopeDeltaResponse> {
    val globalByScope = global.associateBy { it.scope }
    val recentByScope = recent.associateBy { it.scope }
    return (globalByScope.keys + recentByScope.keys).sorted().map { scope ->
        val globalScope = globalByScope[scope]
        val recentScope = recentByScope[scope]
        ProviderScopeDeltaResponse(
            scopeType = scopeType,
            scope = scope,
            requestDelta = (recentScope?.requestCount ?: 0L) - (globalScope?.requestCount ?: 0L),
            successDelta = (recentScope?.successCount ?: 0L) - (globalScope?.successCount ?: 0L),
            failureDelta = (recentScope?.failureCount ?: 0L) - (globalScope?.failureCount ?: 0L),
            retryDelta = (recentScope?.retryCount ?: 0L) - (globalScope?.retryCount ?: 0L),
            circuitOpenDelta = (recentScope?.circuitOpenCount ?: 0L) - (globalScope?.circuitOpenCount ?: 0L),
            avgLatencyDeltaMillis = (recentScope?.avgLatencyMillis ?: 0.0) - (globalScope?.avgLatencyMillis ?: 0.0),
            p95LatencyDeltaMillis = (recentScope?.p95LatencyMillis ?: 0.0) - (globalScope?.p95LatencyMillis ?: 0.0)
        )
    }
}

private data class SnippetResult(
    val status: String,
    val snippet: String?,
    val detail: SnippetDetailResponse?
)

data class SnippetDetailResponse(
    val code: String,
    val message: String,
    val retryable: Boolean
)
