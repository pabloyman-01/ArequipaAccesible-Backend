# ArequipaAccesible-Backend

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.11 y PostgreSQL 17. Implementa **iteraciones 0, 1 y 2 (HU-01)** de la guía técnica del 22/10/2026. Reutiliza la base de identidad existente. Ramas: `feat/iteraciones-0-1` y `feat/hu-01`.

## Implementado

- Base Maven, healthcheck real, Flyway, logs ECS, configuración por entorno y CI.
- Dominio/aplicación sin Spring ni JPA, puertos y adaptadores JPA/HTTP. ArchUnit verifica esta separación.
- JWT Supabase: firma RS256/ES256 mediante JWKS, issuer, audiencia, expiración, nbf, subject UUID y usuario no anónimo. No se emiten JWT propios ni se guardan contraseñas.
- Roles y estado de cuenta leídos de PostgreSQL en cada petición; nunca de `user_metadata` ni IDs/roles del cliente.
- Perfil propio con alta idempotente, edición limitada y documento enmascarado. Verificación inicial NO_VERIFICADO, sin afirmar validación RENIEC.
- Provisión administrativa mediante operación de BD, con auditoría y atomicidad.
- Esquema privado `accesible`, RLS y sin acceso al Data API. Migraciones V1 (identidad/catálogos/auditoría) y V2 (vinculación Auth).

HU-01 añade registro y detalle de reportes, fotos privadas, historial inicial, auditoría, catálogos públicos activos y recuperación de archivos huérfanos. La continuación incorpora el listado paginado de reportes propios con filtros y la consulta del historial por propietario o administrador. Quedan pendientes cambios de estado, administración HTTP, KPI, IA y cuadrillas. El piloto usa catálogos internos aprobados mediante V4; las pruebas usan catálogos explícitamente ficticios.

