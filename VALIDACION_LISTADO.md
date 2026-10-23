# Listado paginado de reportes propios — 2026-10-23

Rama: `feat/listado-reportes`.

## Comportamiento

`GET /api/reportes` devuelve reportes del ciudadano autenticado. Filtros opcionales: estado, severidadDeclarada, tipoBarreraId, distritoId, desde/hasta y código exacto. Fechas inclusivas en America/Lima. Paginación por cursor con orden descendente por fecha e ID, límite predeterminado 20 y máximo 100. La respuesta contiene elementos, limite, hayMas y siguienteCursor, con `Cache-Control: no-store`.

El propietario es obligatorio en la consulta SQL y nunca se obtiene del cliente. El rol ADMINISTRADOR adicional no amplía el listado a otros ciudadanos. Parámetros desconocidos, duplicados, vacíos o inválidos devuelven 400. Las cuentas sin perfil ciudadano o suspendidas no pueden listar. No se cargan fotos, claves de Storage ni IDs de otros ciudadanos en los elementos del listado.

Una única consulta parametrizada trae hasta limite + 1 resúmenes. No hay OFFSET, conteo global ni consultas de fotos por cada fila. Se reutilizan tablas, índice por ciudadano/fecha y permisos existentes. No se requieren migraciones ni cambios de credenciales. Los catálogos inactivos no ocultan reportes históricos.

El cursor es una posición, no una credencial. Al cambiar filtros se debe iniciar sin cursor. No se ofrece una instantánea entre solicitudes: una transición de estado puede cambiar coincidencias durante la navegación. Las inserciones más recientes no desplazan la siguiente página ni repiten sus filas.

## Verificación ejecutada

```powershell
git switch -c feat/listado-reportes
.\mvnw.cmd -B -DskipTests compile
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
git diff --check
```

El script local de PostgreSQL se ejecutó tres veces: primero pasaron 98 pruebas, después 100 al completar casos de permisos y límites, y finalmente 100 tras afinar el rango indexable del cursor y comprobar una inserción con fecha anterior. La última ejecución terminó con 100 pruebas, cero errores, cero fallos y cero omitidas. Comprueba baseline 3 + V4 y una instalación limpia en bases desechables; nunca hace TRUNCATE ni crea fixtures en Supabase.

Cobertura añadida:

- Página vacía, tamaños 1/100 y rechazo de límites inválidos.
- Desempate por ID con la misma fecha y precisión de microsegundos; continuidad tras insertar reportes con fechas más recientes y anteriores.
- Cada filtro por separado, combinación de todos y límites de fecha en medianoche de Lima.
- Aislamiento entre ciudadanos, incluso con doble rol CIUDADANO/ADMINISTRADOR, y búsqueda de código ajeno sin resultados.
- Cuenta suspendida, perfil ausente y administrador sin rol ciudadano, tanto en API como en los controles de aplicación relevantes.
- Parámetros desconocidos, duplicados, vacíos, cursores malformados o fuera de rango.
- Catálogos desactivados y lectura con el rol runtime restringido.
- El listado sigue disponible cuando se deshabilitan nuevas cargas a Storage.

### Comprobación real de solo lectura

Se reinició el backend y se ejecutó un script Node enviado por stdin (`node --input-type=module`) con `fetch` y aserciones, reutilizando el token local sin imprimirlo. Evidencia privada: `.local/listado-real-proof.json`.

| Petición | Resultado |
| --- | --- |
| GET /api/reportes?limite=1 | 200; un elemento, ARQ-2026-000001; hayMas false. |
| Código ARQ-2026-000001 + RECIBIDO + BAJA + tipo/distrito 1 + día 2026-10-23 | 200; un elemento. |
| GET /api/reportes?estado=ATENDIDO | 200; página vacía. |
| Cursor en la posición del único reporte del piloto | 200; siguiente página vacía. |
| GET /api/reportes?limite=101 | 400. |
| GET /api/reportes?ciudadanoId=otro | 400. |
| GET /api/reportes sin token | 401. |
| GET /actuator/health | 200, UP. |

La prueba real no creó ni modificó reportes, usuarios ni objetos de Storage. La paginación entre varios ciudadanos y páginas se verificó en la base desechable; la base piloto conserva un único reporte técnico.

## Archivos modificados

- Dominio: `ConsultaReportes.java`, `EstadoReporte.java`, `ResumenReporte.java`, `ReporteException.java`, `ReporteRepositoryPort.java`.
- Aplicación: `ReportesUseCase.java`.
- Persistencia y seguridad: `JpaReporteAdapter.java`, `SecurityConfig.java`.
- HTTP: `ReportesController.java`, `ApiExceptionHandler.java`.
- Pruebas: `PostgresIntegrationTest.java`, `ReportesUseCaseTest.java`.
- Documentación y ejemplos: `README.md`, `requests.http`, este archivo.

Siguiente bloque: historial consultable. Administración, cambios de estado, KPI, IA y cuadrillas quedan pendientes.

Referencias: [orden estable con LIMIT](https://www.postgresql.org/docs/17/queries-limit.html), [índices compuestos](https://www.postgresql.org/docs/17/indexes-multicolumn.html).
