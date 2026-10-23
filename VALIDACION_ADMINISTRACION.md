# Administración de reportes y cuentas — 2026-10-23

Rama: `feat/administracion`.

## Implementado

- GET /api/admin/reportes: listado global con cursor, filtros existentes y ciudadanoId opcional. El listado ciudadano sigue mostrando únicamente reportes propios.
- PATCH /api/admin/reportes/{id}/estado: exige estadoEsperado y nuevoEstado; motivo obligatorio al rechazar. Actualización, historial y auditoría en la misma transacción.
- GET /api/admin/usuarios: paginación por UUID y filtros estadoCuenta/rol/distritoId; documentos enmascarados y roles agrupados por página.
- PATCH /api/admin/usuarios/{id}/estado: activación/suspensión con motivo, estado esperado y auditoría. Rechaza autosuspensión, serializa cambios entre administradores y hace efectivo el estado de cuenta en la siguiente petición.

Las cuatro rutas exigen un administrador activo, con permisos procedentes de PostgreSQL. No se implementa elevación de roles por HTTP. Se conserva el procedimiento operativo auditado para nominar responsables.

Reglas tomadas de la sección 7 de la guía técnica: RECIBIDO -> EN_EVALUACION; EN_EVALUACION -> EN_PROCESO o RECHAZADO; ASIGNADO existente -> EN_PROCESO; EN_PROCESO -> ATENDIDO. ATENDIDO/RECHAZADO son terminales. No se entra en ASIGNADO porque aún no existe el módulo de asignaciones. La guía condiciona una foto adicional de resolución a una política del equipo no definida; no se agregó ese requisito ni se simularon certificaciones.

Los cambios concurrentes usan bloqueo de fila y estadoEsperado para producir un ganador y un 409, sin duplicar historial/auditoría. Las fechas se toman después del bloqueo y no retroceden respecto de la actualización previa. Una cuenta ya en el estado solicitado con estadoEsperado correcto devuelve 200 sin duplicar auditoría; un estado esperado desactualizado devuelve 409.

## Base de datos y seguridad

No hay tablas ni columnas nuevas; Flyway continúa en V4. El script operativo `ops/configure-runtime-role.sql` añade únicamente UPDATE de estado/fecha del reporte y estado_cuenta del usuario. RLS exige un administrador activo y un actor de transacción coincidente para historial/auditoría. El servidor fija `app.actor_id` a partir del JWT mediante set_config con alcance local a la transacción. Se verificó que el contexto no se conserva en una transacción posterior y que el contexto de un ciudadano no permite cambiar reportes.

El script mantiene las restricciones de creación ciudadana, no concede UPDATE de roles, documento o propietario ni DELETE, y no expone el esquema a anon/authenticated. No usa SECURITY DEFINER ni BYPASSRLS. Se aplicó el mismo script probado al rol remoto arequipa_backend mediante el conector Supabase, con registro operativo `administration_runtime_permissions`; no se modificó manualmente flyway_schema_history.

Advisors posteriores: continúa la advertencia previa de [protección contra contraseñas filtradas desactivada](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection). No aparecen avisos nuevos de RLS tras este cambio. No se cambiaron configuraciones de contraseñas, servicios de correo ni credenciales.

## Pruebas y comandos ejecutados

```powershell
git switch -c feat/administracion
.\mvnw.cmd -B -DskipTests compile
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
git diff --check
```

La primera ejecución de pruebas detectó una expectativa antigua: esperaba 404 en GET /api/admin/usuarios para un administrador, cuando ahora corresponde 200. Se actualizó esa prueba, se completaron casos de rollback/permisos y la ejecución final terminó con **130 pruebas, cero fallos, cero errores y cero omitidas**. PostgreSQL de pruebas fue desechable; se comprobaron instalación limpia y adopción de baseline 3 + V4 sin escribir fixtures en Supabase.

Cobertura relevante:

- Matriz completa de 36 combinaciones de estados; validación de rechazo, estado esperado y cierre terminal.
- Acceso ciudadano/anónimo rechazado, roles revocados sin renovar JWT y autorización directa en aplicación.
- Listado global con todos los filtros, conservación del aislamiento en el listado propio, directorio paginado y documentos enmascarados.
- Concurrencia de transiciones: 200/409 y un único evento.
- Fallo de auditoría: rollback del reporte/historial y rollback de suspensión de cuenta.
- Suspensión/reactivación, rechazo de autosuspensión y suspensión simultánea entre administradores con un único ganador.
- Cambios bajo rol runtime sin privilegios elevados y RLS de actor; contexto de ciudadano no permite UPDATE de estado.
- Intentos de JSON con actor suministrado por cliente, límites y filtros inválidos y recursos inexistentes.

## Verificación remota y activación personal

El backend arrancó con el rol runtime real después de configurar las políticas. Un script Node con fetch y aserciones, enviado por stdin (`node --input-type=module`), comprobó GET /api/admin/reportes y GET /api/admin/usuarios sin token: ambos 401. Salud: 200, UP. Evidencia local ignorada por Git: `.local/admin-real-proof.json`.

Antes de este bloque había cero administradores activos. La nominación de la cuenta personal como ADMINISTRADOR se solicitó expresamente porque amplía el acceso a todos los reportes y a la gestión de cuentas. No se concede ese rol sin respuesta afirmativa. La prueba administrativa positiva en Supabase real queda pendiente de esa nominación y de una sesión válida; los flujos completos administrativos sí pasaron con PostgreSQL real desechable y las políticas runtime.

No se cambiaron estados de reportes/cuentas reales como parte de las pruebas realizadas hasta aquí.

## Archivos

- Dominio: AdministracionException, TransicionReporte, EstadoCuenta, CambioEstadoCuenta, ResumenUsuario y AdministracionRepositoryPort.
- Aplicación: AdministracionUseCase; reutilización de paginación en ReportesUseCase.
- Persistencia/configuración: JpaAdministracionAdapter, AdministracionConfig y listado compartido en JpaReporteAdapter/ReporteRepositoryPort.
- HTTP: AdministracionController, ApiExceptionHandler y reutilización del codificador de cursor de ReportesController.
- Operación: configure-runtime-role.sql y manifiesto deployment/supabase.json.
- Pruebas: AdministracionTest y PostgresIntegrationTest.
- Documentación: README.md, requests.http y este archivo.

Próximo bloque funcional: indicadores reales del dashboard. Cuadrillas, asignaciones, notificaciones, IA e integraciones oficiales permanecen pendientes.
