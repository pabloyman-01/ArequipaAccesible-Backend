# Entrega de iteraciones 0 y 1

Fecha: 22/10/2026 (America/Lima). Base inspeccionada: commit `eba4b37` de `main`, únicamente README. Remoto conservado: https://github.com/pabloyman-01/ArequipaAccesible-Backend.git. Trabajo en `codex/iteraciones-0-1`.

## Especificación y alcance

El archivo solicitado `Revisión del backend.pdf` contiene una captura de una página de chat con la portada de otra guía, no la especificación íntegra. Se encontró en la misma carpeta Descargas `Arequipa_Accesible_Guia_Backend_para_Codex.pdf` y se leyeron sus nueve páginas completas. Se distinguieron los textos del chat capturado de la solicitud actual: se implementaron solo las iteraciones 0 y 1, no el MVP entero ni HU-01.

Entregado: proyecto Java 21/Spring Boot 3.x con Maven Wrapper, arquitectura hexagonal, Actuator, entorno sin secretos, migraciones Flyway, identidad JWT Supabase/JWKS, GET/PUT/PATCH propio, permisos persistidos, suspensión, alta administrativa auditada, configuración RLS/runtime, ejemplos HTTP, OpenAPI y CI.

Sin implementar por alcance: reportes/fotos/Storage/historial, catálogos HTTP, administración HTTP, dashboard y servicios posteriores. No se crearon catálogos oficiales ficticios, credenciales, proyectos Supabase ni servicios gubernamentales.

## Comandos realmente ejecutados

Todos los comandos Maven del proyecto se ejecutaron en `D:\code\ArequipaAccesible-Backend`. Los valores de identidad usados para smoke test son dominios reservados `.example.test`; no representan un servicio Supabase real.

| Comando / operación | Resultado |
|---|---|
| GitHub plugin: lectura de metadatos del repositorio | Repositorio accesible, permisos push confirmados |
| `git clone https://github.com/pabloyman-01/ArequipaAccesible-Backend.git` | Clonado en el workspace |
| `git status --short --branch`, `git remote -v`, `git log -1 --oneline`, `git ls-files`, `rg --files` | Inspección antes de editar; solo README, sin código reutilizable ni AGENTS.md aplicable |
| Lectura/render de PDF con PyMuPDF | Captura revisada visualmente y texto íntegro de guía de nueve páginas leído. Python solo como herramienta de lectura, no backend |
| `java -version`, `mvn -version`, `git --version`, `gh --version` | Java 21.0.8, Maven 3.9.11 y herramientas Git disponibles |
| `docker --version` | Docker no instalado; no se afirma haber ejecutado Testcontainers localmente |
| `pg_ctl --version` | PostgreSQL 17.5 instalado, usado en instancias temporales aisladas |
| `git switch -c codex/iteraciones-0-1` | Rama creada, sin modificar main |
| `mvn -N wrapper:wrapper -Dmaven=3.9.11 -Dtype=only-script` | Primer intento sin POM falló; tras crear POM generó el wrapper correctamente |
| `.\mvnw.cmd -B test` | Detectó inicialmente un error de tipado del adaptador y luego una configuración CORS; ambos corregidos |
| `initdb -D D:\code\tmp\arequipa-postgres-test -U codex_test -A trust --encoding=UTF8 --locale=C` | Instancia exclusivamente local de prueba, sin credenciales de servicios reales |
| `pg_ctl ... -o "-h 127.0.0.1 -p 55439" -w start`, `createdb ... accesible_test` | PostgreSQL temporal iniciado; migraciones/integración ejecutadas contra BD real |
| Variables TEST_DB_URL/USERNAME/PASSWORD locales y `.\mvnw.cmd -B verify` | Sucesivas verificaciones detectaron un conteo de migraciones (marca interna de schema de Flyway) y limpieza de conexiones del fixture; corregidos. Después 44 pruebas pasaron |
| `createdb ... accesible_smoke`; variables DB_URL/MIGRATION_DB_USERNAME/PASSWORD locales; `.\mvnw.cmd -B flyway:migrate flyway:validate` | V1/V2 aplicadas y validadas sobre otra base vacía, sin fixture Auth; BUILD SUCCESS |
| `psql ... -d accesible_smoke -v runtime_role=accesible_runtime_test -f ops/configure-runtime-role.sql` | Permisos/RLS del rol runtime aplicados; COMMIT |
| Variables locales de BD runtime, issuer/JWKS de prueba, HTTPS deshabilitado solo localmente, puerto 18087; `.\mvnw.cmd -B spring-boot:run` | Arranque real con rol restringido, JPA validate y Flyway previamente aplicado |
| `Invoke-WebRequest http://127.0.0.1:18087/actuator/health` | HTTP 200, `{"status":"UP"}` |
| `Invoke-WebRequest http://127.0.0.1:18087/api/usuarios/me -SkipHttpErrorCheck` | HTTP 401 y JSON NO_AUTENTICADO sin JWT |
| `Stop-Process` del PID de la aplicación de smoke test; `pg_ctl ... -m fast -w stop` | Aplicación y primera instancia temporal detenidas |
| `.\ops\test-local-postgres.ps1 -Port 55440` | Se corrigió la herencia de pipes de pg_ctl en Windows y una carrera intermitente de alta. Se añadió bloqueo transaccional por UUID y cinco repeticiones del escenario concurrente |
| `git diff --check` y revisión de configuraciones/secretos | Sin errores de whitespace; variables de credenciales vacías, sin valores reales |

