package io.github.hanumoka.zmbaton.server

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

/**
 * The server's own refusals (400, 401, 403, 404, 409) keep Spring Boot's error body and add
 * `message`: the reason, written for a person, so a screen can say why a move was refused.
 * Only these exception types are handled; any other error keeps the default body without a
 * message, so nothing internal reaches a client.
 */
@RestControllerAdvice
class ApiErrors {

	@ExceptionHandler(
		BadRequestException::class, UnauthorizedException::class, ForbiddenException::class,
		NotFoundException::class, ConflictException::class,
	)
	fun refused(e: RuntimeException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
		val status = AnnotationUtils.findAnnotation(e.javaClass, ResponseStatus::class.java)?.code ?: HttpStatus.BAD_REQUEST
		val body = linkedMapOf<String, Any?>(
			"timestamp" to OffsetDateTime.now().toString(),
			"status" to status.value(),
			"error" to status.reasonPhrase,
			"path" to request.requestURI,
			"message" to e.message,
		)
		return ResponseEntity.status(status).body(body)
	}
}