Proyecto Supabase creado por autorización del usuario: [ArequipaAccesible-Backend](https://supabase.com/dashboard/project/kjqzklzswocxbtbqohsc), São Paulo. Se provisionaron 12 tablas privadas, FK de identidad, bucket privado y rol `arequipa_backend` con RLS. Sus credenciales no están en el repositorio. Ver la adopción de Flyway y configuración de HU-01 al final.

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

Identidad no requiere claves de Storage. HU-01 necesita una secret key **solo de servidor** para el adaptador HTTP; preferir `sb_secret_...` (header apikey), con compatibilidad para service_role JWT legacy (apikey + Bearer). Nunca reenviar el JWT del ciudadano como credencial de Storage. Registro, confirmación de correo, login, recuperación y logout corresponden al cliente y Supabase Auth.

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

El script concede solo operaciones de identidad/HU-01 necesarias con políticas RLS del backend confiable. El runtime no puede conceder ADMINISTRADOR, cambiar estado/documento/email ni actualizar/eliminar reportes por SQL. No exponer `accesible` en Data API ni conceder permisos a anon/authenticated. Configurar `DB_USERNAME`/`DB_PASSWORD` con el rol runtime y `SPRING_FLYWAY_ENABLED=false`; quitar credenciales de migración del proceso. Desarrollo/pruebas con propietario mantienen Flyway habilitado. `ops/create-runtime-role.sql` crea el rol del proyecto sin contraseña; asignar su contraseña real por gestión segura de secretos.

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

## HU-01: reporte con evidencia privada

| Método / ruta | Función |
|---|---|
| GET `/api/catalogos/distritos` | Público; únicamente distritos activos |
| GET `/api/catalogos/tipos-barrera` | Público; únicamente tipos activos |
| POST `/api/reportes` | CIUDADANO activo; multipart `datos` JSON + una sola `foto` |
| GET `/api/reportes/{id}` | Propietario o administrador; 404 también para reportes ajenos |
| GET `/api/reportes/{id}/fotos/{fotoId}` | Propietario o administrador; bytes con no-store; sin URL pública |

POST recibe el contrato de la guía en `requests.http` y responde 201, Location y `{id,codigo,estado,fechaCreacion}`. Ciudadano, estado y fechas nunca vienen del cliente. ADMINISTRADOR solo puede crear si también tiene rol CIUDADANO. Severidad admitida: BAJA/MEDIA/ALTA. Descripción no vacía hasta 3000 caracteres y dirección hasta 500. IDs de catálogos activos; coordenadas dentro del intervalo general y el área configurada. Precisión hasta siete decimales. No se afirma que un bounding box sea un límite administrativo oficial.

Foto JPEG/PNG real: MIME, firma, terminación y decodificación coincidentes; hasta 5 MiB, hasta 25 millones de píxeles, exactamente una imagen. El nombre original no se utiliza. Las claves aleatorias `evidencias/<UUID>.png|jpg` solo se guardan como object_key; el detalle HTTP publica metadatos y enlace autorizado, nunca la clave de Storage. Se comprueba que el bucket siga siendo privado antes de subir/descargar. Adaptador con TLS, sin redirects, timeout y respuesta acotada. Los catálogos y el detalle no fingen resultados de IA ni integraciones oficiales.

El código ARQ-AAAA-NNNNNN usa un contador por año bloqueado y actualizado dentro de la transacción; año de America/Lima, máximo 999999 al año (409 al agotarse). Reporte, foto, historial RECIBIDO, auditoría y confirmación de carga se confirman juntos. No hay endpoint para cambiar estados en esta fase. Límite persistido de cinco intentos de carga válidos por ciudadano/minuto, configurable 1..60: es una ventana fija compartida entre instancias, no un contador de memoria; 429 con Retry-After. El proxy debe limitar también tamaño, concurrencia y tráfico antes de autenticación.

### Storage y compensación

Antes de llamar a Storage se confirma un registro en `archivo_cargas`. Si falla el INSERT posterior a una carga confirmada se intenta borrar el objeto, bajo bloqueo y comprobación de que no esté referenciado. Si COMMIT sí ocurrió pero se perdió su respuesta, la intención ya no existe y no se borra la evidencia. Un fallo/timeout ambiguo de subida conserva la intención, para no competir con una carga aún en curso. Caídas del proceso también dejan una limpieza recuperable.

El worker revisa cada minuto hasta 20 intenciones vencidas (30 minutos de margen); bloquea cada registro y borra solo objetos sin referencia. Si falla la eliminación conserva la intención, incrementa intentos y reprograma 15 minutos. Varios workers se serializan por fila. Monitorizar pendientes/antigüedad/intentos en operación; no borrar filas manualmente antes de limpiar el objeto. Los objetos se borran por Storage API, nunca por DELETE directo de `storage.objects`.

### Activación real

1. Definir credenciales JDBC legítimas del propietario/runtime y `SUPABASE_STORAGE_SECRET_KEY` desde el gestor de secretos. El conector no entrega contraseñas JDBC ni secret keys; no se inventaron valores. La URL/issuer/JWKS públicos del proyecto real están en `.env.example`; JWKS ES256 comprobado.
2. **Solo para el proyecto ya provisionado mediante el conector**: las fuentes V1..V3 se aplicaron con historial de Supabase, antes de tener credenciales JDBC. Tras verificar ese esquema y antes del primer arranque Flyway, el propietario debe adoptarlo una vez:

```sh
./mvnw flyway:baseline -Dflyway.baselineVersion=3
./mvnw flyway:migrate flyway:validate
```

No usar baseline para una base vacía ni para ocultar diferencias. Bases nuevas ajenas al proyecto conectado ejecutan simplemente flyway:migrate, que aplica V1..V4. El script local con `-CheckBootstrap` verifica ambas vías en bases desechables. No se modificó manualmente `flyway_schema_history`. Después de la adopción, nuevas versiones se gestionan exclusivamente con Flyway para mantener una sola autoridad de migraciones de aplicación. El bucket se configura aparte mediante `ops/supabase-private-bucket.sql` (Supabase únicamente).

3. Cargar catálogos aprobados por operación/migración revisada; no hay semillas oficiales inventadas. Definir REPORTES_LAT_MIN/MAX y REPORTES_LON_MIN/MAX según el área aprobada. Sin esos datos no se habilita el registro.
4. `REPORTES_HABILITADO=true` habilita el adaptador real y exige URL HTTPS, clave y área válida al arrancar. Por defecto false: POST devuelve 503, preservando identidad y salud operativas. No existe un Storage falso en producción.

La activación local ya usa JDBC real con `sslmode=verify-full` y el certificado CA oficial de Supabase. El backend arranca con el rol runtime restringido, sin Flyway en ejecución. El baseline 3 y la migración V4 se aplicaron y validaron mediante JDBC; el rol temporal de migración se eliminó después. `SUPABASE_STORAGE_SECRET_KEY` nunca va al cliente. Bucket `reportes-evidencias` privado con límite 5242880 bytes y MIME JPEG/PNG; sin políticas de acceso directo anon/authenticated. El 2026-10-23 se verificó el flujo real con una cuenta confirmada: reporte técnico ARQ-2026-000001, imagen en Storage y lectura autorizada con bytes idénticos; véase `VALIDACION_ACTIVACION.md`.

V4 carga el piloto aprobado: Arequipa (Cercado) y seis categorías internas `PROY-01` a `PROY-06`. El área operativa aprobada es latitud -16.43 a -16.36 y longitud -71.57 a -71.50; no constituye un límite municipal oficial. `.env.local` y `.local/` están excluidos de Git.

En este equipo, cargar las variables sin imprimir secretos y arrancar:

```powershell
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
```

Para confirmar una sesión real por correo, definir localmente `ACTIVATION_EMAIL` y `SUPABASE_PUBLISHABLE_KEY` y ejecutar `node ops/activation-auth.mjs`. Requiere el redirect local permitido en Supabase. El enlace debe abrirse en el mismo equipo; el callback conserva únicamente la sesión en `.local/auth-session.json`, sin imprimir tokens. Una sesión capturada se valida contra Supabase y se reutiliza sin enviar otro correo. `ACTIVATION_SEND_EMAIL=false` impide el envío al reiniciar el helper. No reiniciarlo repetidamente para solicitar enlaces: Auth aplica límites de correo.

Con el backend activo y una sesión real capturada:

```powershell
. .\ops\load-local-env.ps1
node ops/verify-hu01-real.mjs
```

Si falta el perfil, el verificador abre un servidor exclusivo en loopback: visitar `http://localhost:3001` e introducir los datos personalmente. No se solicitan documentos por chat ni se inventan datos de identidad. El formulario usa nonce y comprobaciones de Host/Origin; el servidor se cierra al guardar. El perfil conserva el estado NO_VERIFICADO: confirmar un correo no verifica un documento.

Después crea un reporte identificado como PRUEBA TECNICA, con una imagen PNG sintética de comprobación. No corresponde a una incidencia real. Verifica creación 201, consultas 200, coincidencia SHA-256, `no-store` y acceso sin token 401. La evidencia local `.local/hu01-real-proof.json` evita repetir el POST: siguientes ejecuciones vuelven a consultar el mismo reporte. Ante un POST ambiguo, revisar el intento y la base antes de reintentar. Los helpers no son un frontend desplegable; detenerlos y eliminar la sesión local al terminar la prueba.

Pruebas adicionales: use case/compensación, firmas/decode de imagen, API HTTP de Storage contra servidor de prueba, límites de respuesta/red, integración real de transacciones, IDOR, códigos concurrentes, limpieza diferida y flujo completo con rol runtime restringido. La base real conserva un único reporte técnico de validación, explícitamente identificado como tal.

```powershell
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
```

### Listado de reportes propios

`GET /api/reportes` requiere un ciudadano activo y devuelve exclusivamente sus reportes. Una cuenta con roles CIUDADANO y ADMINISTRADOR sigue viendo solo sus propios reportes en este endpoint. El propietario se obtiene del JWT validado y los roles de la base. No se admite seleccionar un ciudadano mediante parámetros.

| Parámetro | Contrato |
| --- | --- |
| `limite` | Entero de 1 a 100; predeterminado 20. |
| `cursor` | Usar `siguienteCursor` de la respuesta anterior, conservando los filtros. Omitirlo para comenzar de nuevo. |
| `estado` | RECIBIDO, EN_EVALUACION, ASIGNADO, EN_PROCESO, ATENDIDO o RECHAZADO. |
| `severidadDeclarada` | BAJA, MEDIA o ALTA. |
| `tipoBarreraId`, `distritoId` | IDs positivos; un ID sin coincidencias devuelve una página vacía. Los reportes históricos siguen consultándose aunque su catálogo esté inactivo. |
| `desde`, `hasta` | Fechas ISO `AAAA-MM-DD`, inclusivas en America/Lima, entre años 2000 y 9999. `desde` no puede superar a `hasta`. |
| `codigo` | Código exacto ARQ-AAAA-NNNNNN; admite minúsculas y espacios exteriores. |

Todos los filtros son opcionales y se combinan con AND. Parámetros desconocidos, duplicados o vacíos, cursor inválido y límites fuera de rango devuelven 400. El endpoint no depende de que estén habilitadas nuevas cargas a Storage.

Respuesta 200: `{elementos: [...], limite: 20, hayMas: false, siguienteCursor: null}`. Cada elemento contiene id, codigo, tipoBarreraId, distritoId, severidadDeclarada, descripcion, direccionReferencia, estado y fechaCreacion. El detalle y la evidencia conservan sus endpoints existentes. La respuesta lleva `Cache-Control: no-store`.

La consulta usa parámetros vinculados, propietario obligatorio y orden `fecha_creacion DESC, id DESC`. Solicita como máximo `limite + 1` filas, sin OFFSET, COUNT global ni consultas de fotos por reporte. Reutiliza el índice existente `(ciudadano_id, fecha_creacion DESC)`; no requiere migraciones ni nuevos permisos. El cursor representa una posición y no concede permisos. La paginación evita repetir resultados cuando se insertan nuevos reportes más recientes, pero no constituye una instantánea histórica entre peticiones: cambios de estado o de filtros pueden cambiar las coincidencias. Al cambiar filtros, comenzar sin cursor.

### Historial de un reporte

`GET /api/reportes/{id}/historial` requiere un perfil activo. El propietario o un ADMINISTRADOR puede consultarlo; otro ciudadano recibe el mismo 404 que un reporte inexistente. Los roles y el estado de la cuenta se consultan en la base en cada petición, por lo que revocar el rol administrativo o suspender la cuenta afecta al siguiente acceso.

Devuelve un arreglo con `id`, `estadoAnterior`, `estadoNuevo`, `fechaCambio` y `motivo`, ordenado por `fechaCambio ASC, id ASC`. Las fechas son instantes ISO en UTC. En el evento inicial RECIBIDO, estadoAnterior y motivo pueden ser null. Un reporte existente sin eventos devuelve 200 con `[]`. La respuesta lleva `Cache-Control: no-store` y no expone UUID, nombres ni documento del actor, datos de auditoría o claves de fotos.

Se reutilizan la tabla `historial_estados`, el índice por reporte/fecha y los permisos SELECT existentes. La autorización se verifica antes de consultar los eventos y también se aplica en su consulta. No se consultan fotos ni Storage y no se requiere migración. El historial permanece disponible si se deshabilitan nuevas cargas. Este endpoint es de lectura: las transiciones y la escritura de motivos se implementarán en el bloque administrativo; las pruebas de varios estados usan fixtures exclusivamente en la base desechable.

Siguiente iteración: administración y transiciones de estado. La lectura segura de la evidencia se incluyó junto a HU-01 para cerrar el ciclo mínimo del reporte.

Referencias: [JWT Supabase](https://supabase.com/docs/guides/auth/jwts), [Resource Server Spring Security 6.5](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html). El [cambio TLS de Supabase](https://supabase.com/changelog/cbc-tls-ciphers-deprecated) requiere clientes modernos; se usa HTTPS de Java 21.
