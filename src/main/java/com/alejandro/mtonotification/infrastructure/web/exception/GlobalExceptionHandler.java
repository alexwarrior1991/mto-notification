package com.alejandro.mtonotification.infrastructure.web.exception;

import com.alejandro.mtonotification.application.dto.error.ApiErrorResponse;
import com.alejandro.mtonotification.application.dto.error.ValidationError;
import com.alejandro.mtonotification.application.exception.BusinessException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Converts every application exception into the standard API error response contract, the one of
 * {@code mto-maintenance}: the backoffice reads it by alias and shows its {@code errorCode}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Returns 404 because the requested business aggregate does not exist, or is not visible to the
     * caller.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        return businessResponse(exception, HttpStatus.NOT_FOUND, request);
    }

    /**
     * 409 para lo que la validacion no vio y una restriccion de la base si: sin este manejador era un
     * 500 sin explicacion. Las reglas conocidas se comprueban antes; esto es la red.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        LOGGER.warn("Data integrity violation for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMostSpecificCause().getMessage());
        return clientErrorResponse(HttpStatus.CONFLICT, "The change conflicts with the stored data.", "APP-409", List.of(), request);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException exception, HttpServletRequest request) {
        return businessResponse(exception, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Returns 422 for business exceptions that do not have a more specific mapping.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return businessResponse(exception, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * Returns 400 and includes every field and object error produced by Bean Validation.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ValidationError> validationErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), message(error)))
                .toList();
        List<ValidationError> globalErrors = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new ValidationError(error.getObjectName(), message(error)))
                .toList();
        List<ValidationError> errors = new ArrayList<>(validationErrors.size() + globalErrors.size());
        errors.addAll(validationErrors);
        errors.addAll(globalErrors);
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed.",
                "REQ-VALIDATION",
                errors,
                request
        );
    }

    /**
     * Returns 400 and includes every method-level validation violation.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        List<ValidationError> errors = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(GlobalExceptionHandler::toValidationError)
                .toList();
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed.",
                "REQ-VALIDATION",
                errors,
                request
        );
    }

    /**
     * Returns 400 because the HTTP request body cannot be parsed as valid JSON for the expected DTO.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(HttpServletRequest request) {
        LOGGER.warn("Malformed request body for {} {}", request.getMethod(), request.getRequestURI());
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or malformed.",
                "REQ-400",
                List.of(),
                request
        );
    }

    /**
     * Returns 415 because the submitted media type cannot be consumed by the API.
     */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, UnsupportedMediaTypeStatusException.class})
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(Exception exception, HttpServletRequest request) {
        LOGGER.warn("Unsupported media type for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return clientErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported media type.",
                "REQ-415",
                List.of(),
                request
        );
    }

    /**
     * Returns 400 because a path variable or request parameter cannot be converted to the required type.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        Class<?> requiredType = exception.getRequiredType();
        String typeName = requiredType == null ? "required type" : requiredType.getSimpleName();
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid request parameter.",
                "REQ-400",
                List.of(new ValidationError(exception.getName(), "must be a valid " + typeName)),
                request
        );
    }

    /**
     * Returns 400 because a required request parameter is missing. Without this handler it fell into
     * the catch-all and answered 500.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception, HttpServletRequest request) {
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Missing request parameter.",
                "REQ-400",
                List.of(new ValidationError(exception.getParameterName(), "is required")),
                request
        );
    }

    /**
     * Returns 400 because {@code sort} names a property the entity does not have. Spring Data throws
     * it while building the query and nobody translates it, so it would answer 500.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiErrorResponse> handleUnknownSortProperty(PropertyReferenceException exception, HttpServletRequest request) {
        LOGGER.warn("Unknown property in {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return clientErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid sort property.",
                "REQ-400",
                List.of(new ValidationError("sort", "unknown property '" + exception.getPropertyName() + "'")),
                request
        );
    }

    /**
     * Returns 405 with the {@code Allow} header: the resource exists, but not for that method.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        LOGGER.warn("Unsupported method {} {}", request.getMethod(), request.getRequestURI());
        ApiErrorResponse body = errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this resource.", "REQ-405",
                List.of(), request);
        Set<HttpMethod> allowed = exception.getSupportedHttpMethods();
        return allowed == null || allowed.isEmpty()
                ? ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body)
                : ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(allowed.toArray(HttpMethod[]::new)).body(body);
    }

    /**
     * Returns 401 for every authentication failure, including the ones the security filter chain
     * delegates here through {@code RestAuthenticationEntryPoint} before any controller is reached.
     * Handling the whole {@link AuthenticationException} hierarchy matters: a request with no token
     * at all would otherwise fall into the catch-all handler and be reported as a 500.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        LOGGER.warn("Unauthenticated request to {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return clientErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource.",
                "AUTH-401",
                List.of(),
                request
        );
    }

    /**
     * Returns 403 because the caller is authenticated but lacks the role the endpoint requires.
     * Both the path rules of the filter chain and {@code @PreAuthorize} land here, so the client
     * sees the same payload whichever of the two rejected the request.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        LOGGER.warn("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return clientErrorResponse(
                HttpStatus.FORBIDDEN,
                "The authenticated user is not allowed to perform this operation.",
                "AUTH-403",
                List.of(),
                request
        );
    }

    /**
     * Returns 404 because Spring MVC could not resolve a static or endpoint resource for the URL.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(HttpServletRequest request) {
        LOGGER.warn("No resource found for {} {}", request.getMethod(), request.getRequestURI());
        return clientErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource was not found.",
                "HTTP-404",
                List.of(),
                request
        );
    }

    /**
     * Returns 500 for unexpected failures while hiding implementation details from clients.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected exception while processing {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support.",
                "APP-500",
                List.of(),
                request
        ));
    }

    private ResponseEntity<ApiErrorResponse> businessResponse(BusinessException exception, HttpStatus status, HttpServletRequest request) {
        String errorCode = BusinessErrorCodeResolver.resolve(exception);
        LOGGER.warn("Business exception {} for {} {}: {}", errorCode, request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(status).body(errorResponse(status, exception.getMessage(), errorCode, List.of(), request));
    }

    private ResponseEntity<ApiErrorResponse> clientErrorResponse(HttpStatus status,
                                                                String message,
                                                                String errorCode,
                                                                List<ValidationError> validationErrors,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(status).body(errorResponse(status, message, errorCode, validationErrors, request));
    }

    private ApiErrorResponse errorResponse(HttpStatus status,
                                           String message,
                                           String errorCode,
                                           List<ValidationError> validationErrors,
                                           HttpServletRequest request) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.name(),
                message,
                request.getRequestURI(),
                request.getMethod(),
                errorCode,
                request.getHeader(CORRELATION_ID_HEADER),
                validationErrors
        );
    }

    private static ValidationError toValidationError(ConstraintViolation<?> violation) {
        return new ValidationError(violation.getPropertyPath().toString(), violation.getMessage());
    }

    private static String message(ObjectError error) {
        return error.getDefaultMessage() == null ? "Validation error" : error.getDefaultMessage();
    }
}
