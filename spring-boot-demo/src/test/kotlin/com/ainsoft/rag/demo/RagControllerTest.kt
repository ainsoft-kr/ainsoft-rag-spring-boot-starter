package com.ainsoft.rag.demo

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.hamcrest.Matchers.containsString
import java.nio.file.Files

@SpringBootTest(
    properties = [
        "demo.seedEnabled=false",
        "rag.indexPath=build/test-rag-index/\${random.uuid}",
        "rag.storeChunkText=true",
        "rag.uploadMaxBytes=32",
        "rag.sourceLoadAllowHosts[0]=127.0.0.1"
    ]
)
@AutoConfigureMockMvc
class RagControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `root path serves the bundled frontend`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `bundled index html is exposed as a static resource`() {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("<!doctype html>")))
    }

    @Test
    fun `nested frontend routes are forwarded to index`() {
        mockMvc.perform(get("/workbench/search"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `demo sample endpoint loads reusable docs`() {
        mockMvc.perform(post("/api/rag/demo/load-sample"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("loaded"))
            .andExpect(jsonPath("$.tenantId").value("tenant-web-demo"))
            .andExpect(jsonPath("$.docIds").isArray)
            .andExpect(jsonPath("$.docIds.length()").value(3))

        mockMvc.perform(get("/api/rag/stats").param("tenantId", "tenant-web-demo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.docs").value(3))
    }

    @Test
    fun `ingest search and stats endpoints work`() {
        val ingestBody = IngestRequest(
            tenantId = "tenant-demo",
            docId = "demo-1",
            text = "demo api text for search",
            acl = listOf("group:demo"),
            metadata = mapOf("category" to "demo")
        )

        mockMvc.perform(
            post("/api/rag/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(ingestBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ingested"))

        val searchBody = SearchApiRequest(
            tenantId = "tenant-demo",
            principals = listOf("group:demo"),
            query = "search",
            topK = 5,
            providerHealthDetail = true,
            recentProviderWindowMillis = 60000
        )

        mockMvc.perform(
            post("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value("tenant-demo"))
            .andExpect(jsonPath("$.hits[0].docId").value("demo-1"))
            .andExpect(jsonPath("$.meta.resultCount").value(1))
            .andExpect(jsonPath("$.meta.aclApplied").value(true))
            .andExpect(jsonPath("$.meta.providerFallbackApplied").value(false))
            .andExpect(jsonPath("$.meta.notes").isArray)
            .andExpect(jsonPath("$.meta.providerHealthSummary.requestCount").isNumber)
            .andExpect(jsonPath("$.meta.providerHealthSummary.commandScopes").isArray)
            .andExpect(jsonPath("$.meta.recentProviderHealthSummary.requestCount").isNumber)
            .andExpect(jsonPath("$.meta.providerTelemetryDelta.requestDelta").isNumber)
            .andExpect(jsonPath("$.meta.providerTenantScopeDeltas").isArray)
            .andExpect(jsonPath("$.meta.providerCommandScopeDeltas").isArray)

        mockMvc.perform(get("/api/rag/stats").param("tenantId", "tenant-demo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value("tenant-demo"))
            .andExpect(jsonPath("$.docs").value(1))
            .andExpect(jsonPath("$.chunks").isNumber)
            .andExpect(jsonPath("$.statsCacheHitCount").isNumber)
            .andExpect(jsonPath("$.statsCacheEvictionCount").isNumber)
            .andExpect(jsonPath("$.statsCacheExpiredCount").isNumber)
            .andExpect(jsonPath("$.statsCacheTtlMillis").isNumber)
            .andExpect(jsonPath("$.statsCacheMaxEntries").isNumber)
            .andExpect(jsonPath("$.statsCacheMaxEntriesPerTenant").isNumber)
            .andExpect(jsonPath("$.statsCachePersistenceMode").value("memory_only"))
            .andExpect(jsonPath("$.providerTelemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.providerTelemetry.avgLatencyMillis").isNumber)
            .andExpect(jsonPath("$.providerTelemetry.commandScopes").isArray)

        mockMvc.perform(
            get("/api/rag/stats")
                .param("tenantId", "tenant-demo")
                .param("recentProviderWindowMillis", "60000")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providerTelemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.recentProviderWindowMillis").value(60000))
            .andExpect(jsonPath("$.recentProviderTelemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.providerTelemetryDelta.requestDelta").isNumber)
            .andExpect(jsonPath("$.providerTelemetryDelta.avgLatencyDeltaMillis").isNumber)
            .andExpect(jsonPath("$.providerEndpointDeltas").isArray)
            .andExpect(jsonPath("$.providerTenantScopeDeltas").isArray)
            .andExpect(jsonPath("$.providerCommandScopeDeltas").isArray)
            .andExpect(jsonPath("$.recentProviderTelemetry.commandScopes").isArray)

        mockMvc.perform(
            get("/api/rag/provider-health")
                .param("recentProviderWindowMillis", "60000")
                .param("detailed", "true")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providerTelemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.recentProviderTelemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.providerTelemetryDelta.requestDelta").isNumber)
            .andExpect(jsonPath("$.providerEndpointDeltas").isArray)
            .andExpect(jsonPath("$.providerTenantScopeDeltas").isArray)
            .andExpect(jsonPath("$.providerCommandScopeDeltas").isArray)

        mockMvc.perform(
            get("/api/rag/observability/provider-health")
                .param("recentProviderWindowMillis", "60000")
                .param("detailed", "true")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generatedAtEpochMillis").isNumber)
            .andExpect(jsonPath("$.global.telemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.recent.telemetry.requestCount").isNumber)
            .andExpect(jsonPath("$.delta.overall.requestDelta").isNumber)
            .andExpect(jsonPath("$.delta.endpoints").isArray)
            .andExpect(jsonPath("$.delta.tenantScopes").isArray)
            .andExpect(jsonPath("$.delta.commandScopes").isArray)
    }

    @Test
    fun `multipart ingest endpoint accepts uploaded file`() {
        val file = MockMultipartFile(
            "file",
            "upload.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "uploaded demo text".toByteArray()
        )

        mockMvc.perform(
            multipart("/api/rag/ingest-file")
                .file(file)
                .param("tenantId", "tenant upload")
                .param("docId", "upload 1")
                .param("acl", "group:upload")
                .param("metadata", "category=upload")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ingested"))
            .andExpect(jsonPath("$.tenantId").value("tenant-upload"))
            .andExpect(jsonPath("$.docId").value("upload-1"))

        val searchBody = SearchApiRequest(
            tenantId = "tenant-upload",
            principals = listOf("group:upload"),
            query = "uploaded",
            topK = 5
        )

        mockMvc.perform(
            post("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hits[0].docId").value("upload-1"))
            .andExpect(jsonPath("$.meta.resultCount").value(1))
    }

    @Test
    fun `multipart ingest endpoint validates content type and returns structured error`() {
        val file = MockMultipartFile(
            "file",
            "bad.bin",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            "0123456789".toByteArray()
        )

        mockMvc.perform(
            multipart("/api/rag/ingest-file")
                .file(file)
                .param("tenantId", "tenant-bad")
                .param("docId", "bad-1")
                .param("acl", "group:bad")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
    }

    @Test
    fun `search can return source snippet using configured loader policy`() {
        val tempFile = Files.createTempFile("rag-demo-source", ".txt")
        try {
            Files.writeString(tempFile, "source snippet text")
            val ingestBody = IngestRequest(
                tenantId = "tenant-source",
                docId = "source-1",
                text = "source snippet text",
                acl = listOf("group:source"),
                sourceUri = tempFile.toUri().toString()
            )

            mockMvc.perform(
                post("/api/rag/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(ingestBody))
            )
                .andExpect(status().isOk)

            val searchBody = SearchApiRequest(
                tenantId = "tenant-source",
                principals = listOf("group:source"),
                query = "snippet",
                openSource = true
            )

            mockMvc.perform(
                post("/api/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hits[0].sourceSnippet").exists())
            .andExpect(jsonPath("$.hits[0].snippetStatus").value("LOADED"))
            .andExpect(jsonPath("$.meta.snippetStatusCounts.LOADED").value(1))
            .andExpect(jsonPath("$.meta.snippetAvailableCount").value(1))
        } finally {
            tempFile.toFile().delete()
        }
    }

    @Test
    fun `empty search returns meta reason`() {
        val searchBody = SearchApiRequest(
            tenantId = "tenant-empty",
            principals = listOf("group:none"),
            query = "missing"
        )

        mockMvc.perform(
            post("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hits").isEmpty)
            .andExpect(jsonPath("$.meta.emptyReason").value("TENANT_EMPTY"))
    }

    @Test
    fun `empty search can report acl filtered`() {
        val ingestBody = IngestRequest(
            tenantId = "tenant-acl",
            docId = "doc-1",
            text = "restricted document",
            acl = listOf("group:allowed")
        )

        mockMvc.perform(
            post("/api/rag/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(ingestBody))
        ).andExpect(status().isOk)

        val searchBody = SearchApiRequest(
            tenantId = "tenant-acl",
            principals = listOf("group:denied"),
            query = "restricted"
        )

        mockMvc.perform(
            post("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hits").isEmpty)
            .andExpect(jsonPath("$.meta.emptyReason").value("ACL_FILTERED"))
    }

    @Test
    fun `search exposes structured snippet failure details`() {
        val ingestBody = IngestRequest(
            tenantId = "tenant-snippet-fail",
            docId = "doc-1",
            text = "snippet failure text",
            acl = listOf("group:snippet"),
            sourceUri = "https://blocked.example.com/doc.txt"
        )

        mockMvc.perform(
            post("/api/rag/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(ingestBody))
        )
            .andExpect(status().isOk)

        val searchBody = SearchApiRequest(
            tenantId = "tenant-snippet-fail",
            principals = listOf("group:snippet"),
            query = "failure",
            openSource = true
        )

        mockMvc.perform(
            post("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(searchBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hits[0].snippetStatus").value("LOAD_BLOCKED_OR_UNAVAILABLE"))
            .andExpect(jsonPath("$.hits[0].snippetDetail.code").value("LOAD_BLOCKED_OR_UNAVAILABLE"))
            .andExpect(jsonPath("$.hits[0].snippetDetail.retryable").value(true))
            .andExpect(jsonPath("$.meta.snippetStatusCounts.LOAD_BLOCKED_OR_UNAVAILABLE").value(1))
    }

    @Test
    fun `diagnose search endpoint returns diagnostics`() {
        val ingestBody = IngestRequest(
            tenantId = "tenant-diagnose",
            docId = "doc-1",
            text = "diagnostic target text",
            acl = listOf("group:diagnose")
        )

        mockMvc.perform(
            post("/api/rag/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(ingestBody))
        ).andExpect(status().isOk)

        val request = SearchApiRequest(
            tenantId = "tenant-diagnose",
            principals = listOf("group:diagnose"),
            query = "diagnostic",
            providerHealthDetail = true,
            recentProviderWindowMillis = 60000,
            diagnosticMaxSamples = 1
        )

        mockMvc.perform(
            post("/api/rag/diagnose-search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tenantId").value("tenant-diagnose"))
            .andExpect(jsonPath("$.derivedEmptyReason").exists())
            .andExpect(jsonPath("$.lexicalSampleDocIdsWithoutAcl[0]").value("doc-1"))
            .andExpect(jsonPath("$.executedQuery").value("diagnostic"))
            .andExpect(jsonPath("$.providerFallbackApplied").value(false))
            .andExpect(jsonPath("$.providersUsed").isArray)
            .andExpect(jsonPath("$.notes").isArray)
            .andExpect(jsonPath("$.providerHealthSummary.requestCount").isNumber)
            .andExpect(jsonPath("$.providerHealthSummary.commandScopes").isArray)
            .andExpect(jsonPath("$.recentProviderHealthSummary.requestCount").isNumber)
            .andExpect(jsonPath("$.providerTelemetryDelta.requestDelta").isNumber)
            .andExpect(jsonPath("$.providerTenantScopeDeltas").isArray)
            .andExpect(jsonPath("$.providerCommandScopeDeltas").isArray)
    }
}
