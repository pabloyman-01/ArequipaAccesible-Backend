# Integración con Angular — 2026-10-24

Rama: feat/integracion-backend, basada en dev. Frontend local solicitado: D:\code\Arequipaaccesiblefront, rama feat/integracion-frontend del repositorio ArequipaAccesible-Frontend.

Se conectaron en Angular los contratos ya disponibles: Supabase Auth y perfil Java, catálogos, reporte con foto, listados con cursor, detalle, historial, evidencia privada, administración de reportes/usuarios y dashboard. No se añadieron endpoints, tablas, migraciones ni permisos de base de datos para esta unión.

## Cambios de este repositorio

- .env.example: origen explícito de Angular para desarrollo, http://localhost:3000. En despliegue sustituirlo por el origen HTTPS definitivo.
- PostgresIntegrationTest.java: preflight con authorization/content-type permitido para el origen configurado, rechazo de origen ajeno y ausencia de Allow-Credentials.
- README.md y este registro: ejecución de ambos proyectos y verificación.

La configuración real local, ignorada por Git, conserva el rol JDBC restringido y TLS verify-full. CORS_ALLOWED_ORIGINS incluye localhost:3000 y los orígenes locales existentes necesarios. Las claves secretas y contraseñas permanecen solo en el backend; el frontend recibió únicamente URL y clave publicable ya existentes.

## Comandos ejecutados y resultados

```powershell
git switch -c feat/integracion-backend
. .\ops\load-local-env.ps1
.\mvnw.cmd -B spring-boot:run
.\ops\test-local-postgres.ps1 -Port 55440 -CheckBootstrap
```

Resultado: **141 pruebas, cero fallos, errores y omitidas**. PostgreSQL desechable comprobó instalación limpia y adopción de baseline 3 + V4. No se ejecutaron fixtures en Supabase ni se cambiaron reportes/cuentas reales.

Un script Node enviado por stdin comprobó desde http://localhost:3000 los catálogos reales (200, un distrito y seis tipos internos), respuestas 401 de reportes/dashboard sin JWT, preflight local 200 y origen externo 403. La evidencia sin tokens está en .local/proxy-real-proof.json del frontend, ignorada por Git. El frontend usa un proxy de desarrollo /api/** hacia localhost:8080; este proxy no sustituye un despliegue público.

Se corrigió en Angular el almacenamiento PKCE inicialmente limitado a una pestaña: ahora la sesión y el verificador usan localStorage del mismo navegador y se informa de errores del intercambio. La prueba con el titular encontró después el límite temporal de envío de correos de Supabase; se detuvieron las solicitudes. **El recorrido autenticado real en el navegador queda pendiente por ese límite**. No se modificaron SMTP, límites de correo, contraseñas ni confirmación de cuentas para evitarlo.

Actualización posterior del 2026-10-24: el titular autorizó definir una contraseña para su cuenta existente mediante la API administrativa de Supabase, sin correos. La eligió personalmente en el formulario local y confirmó el acceso al panel. La operación definitiva y el acceso fueron comprobados; el formulario temporal de 3002 se cerró. Ver ops/PASSWORD_SETUP.md para la operación, controles, siete tests y validación real.

El registro completo del frontend está en INTEGRACION.md de su rama. Con la sesión renovada se confirmaron respuestas 200 de perfil, reportes propios/globales, usuarios, dashboard, detalle e historial, conservando los dos roles y el estado ACTIVA. La foto privada también se comprobó con su hash. Falta completar las pruebas de acciones del frontend que no se han ejercitado personalmente, como registrar otro reporte con foto desde el navegador; el POST real previo de HU-01 y los contratos multipart automatizados siguen verificados. Después se prepararán los dominios definitivos, CORS y HTTPS de despliegue.

## Registro del piloto sin confirmación de correo

El propietario desactivó temporalmente Confirm email en Supabase. El 2026-10-24 a las 23:14:42 UTC (18:14:42 en Perú) se comprobó GET /auth/v1/settings con la clave publicable: mailer_autoconfirm=true, proveedor de correo habilitado y registros habilitados. La evidencia sin claves está en .local/registration-settings-proof.json, ignorada por Git. deployment/supabase.json registra este estado como excepción temporal del piloto; no caduca automáticamente.

Angular consulta el ajuste real en cada alta y, con confirmación desactivada, adopta la sesión devuelta por Supabase y abre el formulario de perfil. PUT /api/usuarios/me conserva la asignación de CIUDADANO; el registro no concede ADMINISTRADOR. No requiere migraciones ni cambios en la autorización del backend. La compilación y las 64 pruebas del frontend pasaron en CI (40141684932). Queda pendiente un alta nueva completa desde el navegador con datos del titular. La recuperación de contraseña y los enlaces de acceso siguen dependiendo del envío de correos.
