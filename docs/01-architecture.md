# 01 · Arquitectura

## Stack

Java 25, Spring Boot 4.1, Spring Data JPA, Flyway, PostgreSQL, MapStruct, Lombok, Spring AMQP,
Spring Security (OAuth2 resource server + OAuth2 client para la cuenta de servicio saliente),
Spring `RestClient` + Spring Cloud CircuitBreaker (Resilience4j), springdoc,
Micrometer/OpenTelemetry, Testcontainers.

## Capas

```
com.alejandro.mtonotification
├── domain.model            tipos de evento, audiencias, casado de reglas (sin Spring, sin JPA)
├── application
│   ├── dto.<recurso>       records de peticion/respuesta con Bean Validation
│   ├── dto.common          PageResponse, PageMetadataResponse
│   ├── dto.error           ApiErrorResponse, ValidationError
│   ├── service             interfaces publicas
│   ├── service.impl        implementaciones package-private y ayudantes compartidos
│   ├── mapper              MapStruct, entidad -> respuesta solo
│   └── exception           excepciones de negocio que traduce GlobalExceptionHandler
├── infrastructure
│   ├── persistence.entity | .repository | .specification
│   ├── web                 NotificationApiPaths, controladores, web.exception
│   ├── messaging.rabbitmq  consumidores de las fuentes, inbox
│   ├── keycloak            lector de eventos y directorio sobre RestClient
│   └── mail                canal de correo
└── configuration           security, web (correlacion), auditoria JPA, OpenAPI, rabbitmq,
                            messaging (firma), keycloak, mail, rules, scheduling
```

Reglas que mantienen honestas las capas:

- Los controladores hablan con interfaces de servicio y devuelven DTO; las entidades nunca cruzan
  la API.
- Los repositorios con SQL nativo condicional (el inbox, el registro, las ráfagas, los throttles,
  las entregas, la marca de agua) son el único sitio donde se decide una idempotencia o una
  carrera, y siempre dentro de la sentencia que escribe.
- Lo que llega de fuera (un evento, una respuesta de Keycloak) pasa por una lista blanca antes de
  convertirse en una línea del registro.

## Integraciones

| Dirección | Par | Mecanismo |
|---|---|---|
| Entrante | `mto-configuration`, `mto-maintenance`, `mto-stock`, `mto-users` | RabbitMQ, una cola propia por fuente con DLX/DLQ → inbox idempotente |
| Saliente | Keycloak (Admin API) | REST con la cuenta de servicio `mto-notification-svc`, circuito `keycloak`, marca de agua |
| Saliente | SMTP (Mailpit en local) | Correo para lo urgente |
| Entrante | `mto-gateway` / backoffice | JWT del realm `mto` con audiencia `mto-notification-api` |

## Transversal

- **Seguridad**: `SecurityConfiguration` asigna un permiso a cada recurso bajo
  `/api/v1/notifications/**` y deniega lo demás.
- **Errores**: `GlobalExceptionHandler` → `ApiErrorResponse` con un `errorCode` estable
  (`BusinessErrorCodeResolver`).
- **Correlación**: `CorrelationIdFilter` reutiliza o genera `X-Correlation-Id`, lo pone en el MDC y
  en cada error.
- **Auditoría**: `created_by`/`updated_by` a través de `AuditActorResolver`
  (`system` para los procesos de fondo, `unknown` para una petición sin usuario).
- **Trazas**: `spring-boot-starter-opentelemetry` hacia el colector de `mto-platform`, incluida la
  traza que llega en las cabeceras de RabbitMQ.
