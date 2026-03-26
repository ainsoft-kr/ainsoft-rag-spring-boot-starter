package com.ainsoft.rag.demo

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "BAD_REQUEST",
                message = ex.message ?: "bad request"
            )
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "MISSING_PARAMETER",
                message = ex.message
            )
        )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(ex: HttpMediaTypeNotAcceptableException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .contentType(MediaType.TEXT_PLAIN)
            .body(ex.message ?: "No acceptable representation")

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ResponseEntity<*> {
        val message = ex.message ?: "internal error"
        return if (shouldRenderPlainText(request)) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("INTERNAL_ERROR: $message")
        } else {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse(
                    code = "INTERNAL_ERROR",
                    message = message
                )
            )
        }
    }

    private fun shouldRenderPlainText(request: HttpServletRequest): Boolean {
        val acceptHeader = request.getHeader("Accept").orEmpty()
        val requestUri = request.requestURI.orEmpty()
        return requestUri.endsWith("/stream") ||
            acceptHeader.contains(MediaType.TEXT_PLAIN_VALUE) ||
            acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)
    }
}

data class ApiErrorResponse(
    val code: String,
    val message: String
)
