// Local-only callback for a real Supabase email login. Never prints tokens.
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const project = process.env.SUPABASE_URL;
const key = process.env.SUPABASE_PUBLISHABLE_KEY;
const email = process.env.ACTIVATION_EMAIL;
if (!project?.startsWith('https://') || !key?.startsWith('sb_publishable_') || !email) throw new Error('Missing real Auth configuration');
const privateDir = resolve('.local');
await mkdir(privateDir, {recursive:true});
const nonce = randomBytes(32).toString('hex');
const html = `<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Confirmación HU-01</title>
<body style="font:18px system-ui;max-width:640px;margin:60px auto;padding:24px"><h1>Arequipa Accesible</h1><p id="state">Abre el enlace que recibiste por correo para confirmar tu cuenta.</p>
<script nonce="${nonce}">const params=new URLSearchParams(location.hash.slice(1));history.replaceState(null,'','/');const token=params.get('access_token');
if(params.has('error')){document.getElementById('state').textContent='El enlace fue rechazado o caducó. Solicita un enlace nuevo en el chat.'}
else if(token){document.getElementById('state').textContent='Verificando la sesión real…';fetch('/session',{method:'POST',headers:{'Content-Type':'application/json','X-Activation-Nonce':'${nonce}'},body:JSON.stringify({access_token:token})}).then(async r=>{document.getElementById('state').textContent=r.ok?'Cuenta confirmada. Ya puedes volver al chat; la prueba continúa sin mostrar tus credenciales.':'No se pudo validar la sesión. Vuelve al chat.'}).catch(()=>{document.getElementById('state').textContent='No se pudo conectar con el verificador local.'})}</script></body></html>`;
const server = http.createServer(async(req,res) => {
  res.setHeader('Cache-Control','no-store');
  res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'`);
  if (!['localhost:3000','127.0.0.1:3000'].includes(req.headers.host)) {res.writeHead(403).end();return;}
  if (req.method==='GET' && req.url==='/') {res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(html);return;}
  if (req.method!=='POST' || req.url!=='/session' || req.headers['x-activation-nonce']!==nonce
      || !['http://localhost:3000','http://127.0.0.1:3000'].includes(req.headers.origin)) {res.writeHead(403).end();return;}
  try {
    let body=''; for await (const chunk of req) {body+=chunk;if(body.length>24000) throw new Error('Too large');}
    const {access_token}=JSON.parse(body);
    const response=await fetch(project+'/auth/v1/user',{headers:{apikey:key,Authorization:'Bearer '+access_token},signal:AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error('Invalid Auth session');
    const user=await response.json();
    if (user.email?.toLowerCase()!==email.toLowerCase() || !user.email_confirmed_at) throw new Error('Wrong or unconfirmed account');
    await writeFile(resolve(privateDir,'auth-session.json'),JSON.stringify({access_token,userId:user.id}),{mode:0o600});
    console.log('Cuenta de ciudadano confirmada; sesión guardada localmente sin imprimir tokens.');
    res.writeHead(200,{'Content-Type':'application/json'}).end('{"ok":true}');
  } catch {res.writeHead(401,{'Content-Type':'application/json'}).end('{"ok":false}');}
});
server.listen(3000,'127.0.0.1',async()=>{
  console.log('Callback de confirmación listo en http://localhost:3000');
  const r=await fetch(project+'/auth/v1/otp?redirect_to='+encodeURIComponent('http://localhost:3000'),{
    method:'POST',headers:{apikey:key,'Content-Type':'application/json'},body:JSON.stringify({email,create_user:true}),signal:AbortSignal.timeout(15000)});
  if(!r.ok){const error=await r.json().catch(()=>({}));console.log('Solicitud de correo rechazada: HTTP '+r.status+' code='+(error.code??error.error_code??'unknown'));return;}
  console.log('Correo de confirmación solicitado. El usuario debe abrir el enlace en este equipo.');
});
