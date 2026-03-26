package com.ainsoft.rag.demo

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rag/test")
class TestFailureController {
    @GetMapping("/plain-fail")
    fun plainFail(): String {
        throw IllegalStateException("stream-like failure")
    }
}