Las pruebas criptográficas usan claves RSA efímeras y un JWKS HTTP de loopback. Integración usa una tabla Auth mínima explícitamente de fixture; no verifica un proyecto remoto. Los scripts de administrador y permisos se ejecutan también en integración, probando atomicidad/auditoría/idempotencia y denegación de elevación por runtime.

Resultado final del script aislado: **BUILD SUCCESS, 48 pruebas, 0 fallos, 0 errores, 0 omitidas**. Distribución: 6 casos de uso, 1 arquitectura, 10 JWT, 19 integración PostgreSQL (incluidas cinco repeticiones concurrentes) y 12 web. El script detuvo PostgreSQL y eliminó su directorio temporal correctamente. JAR ejecutable generado en `target/accesible-0.1.0-SNAPSHOT.jar` (ignorado por Git).

## Límites y próximos pasos

- Para un despliegue real faltan los datos legítimos de conexión/issuer/JWKS/CORS y el operador responsable del primer administrador; el código no contiene credenciales inventadas.
- CI contiene Testcontainers obligatorio; localmente se verificó con PostgreSQL 17 instalado. No se sustituye PostgreSQL por H2/SQLite.
- La política institucional de consentimiento/retención y revocación estricta por sesión queda por definir; JWT expira y suspensión se consulta por petición.
- Siguiente trabajo: HU-01 con catálogos aprobados, límites geográficos, bucket privado real, migraciones de reportes/fotos/historial, creación multipart y compensación de Storage.

## Archivos modificados y añadidos

- `.env.example`
- `.gitattributes`
- `.github/workflows/tests.yml`
- `.gitignore`
- `.mvn/wrapper/maven-wrapper.properties`
- `docker-compose.test.yml`
- `mvnw`
- `mvnw.cmd`
- `ops/configure-runtime-role.sql`
- `ops/provision-admin.sql`
- `ops/test-local-postgres.ps1`
- `pom.xml`
- `README.md`
- `requests.http`
- `src/main/java/pe/arequipa/accesible/AccesibleApplication.java`
- `src/main/java/pe/arequipa/accesible/application/command/ActualizarPerfil.java`
- `src/main/java/pe/arequipa/accesible/application/command/DatosPerfil.java`
- `src/main/java/pe/arequipa/accesible/application/usecase/PerfilUseCase.java`
- `src/main/java/pe/arequipa/accesible/domain/model/PerfilException.java`
- `src/main/java/pe/arequipa/accesible/domain/model/Rol.java`
- `src/main/java/pe/arequipa/accesible/domain/model/UsuarioPerfil.java`
- `src/main/java/pe/arequipa/accesible/domain/port/out/UsuarioRepositoryPort.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/config/ApplicationConfig.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/persistence/JpaUsuarioAdapter.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/persistence/UsuarioEntity.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/security/DatabaseJwtConverter.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/security/JwtConfig.java`
- `src/main/java/pe/arequipa/accesible/infrastructure/security/SecurityConfig.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/ApiError.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/ApiExceptionHandler.java`
- `src/main/java/pe/arequipa/accesible/presentation/rest/PerfilController.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__identidad.sql`
- `src/main/resources/db/migration/V2__supabase_identity_link.sql`
- `src/test/java/pe/arequipa/accesible/application/PerfilUseCaseTest.java`
- `src/test/java/pe/arequipa/accesible/ArchitectureTest.java`
- `src/test/java/pe/arequipa/accesible/infrastructure/security/JwtVerificationTest.java`
- `src/test/java/pe/arequipa/accesible/PostgresIntegrationTest.java`
- `src/test/java/pe/arequipa/accesible/presentation/PerfilWebTest.java`
- `VALIDACION.md`
