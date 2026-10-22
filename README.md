# ArequipaAccesible-Backend

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11 y PostgreSQL 17. Implementa **solo iteraciones 0 y 1** de la guía técnica del 22/10/2026. El repositorio original contenía únicamente este README; se conserva el nombre y remoto.

## Implementado

- Base Maven, healthcheck real, Flyway, logs ECS, configuración por entorno y CI.
- Dominio/aplicación sin Spring ni JPA, puertos y adaptadores JPA/HTTP. ArchUnit verifica esta separación.
- JWT Supabase: firma RS256/ES256 mediante JWKS, issuer, audiencia, expiración, nbf, subject UUID y usuario no anónimo. No se emiten JWT propios ni se guardan contraseñas.
- Roles y estado de cuenta leídos de PostgreSQL en cada petición; nunca de `user_metadata` ni IDs/roles del cliente.
- Perfil propio con alta idempotente, edición limitada y documento enmascarado. Verificación inicial NO_VERIFICADO, sin afirmar validación RENIEC.
- Provisión administrativa mediante operación de BD, con auditoría y atomicidad.
- Esquema privado `accesible`, RLS y sin acceso al Data API. Migraciones V1 (identidad/catálogos/auditoría) y V2 (vinculación Auth).

No se incluyen reportes, fotos/Storage, historial, administración HTTP, KPI, IA ni cuadrillas. Sus tablas y casos de uso se incorporarán con HU-01 y las iteraciones siguientes. Catálogos vacíos hasta recibir datos aprobados; solo se siembran CIUDADANO y ADMINISTRADOR. No se han creado buckets ni conectado un proyecto Supabase real.

## Estructura

```text
src/main/java/pe/arequipa/accesible/
  domain/model, domain/port/out
  application/command, application/usecase
  infrastructure/config, infrastructure/security, infrastructure/persistence
  presentation/rest
src/main/resources/db/migration/
src/test/java/pe/arequipa/accesible/
ops/                         operación y pruebas PostgreSQL locales
requests.http                ejemplos ficticios, sin tokens reales
```

## Configuración y arranque

Requisitos: JDK 21, PostgreSQL 17 o proyecto Supabase real y Maven Central accesible durante la primera ejecución. En Windows usar `.\mvnw.cmd` en lugar de `./mvnw`.

1. Consultar `.env.example` y exportar las variables desde el shell o gestor de secretos. **Spring no carga `.env` automáticamente.** No versionar valores reales.
2. `DB_URL` es JDBC; `DB_USERNAME`/`DB_PASSWORD` deben corresponder a la conexión real. En Supabase usar TLS con verificación de certificado (`sslmode=verify-full` y CA según corresponda). Preferir conexión directa o session pooler compatible con JDBC; evitar transaction pooler para migraciones.
3. `SUPABASE_JWT_ISSUER`: issuer exacto del proyecto, termina en `/auth/v1`. `SUPABASE_JWKS_URI`: su `/.well-known/jwks.json`. `SUPABASE_JWT_AUDIENCE`: audiencia real, por defecto `authenticated`. Se requieren claves asimétricas publicadas: un proyecto legacy HS256 debe migrar sus signing keys; no se añade un secreto JWT compartido ni bypass.
4. `CORS_ALLOWED_ORIGINS`: orígenes exactos separados por comas. Vacío rechaza otros orígenes; `*` está prohibido.
5. HTTPS obligatorio por defecto. Solo en desarrollo local establecer `REQUIRE_HTTPS=false`. En producción configurar TLS directamente o un proxy confiable. Habilitar `SERVER_FORWARD_HEADERS_STRATEGY=framework` únicamente si el backend no es accesible directamente y el proxy elimina/reemplaza headers forwarded del cliente. Por defecto se ignoran estos headers.
6. Desarrollo con propietario del esquema: `./mvnw spring-boot:run` aplica Flyway y valida JPA. `ddl-auto=validate`, nunca update.

