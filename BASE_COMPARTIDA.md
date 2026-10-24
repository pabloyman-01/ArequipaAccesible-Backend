# Conectar otro equipo a la base compartida

Para ejecutar el backend local usando el proyecto Supabase existente no hace falta una invitación al panel. El responsable debe entregar las credenciales autorizadas por un canal seguro. El acceso al repositorio no entrega estas credenciales.

## 1. Descargar el backend

Instalar Git y JDK 21. Maven se ejecuta con el wrapper incluido. No hace falta instalar PostgreSQL local para conectarse a Supabase.

```powershell
cd D:\code
git clone --branch dev https://github.com/pabloyman-01/ArequipaAccesible-Backend.git
cd ArequipaAccesible-Backend
java -version
```

Si ya existe el repositorio, entrar en él, guardar cualquier trabajo pendiente, cambiar a `dev` y ejecutar `git pull --ff-only origin dev`. No clonar sobre una carpeta existente.

## 2. Preparar las variables

Crear `.env.local` en la raíz del backend sin sobrescribir uno existente:

```powershell
if (-not (Test-Path -LiteralPath .env.local)) {
    Copy-Item -LiteralPath .env.example -Destination .env.local
}
```

Solicitar al responsable los valores reales de JDBC y Storage. No son la contraseña de la cuenta ciudadana ni la de GitHub. `.env.local` y `.local/` están ignorados por Git: no compartirlos por commits, issues o capturas.

| Variable | Configuración para la base compartida |
| --- | --- |
| `DB_URL` | URL JDBC real proporcionada por el responsable, con `sslmode=verify-full`. Instalar el certificado CA requerido y adaptar su ruta al equipo. |
| `DB_USERNAME`, `DB_PASSWORD` | Credenciales autorizadas del rol runtime restringido; no usar el propietario. |
| `SPRING_FLYWAY_ENABLED` | `false`: la base compartida ya está migrada. El ejemplo genérico trae `true`; cambiarlo para este caso. |
| `MIGRATION_DB_USERNAME`, `MIGRATION_DB_PASSWORD` | Dejar vacías; no son necesarias para arrancar. |
| `SUPABASE_URL`, `SUPABASE_JWT_ISSUER`, `SUPABASE_JWKS_URI`, `SUPABASE_JWT_AUDIENCE` | Conservar los valores del mismo proyecto incluidos en `.env.example`. |
| `SUPABASE_STORAGE_SECRET_KEY` | Clave de servidor autorizada, entregada por el responsable. Solo backend, nunca frontend. |
| `SUPABASE_STORAGE_BUCKET` | `reportes-evidencias`. |
| `REPORTES_HABILITADO` | `true` para usar reportes y Storage real. |
| `REPORTES_LAT_MIN`, `REPORTES_LAT_MAX` | `-16.43` y `-16.36`, respectivamente. |
| `REPORTES_LON_MIN`, `REPORTES_LON_MAX` | `-71.57` y `-71.50`, respectivamente. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` para el frontend actual. Si cambia, indicar el origen exacto, sin `*`. |
| `REQUIRE_HTTPS` | `false` exclusivamente en desarrollo local. |
| `PORT` | `8080`, compatible con el proxy del frontend. |

El área es el piloto interno aprobado de Cercado, no un límite municipal oficial. Los catálogos ya existen y no deben cargarse otra vez.

`SUPABASE_PUBLISHABLE_KEY` y `ACTIVATION_*` son para helpers opcionales: no hacen falta para arrancar Java. No copiar sesiones de otro equipo ni ejecutar formularios administrativos temporales para iniciar el servidor.

## 3. Arrancar y comprobar

En la raíz del backend, dentro de la misma terminal PowerShell:

```powershell
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
```

Spring no carga `.env.local` automáticamente. El primer comando exporta sus variables sin imprimir valores. Después de modificar el archivo, detener el servidor, volver a cargar las variables y arrancarlo otra vez.

En otra terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/catalogos/distritos
```

Se espera salud `UP` y el catálogo del piloto. La salud por sí sola no comprueba la subida de fotos a Storage. Los endpoints protegidos requieren una sesión propia del mismo proyecto.

El frontend usa `http://localhost:3000` y el proxy `/api` hacia `http://localhost:8080`. Su configuración pública debe usar la URL y la clave publicable del mismo Supabase, nunca las credenciales JDBC ni la clave secreta de Storage.

## Datos compartidos y problemas frecuentes

- Todos los equipos ven los mismos perfiles, reportes, estados y archivos. Las escrituras afectan a datos reales. Usar cuentas propias; un documento ya registrado no puede vincularse a otra cuenta.
- No ejecutar baseline, migraciones, semillas ni pruebas contra la base compartida durante el arranque. Coordinar los cambios de esquema con el responsable. Las pruebas utilizan una base desechable; nunca configurar `TEST_DB_URL` con Supabase compartido.
- Ante un fallo JDBC, revisar host, puerto, usuario, contraseña, certificado y conectividad. Una ruta de certificado de otro equipo puede no existir aquí. Mantener la verificación TLS.
- Si Flyway intenta modificar tablas, revisar que `SPRING_FLYWAY_ENABLED=false` esté cargado y reiniciar el proceso.
- Ante un error CORS, revisar el origen exacto y reiniciar tras cargar las variables. CORS no sustituye la autenticación.
- Si 8080 está ocupado, detener la otra instancia o cambiar `PORT` y el destino del proxy del frontend de forma coherente.
- La invitación al panel solo es necesaria para administrar Supabase. No hace falta para conectarse con las credenciales autorizadas.
