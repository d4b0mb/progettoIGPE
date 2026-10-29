package it.ristomanager.cloud.common;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** What the client is told about any failure that is the server's own. Never the real cause. */
    static final String GENERIC_MESSAGE = "The request could not be completed";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        if (e.field() != null) {
            problem.setProperty("fields", Map.of(e.field(), e.getMessage()));
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        problem.setProperty("fields", fields);
        return problem;
    }

    /**
     * A database failure nobody planned for. The driver's message can quote the row that was refused
     * ("Failing row contains (...)"), which for a reservation is the guest's name, phone, email, allergens and notes,
     * so it must reach neither the client nor the log.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException e, HttpServletResponse response) {
        return internalError(e, response);
    }

    /**
     * The last resort for anything else unexpected, with the same rule. It must not take over what the framework
     * already answers correctly: security refusals (403, 401) and the client's own mistakes (400, 404, 405, 413, 415...)
     * are handed back untouched, by rethrowing the original exception to the framework's own resolvers.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletResponse response) throws Exception {
        if (belongsToTheFramework(e)) {
            throw e;
        }
        // An ApiException wrapped in something else was answered by handleApi before this catch-all existed.
        ApiException wrapped = find(e, ApiException.class);
        if (wrapped != null) {
            ProblemDetail problem = handleApi(wrapped);
            return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
        return internalError(e, response);
    }

    private static ResponseEntity<ProblemDetail> internalError(Exception e, HttpServletResponse response) {
        String errorId = UUID.randomUUID().toString();
        // Only the type and an id. No message, no cause and no stack trace: any of them can carry personal data.
        log.error("Unexpected {} (error id {})", e.getClass().getName(), errorId);
        if (response.isCommitted()) {
            return null; // Part of an answer is already on its way (a stream); there is nothing left to write.
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_MESSAGE);
        problem.setProperty("errorId", errorId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Security refusals and client errors, which Spring Security and Spring MVC turn into 401, 403 and 4xx themselves. */
    private static boolean belongsToTheFramework(Throwable top) {
        int depth = 0;
        for (Throwable t = top; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof AccessDeniedException || t instanceof AuthenticationException) {
                return true;
            }
        }
        return top instanceof ServletException // missing parameter or part, wrong method or media type, no such path (404)...
                || top instanceof ErrorResponse // every Spring MVC exception that knows its own status, ResponseStatusException
                || top instanceof MultipartException // upload too large (413) or malformed
                || top instanceof HttpMessageNotReadableException // malformed JSON body
                || top instanceof TypeMismatchException // a parameter of the wrong type
                || top instanceof BindException
                || AnnotatedElementUtils.findMergedAnnotation(top.getClass(), ResponseStatus.class) != null;
    }

    private static <T extends Throwable> T find(Throwable top, Class<T> type) {
        int depth = 0;
        for (Throwable t = top; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
