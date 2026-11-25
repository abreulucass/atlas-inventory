package io.github.abreulucass.atlas.access.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.access.DuplicateSlugException;
import io.github.abreulucass.atlas.access.InvalidNameException;
import io.github.abreulucass.atlas.access.InvalidSlugException;

/** Validation and body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler({ InvalidNameException.class, InvalidSlugException.class, InvalidPasswordException.class,
			InvalidEmailException.class })
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ BadCredentialsException.class, InvalidRefreshTokenException.class })
	ProblemDetail unauthorized(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
	}

	@ExceptionHandler({ DuplicateEmailException.class, DuplicateSlugException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
