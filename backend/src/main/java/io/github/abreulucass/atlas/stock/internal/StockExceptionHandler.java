package io.github.abreulucass.atlas.stock.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.abreulucass.atlas.access.OrganizationArchivedException;
import io.github.abreulucass.atlas.catalog.ProductNotFoundException;
import io.github.abreulucass.atlas.company.CompanyNotFoundException;
import io.github.abreulucass.atlas.stock.IdempotencyKeyReusedException;
import io.github.abreulucass.atlas.stock.InsufficientBalanceException;
import io.github.abreulucass.atlas.stock.InvalidIdempotencyKeyException;
import io.github.abreulucass.atlas.stock.InvalidMovementException;
import io.github.abreulucass.atlas.stock.StockArchivedException;
import io.github.abreulucass.atlas.stock.StockNotFoundException;
import io.github.abreulucass.atlas.warehouse.WarehouseNotFoundException;

/** Everything as {@code ProblemDetail}; body-parsing errors from the base class. */
@RestControllerAdvice(assignableTypes = { MovementController.class })
class StockExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler({ InvalidIdempotencyKeyException.class, InvalidMovementException.class })
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ StockNotFoundException.class, CompanyNotFoundException.class,
			WarehouseNotFoundException.class, ProductNotFoundException.class })
	ProblemDetail notFound(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler({ OrganizationArchivedException.class, StockArchivedException.class,
			InsufficientBalanceException.class })
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	ProblemDetail unprocessable(IdempotencyKeyReusedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

}
