package com.alejandro.mtonotification.configuration;

import com.alejandro.mtonotification.application.dto.error.ApiErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Metadatos, esquema de seguridad, etiquetas y respuestas de error reutilizables del OpenAPI.
 *
 * <p>Las rutas no se declaran aqui a mano: springdoc las lee de las anotaciones de los
 * controladores, que son la unica fuente de verdad de la API. Lo que si se fija aqui es lo
 * transversal, que ningun controlador deberia repetir.</p>
 */
@Configuration
public class OpenApiDocumentationConfiguration {

    private static final String JSON = "application/json";
    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI mtoNotificationOpenApi() {
        return new OpenAPI()
                .info(apiInfo())
                // Requisito global: cada operacion documentada pide el bearer salvo que lo anule
                // explicitamente, de modo que anadir un endpoint nuevo no lo publica como abierto.
                .security(List.of(new SecurityRequirement().addList(BEARER_AUTH)))
                .servers(List.of(
                        new Server().url("http://localhost:8086").description("Local development server"),
                        new Server().url("http://localhost:8090/api/notifications").description("Through mto-gateway")
                ))
                .tags(tags())
                .components(components());
    }

    private static Info apiInfo() {
        return new Info()
                .title("MTO Notification API")
                .description("Activity log of the MTO domain (what happened, who, when, from which service and on "
                        + "which entity) and the notifications its rules derive from it: an in-app inbox per "
                        + "person, e-mail for the urgent ones.")
                .version("v1")
                .contact(new Contact()
                        .name("MTO Platform Team")
                        .email("support@example.com"))
                .license(new License()
                        .name("Proprietary"));
    }

    private static List<Tag> tags() {
        return List.of(
                tag("Inbox", "My notifications: page, unread count, mark as read."),
                tag("Activity", "The activity log of the domain, everything but the access events."),
                tag("Access", "Login, failed login, logout and lockout events, with username and IP address."),
                tag("Admin", "Loaded rules, deliveries and their retries, state of the sources, manual broadcasts.")
        );
    }

    private static Components components() {
        Components components = new Components()
                .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak-issued JWT access token. Send it as \"Authorization: Bearer <token>\"."))
                .addResponses("BadRequest", errorResponse("Invalid request, validation failure or malformed JSON.", HttpStatus.BAD_REQUEST))
                .addResponses("Unauthorized", errorResponse("Missing, expired or otherwise invalid bearer token.", HttpStatus.UNAUTHORIZED))
                .addResponses("Forbidden", errorResponse("The authenticated user lacks the role required by this operation.", HttpStatus.FORBIDDEN))
                .addResponses("NotFound", errorResponse("The requested resource was not found, or is not visible to the caller.", HttpStatus.NOT_FOUND))
                .addResponses("Conflict", errorResponse("The request conflicts with current state.", HttpStatus.CONFLICT))
                .addResponses("UnprocessableContent", errorResponse("The request is syntactically valid but violates a domain rule.", HttpStatus.UNPROCESSABLE_CONTENT))
                .addResponses("ServiceUnavailable", errorResponse("A dependency (the Keycloak directory) did not answer.", HttpStatus.SERVICE_UNAVAILABLE))
                .addResponses("InternalServerError", errorResponse("Unexpected server error. Internal details are not exposed to clients.", HttpStatus.INTERNAL_SERVER_ERROR));
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(ApiErrorResponse.class);
        schemas.forEach(components::addSchemas);
        return components;
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private static ApiResponse errorResponse(String description, HttpStatus status) {
        return new ApiResponse()
                .description(status.value() + " - " + description)
                .content(new Content().addMediaType(JSON, new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiErrorResponse"))));
    }
}
