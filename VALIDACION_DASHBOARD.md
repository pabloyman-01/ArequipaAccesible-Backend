# Indicadores del dashboard — 2026-10-23

Rama: `feat/dashboard-indicadores`.

## Resultado

Se implementó GET `/api/admin/dashboard/resumen` con agregaciones reales de PostgreSQL, filtros opcionales desde/hasta/distritoId y autorización de administrador activo tanto en HTTP como en el caso de uso. La cuenta y sus roles se consultan en la base en cada petición. La respuesta no incluye datos personales, reportes individuales ni evidencias y usa Cache-Control: no-store.

El contrato incluye total, pendientes, atendidos, rechazados y distribuciones por estado, severidad declarada, tipo de barrera y distrito. Pendientes corresponde a los cuatro estados no terminales. Los filtros temporales corresponden a creación, con días inclusivos de America/Lima; los estados son los actuales, no una reconstrucción histórica al cierre del período. Ver README.md y requests.http para el contrato completo y ejemplos.

## Persistencia y configuración

Una sola sentencia parametrizada con GROUPING SETS calcula todas las distribuciones en la misma instantánea PostgreSQL. Los JOIN son exclusivamente contra claves primarias de catálogos; no se multiplican conteos por fotografías o eventos. Los catálogos inactivos conservan sus reportes históricos. Las seis claves de estado y las tres de severidad siempre aparecen; las categorías/distritos sin coincidencias no aparecen en los arreglos. Todos los conteos son long, incluido el total cero.

Se reutilizaron ConsultaReportes para validación y conversión de fechas, los permisos de administrador, el manejo uniforme de errores y la configuración runtime existente. No hay cambios de tablas, índices, RLS, secretos o permisos. Flyway continúa en V4; no se necesita una migración para una consulta de lectura sobre el esquema existente.

Se ejecutó EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) mediante el conector Supabase para la consulta con distrito y un día. Usó reportes_distrito_estado_idx, devolvió cinco filas agregadas a partir de un reporte y tuvo Execution Time de 0.315 ms, sin uso de disco temporal. La medición corresponde a un piloto de un reporte y al rol del conector: no es una prueba de carga ni una garantía de latencia HTTP. La prueba HTTP siguiente sí utiliza el rol runtime restringido del backend. El total exacto sin filtros requiere leer el conjunto de reportes; nuevos índices o resúmenes deben justificarse con volumen y planes representativos.

Referencia del SQL: [GROUPING SETS en PostgreSQL 17](https://www.postgresql.org/docs/17/queries-table-expressions.html#QUERIES-GROUPING-SETS).

## Comandos ejecutados y pruebas

```powershell
git switch -c feat/dashboard-indicadores
.\mvnw.cmd -B -DskipTests compile
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
Stop-Process -Id 9020
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
git diff --check
```

Compilación: BUILD SUCCESS. Maven verify: **140 pruebas, cero fallos, cero errores y cero omitidas**. Se comprobaron instalación limpia y adopción baseline 3 + V4 en PostgreSQL desechable. No se ejecutaron fixtures contra Supabase.

Las diez pruebas nuevas cubren resumen vacío, todos los estados/severidades, propietarios distintos, múltiples fotos/eventos sin duplicación, fechas con límites de microsegundos en Lima, rango máximo, filtros combinados, catálogo inactivo, entradas inválidas, acceso anónimo/ciudadano/suspendido, revocación inmediata, cambio de estado reflejado y consulta bajo rol runtime sin superusuario ni BYPASSRLS. La arquitectura hexagonal continúa verificada.

## Verificación con Supabase real

Con una sesión real ya confirmada, se ejecutó un script con fetch y aserciones mediante `node --input-type=module`, enviado por stdin. El token se leyó del archivo privado local, sin imprimirlo ni incorporarlo a Git.

- Resumen global: 200; total 1, pendientes 1, EN_EVALUACION 1, atendidos 0 y rechazados 0. Se comprobó que cada distribución suma el total y que no aparecen campos personales.
- Filtro 2026-10-23..2026-10-23 + distrito 1: 200, total 1.
- Distrito sin coincidencias y fecha hasta 2026-10-22: 200, total 0.
- Rango invertido, parámetro desconocido y distrito duplicado: 400.
- Sin token: 401. Todas las respuestas positivas llevan no-store.

El único reporte es ARQ-2026-000001, marcado expresamente como PRUEBA TECNICA: los conteos no representan una incidencia ciudadana real. Estas comprobaciones son de lectura y no crean reportes, cambian cuentas ni acceden a Storage. Evidencia local ignorada por Git: `.local/dashboard-real-proof.json`. El backend queda activo en el puerto 8080.

## Archivos nuevos y modificados

- Dominio: ConsultaDashboard.java, ResumenDashboard.java y DashboardRepositoryPort.java.
- Aplicación: DashboardUseCase.java.
- Persistencia/HTTP/configuración: JpaDashboardAdapter.java, DashboardController.java y AdministracionConfig.java.
- Pruebas: DashboardUseCaseTest.java y PostgresIntegrationTest.java.
- Documentación y evidencia de despliegue: README.md, requests.http, VALIDACION_DASHBOARD.md y deployment/supabase.json.

El siguiente trabajo puede conectar el endpoint con el frontend. Cuadrillas, asignaciones, notificaciones, exportaciones e IA son módulos opcionales posteriores y no se implementaron en este bloque.
