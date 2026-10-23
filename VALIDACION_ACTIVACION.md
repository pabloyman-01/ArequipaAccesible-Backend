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

## Pendiente real

La página de retorno del correo se abrió sin una sesión utilizable. El callback ahora distingue enlaces rechazados/caducados. El siguiente envío fue rechazado por Supabase Auth con HTTP 429. No se desactivó la confirmación del correo ni se fabricó un JWT.

Cuando Auth permita otro envío, solicitar un enlace nuevo, capturar una sesión validada por `/auth/v1/user` y completar el perfil con datos suministrados por el usuario. Después: crear un reporte técnico explícitamente identificado, subir una imagen de prueba, consultar reporte/foto y comparar sus bytes. No se afirma que ese flujo real haya pasado todavía. La clave real de Storage está configurada; la subida real sigue sin probarse.

La base conserva la cuenta no confirmada correspondiente al correo inicialmente equivocado. No se eliminó ninguna cuenta durante la corrección. Revisar ese registro con el propietario antes de cualquier eliminación.
