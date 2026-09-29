# 07 · Auditoría y datos personales

## Quién tocó una fila

`created_by`/`updated_by` y sus fechas (Spring Data JPA auditing) a través de `AuditActorResolver`:
el `preferred_username` del token, `system` para los procesos de fondo (consumidores, el lector de
Keycloak, las purgas) y `unknown` para una petición HTTP que llega a escribir sin usuario en el
contexto, que además deja un aviso en el log. No hay Envers: el registro es append-only.

## Datos personales

- Los accesos llevan nombre de usuario e IP. Solo viven en la categoría `ACCESS`, que nunca sale por
  `/activity` y tiene su permiso (`notification-access-read`).
- Nunca contraseñas, tokens ni secretos: los eventos de Keycloak y de los hermanos pasan por una
  lista blanca de campos antes de guardarse; el JSON crudo solo vive en el inbox, que se purga a los
  7 días.
- Retención con purga programada por lotes: accesos 90 días; usuarios, configuración,
  mantenimiento y almacén 400; notificaciones y entregas 180; inbox 7. Keycloak caduca los suyos a
  los 7 días: este servicio es el archivo.
