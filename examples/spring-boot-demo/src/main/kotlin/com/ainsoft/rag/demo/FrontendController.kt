package com.ainsoft.rag.demo

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class FrontendController {
    @GetMapping("/")
    fun index(): String = "forward:/index.html"
}
