package com.ainsoft.rag.demo

import com.ainsoft.rag.api.Acl
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.UpsertDocumentRequest
import org.springframework.stereotype.Service

@Service
class DemoSampleDataService(
    private val engine: RagEngine
) {
    fun seedIfEmpty(): DemoSampleLoadResponse? {
        val stats = engine.stats(SAMPLE_TENANT_ID)
        if (stats.docs > 0) return null
        return loadSampleData(status = "seeded")
    }

    fun loadSampleData(): DemoSampleLoadResponse = loadSampleData(status = "loaded")

    fun clearSampleData(): DemoSampleClearResponse {
        val stats = engine.stats(SAMPLE_TENANT_ID)
        val deletedChunks = engine.deleteTenant(SAMPLE_TENANT_ID)
        return DemoSampleClearResponse(
            status = "cleared",
            tenantId = SAMPLE_TENANT_ID,
            deletedDocs = stats.docs,
            deletedChunks = deletedChunks.toLong(),
            docIds = SAMPLE_DOCUMENTS.map { it.docId }
        )
    }

    private fun loadSampleData(status: String): DemoSampleLoadResponse {
        SAMPLE_DOCUMENTS.forEach { document ->
            engine.upsert(
                UpsertDocumentRequest(
                    tenantId = SAMPLE_TENANT_ID,
                    docId = document.docId,
                    normalizedText = document.text,
                    metadata = document.metadata,
                    acl = Acl(SAMPLE_PRINCIPALS),
                    sourceUri = document.sourceUri
                )
            )
        }

        return DemoSampleLoadResponse(
            status = status,
            tenantId = SAMPLE_TENANT_ID,
            principals = SAMPLE_PRINCIPALS,
            suggestedQuery = SAMPLE_SUGGESTED_QUERY,
            docIds = SAMPLE_DOCUMENTS.map { it.docId }
        )
    }

    companion object {
        const val SAMPLE_TENANT_ID = "tenant-web-demo"
        const val SAMPLE_SUGGESTED_QUERY = "hybrid retrieval"
        val SAMPLE_PRINCIPALS = listOf("group:demo")

        private val SAMPLE_DOCUMENTS = listOf(
            DemoSampleDocument(
                docId = "product-overview",
                sourceUri = "demo://product-overview",
                metadata = mapOf("category" to "overview", "surface" to "ui"),
                text = """
                    Ainsoft RAG demo overview

                    This sample workspace demonstrates hybrid retrieval, ACL filtering, provider telemetry,
                    and Spring Boot static asset hosting with a SvelteKit frontend.
                """.trimIndent()
            ),
            DemoSampleDocument(
                docId = "ops-runbook",
                sourceUri = "demo://ops-runbook",
                metadata = mapOf("category" to "operations", "surface" to "backend"),
                text = """
                    Operations runbook

                    Monitor provider health before troubleshooting search quality issues.
                    Review provider fallback notes, recent latency windows, and cache hit rates.
                """.trimIndent()
            ),
            DemoSampleDocument(
                docId = "retrieval-notes",
                sourceUri = "demo://retrieval-notes",
                metadata = mapOf("category" to "retrieval", "surface" to "search"),
                text = """
                    Retrieval design notes

                    Hybrid retrieval combines lexical search with vector search.
                    Use principals such as group:demo to validate ACL behavior and confirm the right
                    tenant is being searched before debugging empty results.
                """.trimIndent()
            )
        )
    }
}

data class DemoSampleLoadResponse(
    val status: String,
    val tenantId: String,
    val principals: List<String>,
    val suggestedQuery: String,
    val docIds: List<String>
)

data class DemoSampleClearResponse(
    val status: String,
    val tenantId: String,
    val deletedDocs: Long,
    val deletedChunks: Long,
    val docIds: List<String> = emptyList()
)

private data class DemoSampleDocument(
    val docId: String,
    val sourceUri: String,
    val metadata: Map<String, String>,
    val text: String
)
