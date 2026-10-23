# Activación local de HU-01 — 2026-10-22

Rama: `feat/activar-hu01`. Proyecto real: `kjqzklzswocxbtbqohsc`, región São Paulo.

## Resultado comprobado

- Credenciales reales guardadas únicamente en `.env.local` y archivos locales ignorados; no se incluyen en Git.
- JDBC mediante session pooler con `sslmode=verify-full` y CA oficial descargada de Supabase. La configuración inicial con el almacén Java no confiaba en esa CA; se corrigió incorporando el certificado, sin desactivar TLS.
- Flyway adoptó el esquema existente con baseline 3 y aplicó V4. Validación posterior correcta. Un intento previo de validar antes de migrar detectó V4 pendiente; se corrigió el orden a migrate/validate.
- V4 cargó un distrito y seis tipos internos de barrera aprobados por el propietario. No son catálogos oficiales de OMAPED.
- Área operativa del piloto aprobada: latitud -16.43..-16.36, longitud -71.57..-71.50.
- Rol de migración temporal eliminado, permisos temporales retirados e historial Flyway transferido al propietario. El runtime conserva sus permisos restringidos.
- Backend local conectado a PostgreSQL de Supabase: salud HTTP 200 con `UP`; catálogos HTTP 200 con un distrito y seis categorías.
- 88 pruebas locales: cero fallos, errores o pruebas omitidas. PostgreSQL de pruebas desechable; no se ejecutaron pruebas destructivas contra Supabase.
- Advisor de seguridad: advertencia de protección contra contraseñas filtradas desactivada en Auth. La prueba actual usa enlace por correo. Revisar la [configuración de protección de contraseñas](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection) antes de habilitar acceso por contraseña.
- Supabase confirmó el correo corregido del ciudadano. No se guarda el correo personal en este documento.

## Comandos realmente ejecutados

```powershell
. .\ops\load-local-env.ps1
.\mvnw.cmd -B flyway:baseline '-Dflyway.baselineVersion=3'
.\mvnw.cmd -B flyway:validate flyway:migrate # detectó V4 pendiente
.\mvnw.cmd -B flyway:migrate flyway:validate # BUILD SUCCESS
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap # BUILD SUCCESS; 88 pruebas
.\mvnw.cmd -B spring-boot:run
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/catalogos/distritos
Invoke-RestMethod http://localhost:8080/api/catalogos/tipos-barrera
node --check ops/activation-auth.mjs
node ops/activation-auth.mjs
git diff --check
```

Las operaciones administrativas de credenciales y retirada del rol temporal se realizaron por el conector Supabase. Las contraseñas se generaron de forma aleatoria y se configuraron mediante verificadores SCRAM; no se inventaron claves de servicios ni se publicó ningún secreto.

## Incidencia inicial de autenticación (resuelta el 2026-10-23)

La página de retorno del correo se abrió sin una sesión utilizable. El callback ahora distingue enlaces rechazados/caducados. El siguiente envío fue rechazado por Supabase Auth con HTTP 429. No se desactivó la confirmación del correo ni se fabricó un JWT.

El 2026-10-23 se solicitó un único enlace nuevo y se capturó una sesión real: `/auth/v1/user` respondió 200, con correo confirmado y coincidente con la cuenta configurada. El usuario introdujo personalmente sus datos en el formulario local del puerto 3001; no se publican esos datos ni el token.

El helper de Auth ahora reutiliza una sesión vigente y muestra «Cuenta confirmada» incluso al abrir un enlace antiguo después de haber capturado una sesión válida. Se comprobó el reinicio sin nuevo correo y el rechazo 403 de POST sin nonce desde otro origen.

## HU-01 real completado el 2026-10-23

- `POST /api/reportes`: 201; código `ARQ-2026-000001`; estado `RECIBIDO`.
- `GET /api/reportes/1`: 200, una imagen y sin exponer object_key.
- `GET /api/reportes/1/fotos/1`: 200; `image/png`; `Cache-Control: no-store`.
- Imagen PNG técnica de 280 bytes: SHA-256 de subida y descarga `a48118781afdf7013aebe65fea4fdf37678da1e76c84b4b15ab5062c2852e461`.
- Reporte y foto sin token: ambos 401.
- Verificación SQL: un reporte, una foto, un historial inicial RECIBIDO, un evento `CREAR_REPORTE`, un objeto de Storage y cero intenciones de carga pendientes. Bucket privado.
- Segunda ejecución del verificador: volvió a consultar el mismo reporte; la base mantuvo un único reporte y un único objeto.

El reporte y la imagen son de prueba, explícitamente identificados como técnicos; no representan una incidencia real. El correo está confirmado y el documento del perfil permanece NO_VERIFICADO.

Comandos adicionales realmente ejecutados:

```powershell
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
node --check ops/activation-auth.mjs
node --check ops/verify-hu01-real.mjs
node ops/activation-auth.mjs
node ops/verify-hu01-real.mjs # creación y prueba real; segunda ejecución solo consulta
$env:ACTIVATION_SEND_EMAIL='false'
node ops/activation-auth.mjs # reutilizó sesión, sin nuevo correo
git diff --check
```

La prueba real incluye comprobaciones HTTP y comparación de bytes. Las 88 pruebas automatizadas del cambio anterior siguen como referencia; no se afirma una nueva ejecución local de Maven verify para estos scripts. Los tokens, el documento y los demás datos personales permanecen excluidos de Git.

Archivos de esta continuación: `ops/verify-hu01-real.mjs`, `ops/activation-auth.mjs`, `.env.example`, `README.md`, `deployment/supabase.json` y este documento.

La base conserva la cuenta no confirmada correspondiente al correo inicialmente equivocado. No se eliminó ninguna cuenta durante la corrección. Revisar ese registro con el propietario antes de cualquier eliminación.
