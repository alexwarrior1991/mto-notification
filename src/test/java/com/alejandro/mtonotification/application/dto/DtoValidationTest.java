package com.alejandro.mtonotification.application.dto;

import com.alejandro.mtonotification.application.dto.admin.BroadcastRequest;
import com.alejandro.mtonotification.application.dto.admin.TestEmailRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bean Validation sobre las peticiones: lo evidente se rechaza antes de llegar al servicio. */
class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aBroadcastNeedsATitleAndAtLeastOneAudience() {
        Set<ConstraintViolation<BroadcastRequest>> violations = validator.validate(
                new BroadcastRequest(" ", null, null, null, List.of(), null));
        Set<String> paths = violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains("title"));
        assertTrue(paths.contains("audiences"));

        assertTrue(validator.validate(new BroadcastRequest("Aviso", "cuerpo", "/x", null, List.of("PROFILE:mto-ops"), List.of("inbox"))).isEmpty());
    }

    @Test
    void aBlankAudienceInsideTheListIsRejected() {
        Set<ConstraintViolation<BroadcastRequest>> violations = validator.validate(
                new BroadcastRequest("Aviso", null, null, null, List.of(" "), null));
        assertEquals(1, violations.size());
    }

    @Test
    void aTestEmailNeedsAValidAddress() {
        assertEquals(1, validator.validate(new TestEmailRequest("no-es-un-correo")).size());
        assertTrue(validator.validate(new TestEmailRequest("ops@mto.local")).isEmpty());
    }
}
