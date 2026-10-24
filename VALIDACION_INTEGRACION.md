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

El registro completo de archivos, comandos y pruebas del frontend está en INTEGRACION.md de su rama. Al retomar: iniciar sesión cuando se restablezca el correo o con una contraseña ya definida por el titular, verificar permisos, reportes/evidencia y administración desde la interfaz. Después configurar los dominios definitivos, CORS y HTTPS de despliegue.
