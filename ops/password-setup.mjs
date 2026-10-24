// Explicit owner operation only. Never part of the Java API or a production server.
import http from "node:http";
import { randomBytes } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const escapeHtml = (value) =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
export function passwordSetupServer({
  target,
  project,
  secret,
  publishable,
  fetcher = fetch,
  saveSession = async () => {},
  saveProof = async () => {},
}) {
  if (
    !/^[0-9a-f-]{36}$/.test(target.id) ||
    !target.confirmado ||
    !target.administrador ||
    target.estado_cuenta !== "ACTIVA"
  )
    throw new Error("Confirmed active administrator required");
  const base = new URL(project);
  if (base.protocol !== "https:" || base.username || base.password)
    throw new Error("HTTPS Supabase URL required");
  if (
    !secret?.startsWith("sb_secret_") ||
    !publishable?.startsWith("sb_publishable_")
  )
    throw new Error("Existing server secret and publishable key required");
  const nonce = randomBytes(32).toString("hex");
  let locked = false,
    attempts = 0;
  async function remote(path, key, options = {}) {
    return fetcher(new URL(path, base), {
      ...options,
      headers: {
        apikey: key,
        "Content-Type": "application/json",
        ...options.headers,
      },
      redirect: "error",
      signal: AbortSignal.timeout(15000),
    });
  }
  const html =
    () => `<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Definir contraseña de administrador</title>
  <style>[hidden]{display:none!important}body{font:17px system-ui;background:#faf4f4;color:#2e2020;margin:0;padding:36px 16px}main{max-width:520px;margin:auto;background:white;border-radius:20px;padding:28px;box-shadow:0 8px 32px #65102018}h1{color:#741b21;font-size:26px}label{display:block;margin-top:20px}input{box-sizing:border-box;width:100%;padding:14px;margin-top:8px;border:1px solid #d6bebe;border-radius:10px;font:inherit}button,a{display:inline-block;padding:13px 20px;border-radius:30px;margin-top:22px;background:#741b21;color:white;border:0;font:inherit;text-decoration:none}button:disabled{opacity:.5}#state{white-space:pre-line;line-height:1.6}</style>
  <main><h1>Definir contraseña de administrador</h1><p>Cuenta: <strong>${escapeHtml(target.email)}</strong></p><p>Se conserva tu perfil y tus roles. La contraseña se enviará a Supabase desde este equipo; no se guarda en archivos ni se muestra en el chat.</p>
  <form id="setup"><label>Nueva contraseña<input id="password" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><label>Repetir contraseña<input id="confirmation" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><button id="save" type="submit">Guardar contraseña y comprobar acceso</button></form>
  <p id="state" role="status"></p><a id="next" hidden href="http://localhost:3000/inicio-sesion">Ir al frontend e iniciar sesión</a>
  <script nonce="${nonce}">const form=document.getElementById('setup'),button=document.getElementById('save'),state=document.getElementById('state');let busy=false;
  form.addEventListener('submit',async e=>{e.preventDefault();if(busy)return;const password=document.getElementById('password').value,confirmation=document.getElementById('confirmation').value;if(password!==confirmation){state.textContent='Las contraseñas no coinciden.';return;}busy=true;button.disabled=true;state.textContent='Guardando en Supabase…';
  try{const r=await fetch('/password',{method:'POST',headers:{'Content-Type':'application/json','X-Setup-Nonce':'${nonce}'},body:JSON.stringify({password,confirmation})});const result=await r.json();form.reset();state.textContent=result.message;if(r.ok){form.hidden=true;document.getElementById('next').hidden=false;}else if(result.retryable){busy=false;button.disabled=false;}}catch{form.reset();state.textContent='No se pudo confirmar el resultado. Prueba tu nueva contraseña en el frontend antes de repetir esta operación.';}});
  </script></main></html>`;
  const server = http.createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader(
      "Content-Security-Policy",
      `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
    );
    const port = server.address()?.port;
    const hosts = [`localhost:${port}`, `127.0.0.1:${port}`];
    function reply(status, body) {
      res
        .writeHead(status, {
          "Content-Type": "application/json; charset=utf-8",
        })
        .end(JSON.stringify(body));
    }
    if (!hosts.includes(req.headers.host)) {
      reply(403, { message: "Origen rechazado." });
      return;
    }
    if (req.method === "GET" && req.url === "/") {
      res
        .writeHead(200, { "Content-Type": "text/html; charset=utf-8" })
        .end(html());
      return;
    }
    if (
      req.method !== "POST" ||
      req.url !== "/password" ||
      req.headers.origin !== `http://${req.headers.host}` ||
      req.headers["x-setup-nonce"] !== nonce
    ) {
      reply(403, { message: "Solicitud rechazada." });
      return;
    }
    if (locked || attempts >= 3) {
      reply(409, {
        message: "Operación cerrada. Prueba el acceso en el frontend.",
      });
      return;
    }
    if (req.headers["content-type"] !== "application/json") {
      reply(415, { message: "Solicitud inválida." });
      return;
    }
    let body;
    try {
      let bytes = 0;
      const chunks = [];
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 4096) throw new Error("Too large");
        chunks.push(chunk);
      }
      body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      if (
        Object.keys(body).some(
          (k) => !["password", "confirmation"].includes(k),
        ) ||
        typeof body.password !== "string" ||
        body.password.length < 8 ||
        body.password.length > 128 ||
        body.password !== body.confirmation
      )
        throw new Error("Invalid password input");
    } catch {
      reply(400, {
        message: "Introduce dos contraseñas iguales de 8 a 128 caracteres.",
        retryable: true,
      });
      return;
    }
    locked = true;
    attempts++;
    try {
      // Revalidate the immutable target immediately before changing its password.
      const before = await remote(`/auth/v1/admin/users/${target.id}`, secret);
      if (!before.ok) {
        locked = false;
        reply(502, {
          message: "No se pudo verificar la cuenta en Supabase.",
          retryable: true,
        });
        return;
      }
      const existing = await before.json();
      if (
        existing.id !== target.id ||
        existing.email?.toLowerCase() !== target.email.toLowerCase() ||
        !existing.email_confirmed_at
      ) {
        reply(409, {
          message: "La cuenta objetivo no coincide con la cuenta aprobada.",
        });
        return;
      }
      const updated = await remote(
        `/auth/v1/admin/users/${target.id}`,
        secret,
        { method: "PUT", body: JSON.stringify({ password: body.password }) },
      );
      if (!updated.ok) {
        locked = false;
        reply(422, {
          message:
            "Supabase rechazó la contraseña. Revisa sus requisitos y prueba otra.",
          retryable: attempts < 3,
        });
        return;
      }
    } catch {
      await saveProof({
        at: new Date().toISOString(),
        passwordUpdated: "unknown",
        loginVerified: false,
      }).catch(() => {});
      reply(502, {
        message:
          "No se pudo confirmar la actualización. Prueba tu nueva contraseña en el frontend antes de repetir.",
      });
      return;
    }
    // The password update succeeded. A failed verification must never trigger a second update.
    let loginVerified = false,
      sessionSaved = false;
    try {
      const login = await remote(
        "/auth/v1/token?grant_type=password",
        publishable,
        {
          method: "POST",
          body: JSON.stringify({
            email: target.email,
            password: body.password,
          }),
        },
      );
      if (login.ok) {
        const session = await login.json();
        if (
          session.user?.id === target.id &&
          typeof session.access_token === "string"
        ) {
          loginVerified = true;
          await saveSession({
            access_token: session.access_token,
            userId: target.id,
          });
          sessionSaved = true;
        }
      }
    } catch {
      /* The user can still sign in with the newly set password. */
    }
    body = undefined;
    await saveProof({
      at: new Date().toISOString(),
      passwordUpdated: true,
      loginVerified,
      sessionSaved,
    }).catch(() => {});
    reply(200, {
      ok: true,
      loginVerified,
      message: loginVerified
        ? "Contraseña guardada. Acceso con contraseña comprobado. Ya puedes entrar al frontend con tu correo y la contraseña que elegiste."
        : "Contraseña guardada. Inicia sesión en el frontend con tu correo y la nueva contraseña para comprobar el acceso.",
    });
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 5000;
  return server;
}

