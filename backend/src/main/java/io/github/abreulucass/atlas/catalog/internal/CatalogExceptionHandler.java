package io.github.abreulucass.atlas.catalog.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.catalog.CategoryArchivedException;
import io.github.abreulucass.atlas.catalog.CategoryNotFoundException;
import io.github.abreulucass.atlas.catalog.DuplicateCategoryNameException;
import io.github.abreulucass.atlas.catalog.InvalidCategoryIdException;
import io.github.abreulucass.atlas.catalog.InvalidNameException;

/** Validation and body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = CategoryController.class)
class CatalogExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler({ InvalidNameException.class, InvalidCategoryIdException.class })
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ DuplicateCategoryNameException.class, CategoryArchivedException.class,
			OrganizationArchivedException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(NotAllowedException.class)
	ProblemDetail forbidden(NotAllowedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler(CategoryNotFoundException.class)
	ProblemDetail notFound(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

}
