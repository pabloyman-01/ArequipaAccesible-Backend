# Contraseña de la cuenta administradora — operación local del propietario

Esta herramienta se ejecuta manualmente en el equipo del propietario, después de que este aprueba explícitamente la cuenta objetivo. No forma parte de la API Java, no se despliega ni debe publicarse como servicio.

Usa la [operación administrativa de Supabase para actualizar la contraseña](https://supabase.com/docs/reference/javascript/auth-admin-updateuserbyid). El titular introduce y confirma su propia contraseña en un formulario local. No se envían correos y no se crean cuentas, roles ni datos de identidad. No existe una contraseña predeterminada del proyecto.

## Preparación

El operador debe comprobar en la base que el correo corresponde a exactamente un perfil ACTIVA con ADMINISTRADOR y correo confirmado en Auth. Tras esa comprobación crea `.local/password-setup-target.json` con id, email, confirmado=true, administrador=true y estado_cuenta=ACTIVA. Estos datos operativos están ignorados por Git. No aceptar un destinatario remitido por un cliente HTTP.

El entorno privado debe contener SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, ACTIVATION_EMAIL y una secret key de servidor existente en SUPABASE_STORAGE_SECRET_KEY. Pese al nombre histórico de esa variable, se trata de una clave privilegiada del proyecto; nunca se copia al navegador ni al frontend. La lectura previa de la cuenta por la API administrativa debe funcionar antes de pedir al titular que escriba su contraseña.

```powershell
. .\ops\load-local-env.ps1
node --test ops/password-setup.test.mjs
node ops/password-setup.mjs
```

El titular abre http://127.0.0.1:3002 y realiza personalmente la entrada y el guardado. No se deben capturar, leer mediante herramientas, copiar ni registrar los campos. Debe esperarse el mensaje de confirmación, no solo que se rellene el formulario. El formulario caduca a los diez minutos; después de usarlo se cierra el proceso verificado que escucha en 3002.

## Controles

- Solo loopback, Host exacto, Origin del mismo formulario, nonce aleatorio, CSP contra iframes/scripts externos y respuestas no-store/no-referrer.
- Destinatario inmutable; se vuelve a verificar id, correo y confirmación inmediatamente antes del PUT. El cuerpo enviado a Auth contiene únicamente password.
- Se admiten como máximo tres intentos válidos. Una actualización exitosa o una respuesta ambigua bloquean otro PUT; no hay reintentos automáticos de escritura.
- La contraseña vive solo durante la petición. No se imprime, persiste ni devuelve al navegador en la respuesta. La confirmación usa una solicitud de acceso por contraseña al mismo Supabase.
- Si el acceso se confirma, se conserva únicamente el access token y el id en el archivo privado local auth-session.json para las verificaciones autorizadas. No se conserva la contraseña ni el refresh token.
- password-setup-status.json identifica cada ejecución y distingue awaiting_owner/completed/uncertain. password-setup-proof.json contiene fechas y resultados sin contraseñas. Un éxito de otra ejecución no demuestra que se haya completado la actual.

Los siete tests usan un fetch simulado y un servidor HTTP efímero de loopback. Comprueban Host/Origin/nonce, ausencia de secretos en HTML/respuesta, destino inmutable, validación, un único cambio, fallo ambiguo y fallo de la comprobación posterior. Nunca contactan cuentas reales ni envían correos.

## Validación del 2026-10-24

El titular eligió conservar su cuenta confirmada y sus roles existentes y definió personalmente la contraseña. Se confirmó la operación real y el acceso por contraseña. Una captura inicial coincidió con el autocompletado; el archivo se eliminó y el titular sustituyó después ese valor por una contraseña definitiva no capturada. La segunda operación quedó confirmada a las 22:54:03 UTC (17:54:03 de Perú).

Con la sesión nueva, perfil, reportes propios/globales, directorio, dashboard, detalle e historial respondieron 200. Se conservaron CIUDADANO y ADMINISTRADOR y la cuenta ACTIVA. También se verificó la foto privada del único reporte técnico con no-store y SHA-256 coincidente. El titular confirmó que pudo entrar al panel administrativo desde el frontend. No se crearon reportes ni cuentas adicionales durante esta comprobación.

Se ejecutaron `node --test ops/password-setup.test.mjs` (7/7), un script Node con fetch/aserciones por stdin y el cierre del proceso temporal de 3002. Las evidencias sanitizadas están en .local/password-login-real-proof.json y password-setup-proof.json. No se publican sesiones, contraseñas, capturas del formulario ni el archivo del destinatario.
