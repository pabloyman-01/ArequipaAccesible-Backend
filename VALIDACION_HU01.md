# Entrega HU-01

Fecha: 22/10/2026, America/Lima. Base: commit `26a7805`, revisado antes de editar. Se reutilizaron identidad, roles persistidos, DTO/errores, JPA, Flyway, Maven Wrapper, CI y pruebas existentes.

## Ramas

Por indicación del usuario se renombró la rama anterior a `feat/iteraciones-0-1`, se subió ese nombre y se retiró su antiguo nombre remoto. HU-01 se desarrolla en `feat/hu-01`. No se reescribió el historial ni se fusionó a main.

## Implementación

- POST multipart `datos` + una `foto`: ciudadano activo, 201/Location, código ARQ-AAAA-NNNNNN y RECIBIDO.
- Validación de texto, catálogos activos, coordenadas/área configurable, JPEG/PNG real, MIME/firma/decode, 5 MiB y límite de píxeles; rechazo de identidad/estado del cliente.
- GET detalle y foto autorizados por propietario/administrador, 404 uniforme ante acceso ajeno y no-store en evidencia. No se expone object_key.
- Catálogos públicos activos, sin inventar datos oficiales.
- Reporte/foto/historial inicial/auditoría confirmados atómicamente. Contador anual transaccional con pruebas concurrentes.
- Storage HTTP real, bucket privado comprobado, secretos solo de servidor, sin upsert/redirects, timeout y respuesta acotada.
- Intención de carga persistida antes del HTTP, compensación protegida y worker de recuperación tras fallo/red/crash. No se borra evidencia de un COMMIT exitoso con respuesta perdida.
- Límite por ciudadano/minuto persistido en PostgreSQL (429), compartido entre instancias.
- V3 nueva, V1/V2 originales conservadas. Permisos y RLS actualizados para runtime sin elevación ni cambios de estado.

## Supabase real creado

El usuario solicitó crear la base y eligió explícitamente «JOL25's Org» y São Paulo. La herramienta de creación no solicitó confirmación de coste adicional.

