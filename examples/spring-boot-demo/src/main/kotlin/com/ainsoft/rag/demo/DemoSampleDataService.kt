package com.ainsoft.rag.demo

import com.ainsoft.rag.api.Acl
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.UpsertDocumentRequest
import com.ainsoft.rag.graph.GraphProjectionService
import com.ainsoft.rag.graph.GraphStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class DemoSampleDataService(
    private val engine: RagEngine,
    private val graphStore: GraphStore,
    private val graphProjectionService: GraphProjectionService
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
        graphStore.deleteTenant(SAMPLE_TENANT_ID)
        return DemoSampleClearResponse(
            status = "cleared",
            tenantId = SAMPLE_TENANT_ID,
            deletedDocs = stats.docs,
            deletedChunks = deletedChunks,
            docIds = emptyList()
        )
    }

    private fun loadSampleData(status: String): DemoSampleLoadResponse {
        SAMPLE_DOCUMENTS.forEach { document ->
            val request = UpsertDocumentRequest(
                tenantId = SAMPLE_TENANT_ID,
                docId = document.docId,
                normalizedText = document.text,
                metadata = document.metadata,
                acl = Acl(SAMPLE_PRINCIPALS),
                sourceUri = document.sourceUri
            )
            engine.upsert(request)
            graphStore.upsertProjection(
                graphProjectionService.projectDocument(
                    tenantId = SAMPLE_TENANT_ID,
                    docId = document.docId,
                    request = request
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
        const val SAMPLE_DOCUMENT_COUNT = 49
        const val SAMPLE_SUGGESTED_QUERY = "하이브리드 검색"
        val SAMPLE_PRINCIPALS = listOf("group:demo")

        private const val SAMPLE_MANIFEST_PATH = "demo-samples/manifest.json"
        private val objectMapper = ObjectMapper()
        private val SAMPLE_DOCUMENTS = loadSampleDocuments()

        private fun loadSampleDocuments(): List<DemoSampleDocument> {
            val manifest = ClassPathResource(SAMPLE_MANIFEST_PATH).inputStream.use { input ->
                objectMapper.readTree(input)
            }
            val documents = manifest.path("documents")
            require(documents.isArray) {
                "Expected 'documents' array in $SAMPLE_MANIFEST_PATH"
            }
            require(documents.size() == SAMPLE_DOCUMENT_COUNT) {
                "Expected $SAMPLE_DOCUMENT_COUNT demo sample documents but found ${documents.size()}"
            }
            return documents.map { entry ->
                val metadata = linkedMapOf<String, String>()
                entry.path("metadata").fields().forEach { (key, value) ->
                    metadata[key] = value.asText()
                }
                DemoSampleDocument(
                    docId = entry.path("docId").asText(),
                    sourceUri = entry.path("sourceUri").asText(),
                    metadata = metadata,
                    text = ClassPathResource("demo-samples/${entry.path("contentPath").asText()}")
                        .inputStream
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText().trim() }
                )
            }
        }
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
