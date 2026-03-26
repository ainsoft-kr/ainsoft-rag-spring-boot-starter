package com.ainsoft.rag.starter

/**
 * Marker object for the Ainsoft RAG Spring Boot starter artifact.
 *
 * The real auto-configuration lives in the autoconfigure module. This marker exists so the
 * starter project is not an empty wrapper and external consumers can quickly identify the
 * starter artifact in code and bytecode.
 */
object AinsoftRagStarter {
    const val ARTIFACT_NAME: String = "ainsoft-rag-spring-boot-starter"
}
