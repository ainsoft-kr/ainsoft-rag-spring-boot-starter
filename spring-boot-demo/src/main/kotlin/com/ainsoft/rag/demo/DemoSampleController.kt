package com.ainsoft.rag.demo

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rag/demo")
class DemoSampleController(
    private val sampleDataService: DemoSampleDataService
) {
    @PostMapping("/load-sample")
    fun loadSample(): DemoSampleLoadResponse = sampleDataService.loadSampleData()

    @PostMapping("/clear-sample")
    fun clearSample(): DemoSampleClearResponse = sampleDataService.clearSampleData()
}
