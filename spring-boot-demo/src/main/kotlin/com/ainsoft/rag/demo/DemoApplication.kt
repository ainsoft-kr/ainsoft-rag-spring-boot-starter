package com.ainsoft.rag.demo

import com.ainsoft.rag.api.RagEngine
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class DemoApplication {

    @Bean
    fun ragDemoRunner(engine: RagEngine): CommandLineRunner = CommandLineRunner {
        val stats = engine.stats()
        println("RagEngine ready. docs=${stats.docs} chunks=${stats.chunks}")
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
