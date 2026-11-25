package io.github.abreulucass.atlas.warehouse.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.access.NotAllowedException;
import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.company.CompanyNotFoundException;
import io.github.abreulucass.atlas.warehouse.CompanyArchivedException;
import io.github.abreulucass.atlas.warehouse.DuplicateWarehouseCodeException;
import io.github.abreulucass.atlas.warehouse.DuplicateWarehouseNameException;
import io.github.abreulucass.atlas.warehouse.InvalidCodeException;
import io.github.abreulucass.atlas.warehouse.InvalidNameException;
import io.github.abreulucass.atlas.warehouse.WarehouseNotFoundException;

/** Validation and body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = WarehouseController.class)
class WarehouseExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler({ InvalidNameException.class, InvalidCodeException.class })
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ DuplicateWarehouseNameException.class, DuplicateWarehouseCodeException.class,
			CompanyArchivedException.class, OrganizationArchivedException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(NotAllowedException.class)
	ProblemDetail forbidden(NotAllowedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler({ CompanyNotFoundException.class, WarehouseNotFoundException.class })
	ProblemDetail notFound(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail malformedId(MethodArgumentTypeMismatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
	}

}
