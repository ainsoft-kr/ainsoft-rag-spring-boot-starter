package com.ainsoft.rag.demo

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SpaFallbackControllerAdvice {
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        ex: NoResourceFoundException,
        request: HttpServletRequest
    ): Any {
        val path = request.requestURI ?: return notFound(ex)
        return if (request.method == "GET" && !path.startsWith("/api/") && !path.contains('.')) {
            "forward:/index.html"
        } else {
            notFound(ex)
        }
    }

    private fun notFound(ex: NoResourceFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(404).body(
            ApiErrorResponse(
                code = "NOT_FOUND",
                message = ex.message ?: "resource not found"
            )
        )
}
