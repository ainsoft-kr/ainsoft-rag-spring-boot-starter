package com.ainsoft.rag.demo

import com.ainsoft.rag.api.RagEngine
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.annotation.Value
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@SpringBootApplication
class DemoApplication {

    @Bean
    fun ragDemoRunner(
        engine: RagEngine,
        sampleDataService: DemoSampleDataService,
        @Value("\${demo.seedEnabled:true}") seedEnabled: Boolean
    ): CommandLineRunner = CommandLineRunner {
        if (seedEnabled) {
            val seeded = sampleDataService.seedIfEmpty()
            if (seeded != null) {
                println("Loaded demo sample data. tenant=${seeded.tenantId} docs=${seeded.docIds.size}")
            }
        }
        val stats = engine.stats()
        println("RagEngine ready. docs=${stats.docs} chunks=${stats.chunks}")
    }

    @Bean(destroyMethod = "close")
    fun streamExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
