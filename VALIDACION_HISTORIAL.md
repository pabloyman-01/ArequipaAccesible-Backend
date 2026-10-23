# Consulta del historial de reportes — 2026-10-23

Rama: `feat/historial-reportes`.

## Resultado

`GET /api/reportes/{id}/historial` devuelve los eventos del reporte en orden cronológico ascendente, con desempate por ID. Campos: id, estadoAnterior, estadoNuevo, fechaCambio y motivo. El evento inicial RECIBIDO conserva estadoAnterior/motivo null cuando así están almacenados.

El propietario o un administrador activo puede consultar. Un ciudadano ajeno recibe el mismo 404 y código NO_ENCONTRADO que un reporte inexistente. Los roles se leen de PostgreSQL en cada petición: revocar ADMINISTRADOR afecta al siguiente acceso sin renovar el token. Un reporte autorizado sin eventos devuelve 200 con `[]`. Un usuario sin autenticación recibe 401; una cuenta suspendida o sin perfil autorizado recibe 403.

La respuesta usa `Cache-Control: no-store`. No publica identidad del actor, auditoría ni claves de fotos. La autorización se comprueba antes de consultar y se repite en la consulta de eventos. No se cargan metadatos de fotos ni se llama a Storage. La lectura funciona aunque se deshabiliten nuevas cargas.

Se reutilizan la tabla historial_estados, su índice reporte/fecha y los permisos SELECT del runtime. El nuevo mapeo JPA es de lectura. No se requieren migraciones, credenciales ni cambios de políticas. El endpoint no crea transiciones: POST/PATCH devuelven 405. Los estados posteriores usados en pruebas se insertaron solo en PostgreSQL desechable.

## Comandos ejecutados y resultados

```powershell
git switch -c feat/historial-reportes
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
git diff --check
```

La suite terminó con **110 pruebas, cero fallos, cero errores y cero omitidas**. El script comprobó instalación limpia y adopción baseline 3 + V4 en bases desechables, y detuvo PostgreSQL al terminar.

Cobertura añadida:

- Lectura del historial inicial por su propietario, incluidos campos null y cabecera no-store.
- Orden por fecha e ID con precisión de microsegundos y eventos insertados fuera de orden.
- Aislamiento de eventos de otros reportes y ausencia de datos privados del actor.
- Reporte existente sin eventos; reporte ajeno e inexistente con el mismo error.
- Acceso de un administrador sin rol ciudadano; revocación inmediata del rol.
- Usuario anónimo, perfil ausente y suspensión del propietario o del administrador.
- IDs inválidos y rechazo de escrituras por HTTP.
- Lectura con el rol runtime restringido, sin nuevos permisos.
- Controles en aplicación, sin cargas ni acceso a fotos/Storage.

## Prueba real con Supabase

Tras reiniciar el backend se ejecutó `node --input-type=module` con un script enviado por stdin que usó fetch y aserciones. Reutilizó la sesión local del propietario sin imprimir tokens ni datos de identidad. Evidencia ignorada por Git: `.local/historial-real-proof.json`.

| Comprobación | Resultado |
| --- | --- |
| GET /api/reportes/1/historial | 200; un evento RECIBIDO; estadoAnterior y motivo null. |
| Campos privados del actor | Ausentes; solo los cinco campos del contrato. |
| Cache-Control | no-store. |
| GET /api/reportes/999999/historial | 404. |
| GET sin token | 401. |
| POST /api/reportes/1/historial | 405; no crea eventos. |
| GET /actuator/health | 200, UP. |

La prueba real consultó el historial del reporte técnico ya existente ARQ-2026-000001. No creó usuarios, reportes, eventos ni objetos de Storage. Los escenarios con administrador y múltiples eventos se probaron en la base desechable.

## Archivos

- Dominio/puerto: CambioEstadoReporte.java, ReporteRepositoryPort.java.
- Aplicación: ReportesUseCase.java.
- Persistencia: HistorialEstadoEntity.java, JpaReporteAdapter.java.
- API: ReportesController.java.
- Pruebas: PostgresIntegrationTest.java, ReportesUseCaseTest.java.
- Documentación: README.md, requests.http y este archivo.

Próximo bloque: gestión administrativa y transiciones de estado, con validación de reglas y auditoría. No se implementaron en esta entrega.