```sh
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

No se requiere service_role key, anon key ni Storage en estas iteraciones. Registro, confirmación de correo, login, recuperación y logout corresponden al cliente y Supabase Auth, no a endpoints nuevos del backend.

### Producción: migraciones y rol de ejecución separado

El job de migraciones recibe `DB_URL`, `MIGRATION_DB_USERNAME` y `MIGRATION_DB_PASSWORD` del propietario:

```sh
./mvnw flyway:migrate
./mvnw flyway:validate
```

V2 crea FK `usuarios.id -> auth.users.id` cuando existe Supabase Auth. En PostgreSQL independiente no inventa Auth. Si se instala Auth después de V2, añadir una nueva migración para la FK antes de desplegar; no editar migraciones aplicadas. ON DELETE RESTRICT exige un proceso posterior de retención/eliminación de cuentas.

Crear por operación de BD un rol LOGIN dedicado, sin SUPERUSER/BYPASSRLS, sin membresías privilegiadas ni propiedad del esquema. Gestionar la contraseña fuera del código. Como propietario, ejecutar con psql y conexión segura configurada mediante PGHOST/PGPORT/PGDATABASE/PGUSER y `.pgpass` o gestor de secretos:

```sh
psql -v runtime_role=ROL_RUNTIME_REAL -f ops/configure-runtime-role.sql
```

El script concede solo operaciones de identidad necesarias con políticas RLS del backend confiable. El runtime no puede conceder ADMINISTRADOR ni cambiar estado/documento/email por SQL. No exponer `accesible` en Data API ni conceder permisos a anon/authenticated. Configurar `DB_USERNAME`/`DB_PASSWORD` con el rol runtime y `SPRING_FLYWAY_ENABLED=false`; quitar credenciales de migración del proceso. Desarrollo/pruebas con propietario mantienen Flyway habilitado.

## Identidad HTTP

| Método / ruta | Función |
|---|---|
| GET `/actuator/health` | Público; salud real de aplicación/BD sin detalles |
| PUT `/api/usuarios/me` | Completar alta después de Auth; 200 en creación/reintento; siempre CIUDADANO |
| GET `/api/usuarios/me` | Perfil propio; 404 si aún no existe |
| PATCH `/api/usuarios/me` | Solo nombres, apellidos, teléfono y distrito activo |

PUT es una extensión necesaria para el alta confiable: requiere nombres/apellidos, tipoDocumento (DNI/CE/PASAPORTE) y documento; teléfono/distrito opcionales. DNI: ocho dígitos. UUID/email vienen del JWT firmado. Reintentar el mismo UUID devuelve el perfil existente sin sobrescribirlo, incluso con concurrencia. Usuario y rol se insertan en una transacción; conflicto de documento/email devuelve 409 sin datos parciales. Si Auth se creó pero el perfil falló, reintentar PUT: no se borra automáticamente la cuenta Auth.

PATCH conserva campos null/omitidos y no permite modificar UUID, email, documento, estado, verificación o roles. JSON con propiedades desconocidas se rechaza. No hay ruta de perfiles ajenos. `/api/admin/**` ya exige ADMINISTRADOR, pero los controladores pertenecen a iteración 4: ciudadano recibe 403; administrador 404 hasta implementarlos.

Errores JSON uniformes: timestamp, status, code, message, details y traceId. 400 JSON/DTO, 401 JWT ausente/inválido, 403 suspensión/permisos, 404 inexistente, 409 conflicto, 415 formato y 422 distrito inválido. CORS se rechaza antes del controlador. No se devuelven errores SQL/tokens/DNI completo; se desactivan logs SQL de errores/bindings que puedan incluir datos personales.

`OPENAPI_ENABLED=true` habilita `/v3/api-docs` y `/swagger-ui/index.html`, protegidos por rol ADMINISTRADOR. Para cargar la UI se necesita un cliente/proxy que adjunte el Bearer también a sus recursos. Documentación apagada por defecto. Ejemplos en `requests.http`, siempre con tokens fuera del repositorio.

### Primer administrador

1. Crear la cuenta mediante Supabase Auth real y completar perfil con PUT; no insertar manualmente en `auth.users`.
2. Un operador de BD autorizado verifica la concesión. No hay contraseña/admin predeterminado ni endpoint de elevación.
3. Como propietario, usar UUID reales y motivo sin secretos/datos personales:

```sh
psql -v actor_id=UUID_OPERADOR_REAL -v usuario_id=UUID_DESTINO_REAL -v motivo="Alta autorizada por el responsable" -f ops/provision-admin.sql
```

Actor y destino deben tener perfiles activos. En bootstrap se permite el UUID del propio responsable como actor/destino, bajo control del operador de BD. La autorización procede de acceso operativo a BD. El script bloquea perfiles, concede rol y registra actor/destino/motivo/usuario de BD/fecha en la misma transacción. Reintentos no duplican auditoría. Permisos visibles en la siguiente petición sin renovar JWT. La administración HTTP y su auditoría quedan para iteración 4.

Un JWT emitido sigue siendo válido hasta expirar tras logout: esta fase verifica firma/claims y estado persistido, no revocación estricta por sesión. Definir validación contra sesiones/Auth y política de expiración si se necesita. Definir consentimiento, retención y responsables de datos antes de producción.

## Pruebas

`./mvnw test` ejecuta unitarias, ArchUnit, MockMvc y criptografía JWT contra JWKS local con claves efímeras de prueba. Con Docker ejecuta integración Testcontainers PostgreSQL 17. Sin Docker ni `TEST_DB_URL`, esa clase aparece omitida, no aprobada. CI usa `./mvnw -B verify -DrequireDocker=true` y falla si falta Docker.

Alternativa: establecer `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` de una base **desechable y exclusiva** y ejecutar `./mvnw verify`. Las pruebas crean una tabla mínima `auth.users` de fixture y truncan datos; **nunca apuntarlas a Supabase ni a datos reales**. No representan un servidor Auth.

En Windows con PostgreSQL instalado:

```powershell
.\ops\test-local-postgres.ps1
# Opcional: -PgBin 'ruta\PostgreSQL\17\bin' -Port 55440
```

El script inicializa una instancia temporal exclusiva con trust solo en loopback, ejecuta verify, detiene y limpia sus archivos; no modifica la instancia instalada. Alternativa Compose: definir contraseña efímera TEST_DB_PASSWORD, `docker compose -f docker-compose.test.yml up -d --wait`, TEST_DB_URL al puerto 55432/DB accesible_test y TEST_DB_USERNAME=accesible_test; finalizar con `docker compose -f docker-compose.test.yml down`. Testcontainers no requiere Compose.

Cobertura: firma JWT válida/incorrecta, issuer/audiencia/exp/nbf/UUID/anónimo/service_role, inyección de IDs/roles, suspensión, CORS, enmascaramiento, DTO, Flyway, FK/UNIQUE, rollback, alta concurrente, permisos persistidos, provisión auditada y RLS runtime.

## Próximo: HU-01

Se necesitan catálogos aprobados, límites geográficos y configuración real de proyecto/bucket privado `reportes-evidencias`. Implementar migraciones de reportes/fotos/historial, POST multipart, archivos/coordenadas, código transaccional ARQ-AAAA-NNNNNN, estado RECIBIDO, acceso privado y compensación de Storage con pruebas. No hay conexión Supabase real ni despliegue realizado en esta entrega.

Referencias: [JWT Supabase](https://supabase.com/docs/guides/auth/jwts), [Resource Server Spring Security 6.5](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html). El [cambio TLS de Supabase](https://supabase.com/changelog/cbc-tls-ciphers-deprecated) requiere clientes modernos; se usa HTTPS de Java 21.
