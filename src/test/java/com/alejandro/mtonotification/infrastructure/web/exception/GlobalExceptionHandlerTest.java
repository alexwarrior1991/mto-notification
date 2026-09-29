package com.alejandro.mtonotification.infrastructure.web.exception;

import com.alejandro.mtonotification.application.dto.error.ApiErrorResponse;
import com.alejandro.mtonotification.application.dto.error.ValidationError;
import com.alejandro.mtonotification.application.exception.BusinessException;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import com.alejandro.mtonotification.application.exception.InvalidSortException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.UnprocessableException;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.exception.ValidationException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private static final String INBOX = "/api/v1/notifications/inbox";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundCarriesAggregatePrefixedCodeAndRequestContext() {
        MockHttpServletRequest request = request("POST", INBOX + "/" + UUID.randomUUID() + "/read");
        request.addHeader("X-Correlation-Id", "corr-1");

        ResponseEntity<ApiErrorResponse> response =
                handler.handleNotFound(new NotFoundException("Notification", UUID.randomUUID()), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NTF-404", response.getBody().errorCode());
        assertEquals("corr-1", response.getBody().correlationId());
        assertEquals("POST", response.getBody().method());
    }

    @Test
    void genericBusinessExceptionsAnswer422AndUnknownAggregatesFallBackToTheAppPrefix() {
        MockHttpServletRequest request = request("GET", "/api/v1/notifications/activity");

        assertEquals("BUS-001", codeOf(handler.handleBusiness(new BusinessException("odd"), request), HttpStatus.UNPROCESSABLE_CONTENT));
        assertEquals("VAL-001", codeOf(handler.handleValidation(new ValidationException("bad"), request), HttpStatus.BAD_REQUEST));
        assertEquals("APP-404", codeOf(handler.handleNotFound(new NotFoundException("Widget", UUID.randomUUID()), request), HttpStatus.NOT_FOUND));
        assertEquals("ACT-404", codeOf(handler.handleNotFound(new NotFoundException("Activity event", UUID.randomUUID()), request), HttpStatus.NOT_FOUND));
        assertEquals("DLV-404", codeOf(handler.handleNotFound(new NotFoundException("Delivery", UUID.randomUUID()), request), HttpStatus.NOT_FOUND));
    }

    @Test
    void anEscapedConstraintAnswers409InsteadOf500WithoutLeakingItsName() {
        ResponseEntity<ApiErrorResponse> integrity = handler.handleDataIntegrity(
                new DataIntegrityViolationException("uq_activity_event_source"), request("POST", "/api/v1/notifications/admin/broadcasts"));

        assertEquals("APP-409", codeOf(integrity, HttpStatus.CONFLICT));
        assertEquals("The change conflicts with the stored data.", integrity.getBody().message(), "The constraint name does not leak");
    }

    @Test
    void requestShapeErrorsAnswer400Or405InsteadOf500() {
        ResponseEntity<ApiErrorResponse> missing = handler.handleMissingParameter(
                new MissingServletRequestParameterException("from", "Instant"), request("GET", "/api/v1/notifications/access"));
        assertEquals("REQ-400", codeOf(missing, HttpStatus.BAD_REQUEST));
        assertEquals(List.of(new ValidationError("from", "is required")), missing.getBody().validationErrors());

        ResponseEntity<ApiErrorResponse> sort = handler.handleUnknownSortProperty(
                new PropertyReferenceException("nope", TypeInformation.of(ApiErrorResponse.class), List.of()),
                request("GET", "/api/v1/notifications/activity"));
        assertEquals("REQ-400", codeOf(sort, HttpStatus.BAD_REQUEST));
        assertEquals(List.of(new ValidationError("sort", "unknown property 'nope'")), sort.getBody().validationErrors());

        ResponseEntity<ApiErrorResponse> method = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST")), request("PATCH", INBOX));
        assertEquals("REQ-405", codeOf(method, HttpStatus.METHOD_NOT_ALLOWED));
        assertEquals(Set.of(HttpMethod.GET, HttpMethod.POST), method.getHeaders().getAllow());
    }

    @Test
    void unexpectedExceptionsDoNotLeakDetails() {
        MockHttpServletRequest request = request("GET", INBOX);

        ResponseEntity<ApiErrorResponse> response = handler.handleException(new IllegalStateException("secret"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("APP-500", response.getBody().errorCode());
        assertEquals("An unexpected error occurred. Please contact support.", response.getBody().message());
    }

    @Test
    void securityFailuresAnswer401And403WithAFixedMessage() {
        MockHttpServletRequest request = request("GET", INBOX);

        ResponseEntity<ApiErrorResponse> unauthenticated = handler.handleAuthentication(new BadCredentialsException("token expired"), request);
        ResponseEntity<ApiErrorResponse> forbidden = handler.handleAccessDenied(new AccessDeniedException("needs admin"), request);

        assertEquals("AUTH-401", codeOf(unauthenticated, HttpStatus.UNAUTHORIZED));
        assertEquals("Authentication is required to access this resource.", unauthenticated.getBody().message());
        assertEquals("AUTH-403", codeOf(forbidden, HttpStatus.FORBIDDEN));
        assertEquals("The authenticated user is not allowed to perform this operation.", forbidden.getBody().message(),
                "The cause of the denial is never echoed to the client");
    }

    @Test
    void malformedRequestsAnswer400OrTheirOwnHttpStatusNamingTheOffendingParameter() {
        MockHttpServletRequest request = request("POST", INBOX + "/not-a-uuid/read");
        MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "id", null,
                new IllegalArgumentException("Invalid UUID"));

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodArgumentTypeMismatch(mismatch, request);

        assertEquals("REQ-400", codeOf(response, HttpStatus.BAD_REQUEST));
        assertEquals(List.of(new ValidationError("id", "must be a valid UUID")), response.getBody().validationErrors());
        assertEquals("REQ-400", codeOf(handler.handleHttpMessageNotReadable(request), HttpStatus.BAD_REQUEST));
        assertEquals("HTTP-404", codeOf(handler.handleNoResourceFound(request), HttpStatus.NOT_FOUND));
        assertEquals("REQ-415", codeOf(handler.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("text/plain"), request),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertEquals(INBOX + "/not-a-uuid/read", response.getBody().path());
    }

    @Test
    void constraintViolationsAreListedSortedByPropertyPath() {
        MockHttpServletRequest request = request("POST", "/api/v1/notifications/admin/broadcasts");
        var violations = Validation.buildDefaultValidatorFactory().getValidator()
                .validate(new Probe(" ", "x".repeat(201)));

        ResponseEntity<ApiErrorResponse> response = handler.handleConstraintViolation(new ConstraintViolationException(violations), request);

        assertEquals("REQ-VALIDATION", codeOf(response, HttpStatus.BAD_REQUEST));
        assertEquals(List.of("body", "title"), response.getBody().validationErrors().stream().map(ValidationError::field).toList());
    }

    @Test
    void conflictsUnprocessableRequestsAndOutagesCarryTheirAggregateCodes() {
        MockHttpServletRequest request = request("POST", "/api/v1/notifications/admin/broadcasts");

        assertEquals("DLV-409", codeOf(handler.handleConflict(new ConflictException("Delivery", "already sent"), request), HttpStatus.CONFLICT));
        assertEquals("APP-409", codeOf(handler.handleConflict(new ConflictException("Widget", "odd"), request), HttpStatus.CONFLICT));
        assertEquals("NTF-422", codeOf(handler.handleUnprocessable(new UnprocessableException("Notification", "unknown audience"), request),
                HttpStatus.UNPROCESSABLE_CONTENT));
        assertEquals("NTF-503", codeOf(handler.handleDirectoryUnavailable(new DirectoryUnavailableException("Keycloak is down"), request),
                HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals("SRC-422", codeOf(handler.handleBusiness(new UnprocessableSourceEventException("no entity name"), request),
                HttpStatus.UNPROCESSABLE_CONTENT));

        ResponseEntity<ApiErrorResponse> sort = handler.handleInvalidSort(new InvalidSortException("payload"), request);
        assertEquals("REQ-400", codeOf(sort, HttpStatus.BAD_REQUEST));
        assertEquals(List.of(new ValidationError("sort", "unsupported property 'payload'")), sort.getBody().validationErrors());
    }

    private static String codeOf(ResponseEntity<ApiErrorResponse> response, HttpStatus expected) {
        assertEquals(expected, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody().errorCode();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    /** Un cuerpo con dos reglas, para que la lista salga ordenada por campo y no por orden de fallo. */
    private record Probe(@NotBlank String title, @Size(max = 200) String body) {
    }
}