- [Proyecto ArequipaAccesible-Backend](https://supabase.com/dashboard/project/kjqzklzswocxbtbqohsc), ID `kjqzklzswocxbtbqohsc`, región `sa-east-1`, ACTIVE_HEALTHY al crear.
- URL real obtenida del conector: https://kjqzklzswocxbtbqohsc.supabase.co. JWKS público comprobado: EC/ES256/sig.
- Se inspeccionaron tablas/esquemas antes de escribir. Se aplicaron por conector las fuentes V1..V3 y el bucket en una migración de bootstrap, y después el rol/runtime y sus políticas.
- Historial Supabase: `20261022235926 bootstrap_iteraciones_0_1_hu01`, `20261023000029 backend_runtime_permissions_hu01` (versiones UTC del servicio).
- Consulta real confirmó 12 tablas, todas con RLS; FK `usuarios_auth_fk`; bucket privado `reportes-evidencias`, 5242880 bytes, MIME JPEG/PNG; anon/authenticated sin USAGE al esquema; cero reportes ficticios; runtime LOGIN sin SUPERUSER ni BYPASSRLS.
- `arequipa_backend` creado sin contraseña en SQL: ninguna credencial inventada/guardada. Hace falta asignar su contraseña real mediante gestión segura antes de usar JDBC.
- Advisors de seguridad finales: sin avisos. Performance solo informa [índices aún sin uso](https://supabase.com/docs/guides/database/database-linter?lint=0005_unused_index), esperable en una base nueva; se conservan índices necesarios para FKs y consultas. Los avisos iniciales de RLS sin políticas desaparecieron al configurar runtime; no se abrieron políticas a clientes para silenciarlos.
- [deployment/supabase.json](deployment/supabase.json) contiene identificación no secreta y SHA-256 de las fuentes para trazabilidad.

Como no se dispone de credenciales JDBC a través del conector, el esquema remoto fue inicializado con historial Supabase. **Antes de usar Flyway en ese proyecto, el propietario debe verificar/adoptar una vez la versión 3 con `flyway:baseline -Dflyway.baselineVersion=3`**. No se fabricó ni editó a mano flyway_schema_history. Después se usa exclusivamente Flyway para nuevas migraciones de aplicación. Este procedimiento de adopción se probó localmente; el baseline remoto queda pendiente de la conexión legítima. En bases vacías normales solo se usa migrate, sin baseline.

## Comandos y herramientas realmente ejecutados

| Comando / herramienta | Resultado |
|---|---|
| `git status --short --branch`, `git log -2 --oneline`, lectura de README/config/seguridad/JPA/pruebas y búsqueda AGENTS.md | Inspección previa, checkout limpio; reutilización de capas existentes |
| `git branch -m feat/iteraciones-0-1`; `git push -u origin feat/iteraciones-0-1`; eliminación del nombre anterior; `git switch -c feat/hu-01` | Nombres corregidos según preferencia del usuario |
| Lectura completa de páginas relevantes de guía técnica con PyMuPDF | HU-01 y contratos/criterios revisados; Python no forma parte del backend |
| Supabase `list_projects`, `list_organizations`, `create_project` | Proyecto creado en organización/región elegidas por el usuario |
| Supabase `search_docs`, documentación/changelog/API pública oficial | Contratos HTTP de Storage y configuración de secret keys verificados |
| `.\mvnw.cmd -B test` sin Docker | Compilación/unitarias/web aprobadas; integración omitida en esa ejecución, sin presentarla como aprobada |
| `.\ops\test-local-postgres.ps1 -Port 55440` | Pruebas completas en instancia PostgreSQL 17.5 exclusiva, creadas y eliminadas por el script |
| Supabase `list_tables`, `apply_migration` bootstrap y runtime | Esquema, bucket, FK, rol y políticas provisionados en el proyecto real |
| Supabase `execute_sql`, `get_advisors` security/performance, `list_migrations`, `get_project_url` | Verificaciones reales detalladas arriba; no se cargaron datos de prueba |
| `Invoke-RestMethod .../auth/v1/.well-known/jwks.json` | Publicación ES256 confirmada, sin secretos |
| `.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap` | Bootstrap SQL en segunda BD, baseline 3, validate/migrate sin duplicar tablas, y suite completa desde base vacía: todos BUILD SUCCESS |
| `.\mvnw.cmd -B -Dtest=SupabaseStorageAdapterTest test` | Ocho pruebas HTTP específicas aprobadas tras reforzar assertions de rutas/headers del contrato |
| `git diff --check` y revisión de configuración | Sin errores de whitespace; sin credenciales reales en archivos |

Resultado completo: **88 pruebas, 0 fallos, 0 errores, 0 omitidas**. Distribución: 6 identidad, 8 caso de uso de reportes, 1 arquitectura, 10 JWT, 6 imágenes, 8 Storage HTTP, 37 integración PostgreSQL y 12 web de identidad. Las 37 de integración incluyen APIs multipart/IDOR, rollback real tras fallo de auditoría, compensación/reintento, concurrencia de altas/códigos y creación con rol runtime restringido.

Las pruebas de Storage usan un servidor HTTP local explícitamente de prueba para éxito, privacidad, errores/red y límites; no se afirma haber subido una foto a Supabase real sin secret key. PostgreSQL local usa trust solo para una instancia temporal en loopback; no modifica la instalación normal. El script detuvo servidores y eliminó sus directorios. El JAR compilable se genera en target, ignorado por Git.

## Pendientes de activación y alcance siguiente

Asignar credenciales JDBC y secret key de servidor por gestor de secretos, adoptar Flyway remoto, aprobar/importar catálogos y definir límites geográficos. Entonces activar REPORTES_HABILITADO=true. Por defecto permanece false y falla cerrado con 503; no existe adaptador falso en producción. No se desplegó un servidor backend remoto ni se crearon usuarios Auth de prueba.

Listados propios paginados, filtros e historial consultable corresponden a iteración 3; transiciones/admin/KPI a posteriores. La lectura privada de fotos se incluyó con HU-01 para completar el ciclo mínimo de evidencia.

## Archivos modificados y añadidos respecto de la base

- `.env.example`
- `deployment/supabase.json`
- `ops/configure-runtime-role.sql`
- `ops/create-runtime-role.sql`
- `ops/supabase-private-bucket.sql`
- `ops/test-local-postgres.ps1`
- `README.md`
- `requests.http`
- `src/main/java/pe/arequipa/accesible/application/usecase/ReportesUseCase.java`
- `src/main/java/pe/arequipa/accesible/domain/model/AreaPermitida.java`
- `src/main/java/pe/arequipa/accesible/domain/model/CrearReporte.java`
- `src/main/java/pe/arequipa/accesible/domain/model/ImagenValidada.java`
- `src/main/java/pe/arequipa/accesible/domain/model/Reporte.java`
- `src/main/java/pe/arequipa/accesible/domain/model/ReporteException.java`
- `src/main/java/pe/arequipa/accesible/domain/model/Severidad.java`
- `src/main/java/pe/arequipa/accesible/domain/port/out/ArchivoStoragePort.java`
- `src/main/java/pe/arequipa/accesible/domain/port/out/ImagenValidatorPort.java`
- `src/main/java/pe/arequipa/accesible/domain/port/out/ReporteRepositoryPort.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/config/ReportesConfig.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/persistence/FotoEntity.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/persistence/JpaReporteAdapter.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/persistence/ReporteEntity.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/security/SecurityConfig.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/storage/ImagenValidator.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/storage/LimpiezaArchivos.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/storage/SupabaseStorageAdapter.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/ApiExceptionHandler.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/CatalogosController.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/ReportesController.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V3__hu01_reportes.sql`
- `src/test/java/pe/arequipa/accesible/application/ReportesUseCaseTest.java`
- `src/test/java/pe/arequipa/accesible/infrastructure/storage/ImagenValidatorTest.java`
- `src/test/java/pe/arequipa/accesible/infrastructure/storage/SupabaseStorageAdapterTest.java`
- `src/test/java/pe/arequipa/accesible/PostgresIntegrationTest.java`
- `VALIDACION_HU01.md`
- `VALIDACION.md`
