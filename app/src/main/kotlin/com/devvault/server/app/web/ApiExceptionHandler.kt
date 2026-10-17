package com.devvault.server.app.web

import com.devvault.server.core.ApiException
import com.devvault.server.core.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 等价于原先 Ktor 的 StatusPages：业务错误 → 对应状态码 + `{ "message": ... }`，
 * 未知异常统一 500 且不泄漏内部信息。
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.message))

    /** 请求体不是合法 JSON、字段类型不对、查询参数类型不对 */
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun handleUnreadable(): ResponseEntity<ErrorResponse> = error(HttpStatus.BAD_REQUEST, "请求格式不正确")

    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun handleNotFound(): ResponseEntity<ErrorResponse> = error(HttpStatus.NOT_FOUND, "接口不存在")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(): ResponseEntity<ErrorResponse> = error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不正确")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(): ResponseEntity<ErrorResponse> = error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求格式不正确")

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error", e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误")
    }

    private fun error(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(message))
}
