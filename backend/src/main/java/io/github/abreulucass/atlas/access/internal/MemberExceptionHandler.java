package io.github.abreulucass.atlas.access.internal;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Validation and body-parsing errors (400) come from the base class, all as {@code ProblemDetail}. */
@RestControllerAdvice(assignableTypes = MemberController.class)
class MemberExceptionHandler extends ResponseEntityExceptionHandler {

}