async function main() {
  const directory = resolve(".local");
  const runId = randomBytes(12).toString("hex");
  const target = JSON.parse(
    await readFile(resolve(directory, "password-setup-target.json"), "utf8"),
  );
  if (target.email !== process.env.ACTIVATION_EMAIL)
    throw new Error("Target email must match the approved local account");
  const server = passwordSetupServer({
    target,
    project: process.env.SUPABASE_URL,
    secret: process.env.SUPABASE_STORAGE_SECRET_KEY,
    publishable: process.env.SUPABASE_PUBLISHABLE_KEY,
    saveSession: (s) =>
      writeFile(resolve(directory, "auth-session.json"), JSON.stringify(s), {
        mode: 0o600,
      }),
    saveProof: async (p) => {
      const proof = { ...p, runId };
      await writeFile(
        resolve(directory, "password-setup-proof.json"),
        JSON.stringify(proof, null, 2),
        { mode: 0o600 },
      );
      await writeFile(
        resolve(directory, "password-setup-status.json"),
        JSON.stringify(
          {
            ...proof,
            phase: p.passwordUpdated === true ? "completed" : "uncertain",
          },
          null,
          2,
        ),
        { mode: 0o600 },
      );
    },
  });
  await writeFile(
    resolve(directory, "password-setup-status.json"),
    JSON.stringify(
      { runId, phase: "awaiting_owner", startedAt: new Date().toISOString() },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  server.listen(3002, "127.0.0.1", () =>
    console.log(
      "Formulario temporal listo en http://127.0.0.1:3002. El titular debe introducir y guardar su contraseña personalmente.",
    ),
  );
  setTimeout(() => server.close(), 10 * 60 * 1000).unref();
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  main().catch(() => {
    console.error(
      "No se pudo iniciar el formulario. Revisa la cuenta aprobada y la configuración local; no se mostraron secretos.",
    );
    process.exitCode = 1;
  });
}
