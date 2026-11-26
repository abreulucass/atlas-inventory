package io.github.abreulucass.atlas.access.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;

/** Body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = MemberAccessController.class)
class MemberAccessExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(InvalidGrantException.class)
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(NotAllowedException.class)
	ProblemDetail forbidden(NotAllowedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler({ MemberNotFoundException.class, GrantTargetNotFoundException.class })
	ProblemDetail notFound(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler({ DuplicateGrantException.class, OrganizationArchivedException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
