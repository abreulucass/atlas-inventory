package io.github.abreulucass.atlas.company.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.company.CompanyNotFoundException;

/** Validation and body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = CompanyController.class)
class CompanyExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(CompanyNotFoundException.class)
	ProblemDetail notFound(CompanyNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail malformedId(MethodArgumentTypeMismatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Company not found");
	}

}
