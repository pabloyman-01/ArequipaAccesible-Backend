// Explicit real-system smoke test. Run only against the approved pilot.
// A real citizen session and user-supplied profile are required. No secrets are logged.
import http from 'node:http';
import assert from 'node:assert/strict';
import {createHash, randomBytes} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';

const backend='http://localhost:8080';
const proofPath='.local/hu01-real-proof.json';
const session=JSON.parse(await readFile('.local/auth-session.json','utf8'));
const authorization='Bearer '+session.access_token;
const api=(path,options={})=>fetch(backend+path,{...options,redirect:'error',
  headers:{Authorization:authorization,...options.headers},signal:AbortSignal.timeout(30000)});
const auth=await fetch(process.env.SUPABASE_URL+'/auth/v1/user',{
  headers:{apikey:process.env.SUPABASE_PUBLISHABLE_KEY,Authorization:authorization},
  redirect:'error',signal:AbortSignal.timeout(15000)});
assert.equal(auth.status,200,'A valid real Auth session is required');
const citizen=await auth.json();
assert.equal(citizen.id,session.userId);
assert.equal(citizen.email?.toLowerCase(),process.env.ACTIVATION_EMAIL?.toLowerCase());
assert.ok(citizen.email_confirmed_at);
const districtsResponse=await api('/api/catalogos/distritos');
const typesResponse=await api('/api/catalogos/tipos-barrera');
assert.equal(districtsResponse.status,200); assert.equal(typesResponse.status,200);
const district=(await districtsResponse.json()).find(d=>d.nombre==='Arequipa (Cercado)');
const barrier=(await typesResponse.json()).find(t=>t.codigo==='PROY-01');
assert.ok(district&&barrier,'Approved pilot catalogues are required');

const profile=await api('/api/usuarios/me');
if(profile.status===404) await completeProfile();
else assert.equal(profile.status,200,'Citizen profile must be available');
await verifyReport();

async function completeProfile() {
  const nonce=randomBytes(32).toString('hex');
  const html=`<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Perfil para probar HU-01</title>
  <style>body{font:17px system-ui;max-width:650px;margin:40px auto;padding:20px}label{display:block;margin:16px 0}input,select,button{display:block;padding:10px;font:inherit;max-width:95%}button{margin-top:24px}#state{white-space:pre-wrap}</style>
  <h1>Completar mi perfil</h1><p>Tu correo ya está confirmado. Estos datos se enviarán a tu backend local y se guardarán en la base de Supabase del proyecto. No los envíes por el chat.</p>
  <p>Después se creará un reporte marcado como PRUEBA TÉCNICA, con una imagen de prueba. No representa una incidencia real. Tu documento quedará NO VERIFICADO.</p>
  <form id="profile" autocomplete="off"><label>Nombres<input name="nombres" required maxlength="100"></label>
  <label>Apellidos<input name="apellidos" required maxlength="100"></label>
  <label>Tipo de documento<select name="tipoDocumento"><option>DNI</option><option>CE</option><option>PASAPORTE</option></select></label>
  <label>Número de documento<input name="documento" required minlength="6" maxlength="20" pattern="[A-Za-z0-9]{6,20}"></label>
  <p>Distrito del piloto: Arequipa (Cercado).</p><button>Guardar perfil y probar HU-01</button></form><p id="state" role="status"></p>
  <script nonce="${nonce}">document.getElementById('profile').addEventListener('submit',async e=>{e.preventDefault();const form=e.target;const button=form.querySelector('button');button.disabled=true;const state=document.getElementById('state');state.textContent='Guardando perfil…';try{const r=await fetch('/profile',{method:'POST',headers:{'Content-Type':'application/json','X-Activation-Nonce':'${nonce}'},body:JSON.stringify(Object.fromEntries(new FormData(form)))});if(r.ok){form.reset();form.hidden=true;state.textContent='Perfil guardado. La prueba continúa en el backend; vuelve al chat.'}else{const data=await r.json();state.textContent=data.message;button.disabled=false}}catch{state.textContent='No se pudo conectar. Vuelve al chat.';button.disabled=false}});</script></html>`;
  await new Promise((resolve,reject)=>{
    let submitted=false;
    const server=http.createServer(async(req,res)=>{
      res.setHeader('Cache-Control','no-store');
      res.setHeader('X-Content-Type-Options','nosniff');
      res.setHeader('Referrer-Policy','no-referrer');
      res.setHeader('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'`);
      if(!['localhost:3001','127.0.0.1:3001'].includes(req.headers.host)){res.writeHead(403).end();return;}
      if(req.method==='GET'&&req.url==='/'){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(html);return;}
      if(req.method!=='POST'||req.url!=='/profile'||submitted||req.headers['x-activation-nonce']!==nonce
          ||!['http://localhost:3001','http://127.0.0.1:3001'].includes(req.headers.origin)) {res.writeHead(403).end();return;}
      try {
        let body=''; for await(const chunk of req){body+=chunk;if(body.length>4096)throw new Error('Body limit');}
        const data=JSON.parse(body);
        // Allow only the profile fields; never accept roles or status from this form.
        const result=await api('/api/usuarios/me',{method:'PUT',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({nombres:data.nombres,apellidos:data.apellidos,tipoDocumento:data.tipoDocumento,
            documento:data.documento,distritoId:district.id})});
        if(!result.ok){res.writeHead(result.status,{'Content-Type':'application/json'}).end(JSON.stringify({message:'No se pudo guardar. Revisa los datos (DNI: ocho dígitos). HTTP '+result.status}));return;}
        submitted=true;
        res.writeHead(200,{'Content-Type':'application/json'}).end('{"ok":true}');
        server.close(); resolve();
      } catch {res.writeHead(400,{'Content-Type':'application/json'}).end('{"message":"Petición inválida o backend no disponible."}');}
    });
    server.on('error',reject);
    server.listen(3001,'127.0.0.1',()=>console.log('Completa tu perfil en http://localhost:3001 (sin compartir documentos por el chat).'));
  });
}

function technicalPng(){
  const crc32=bytes=>{let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0;};
  const chunk=(name,data)=>{const type=Buffer.from(name);const size=Buffer.alloc(4);size.writeUInt32BE(data.length);const crc=Buffer.alloc(4);crc.writeUInt32BE(crc32(Buffer.concat([type,data])));return Buffer.concat([size,type,data,crc]);};
  const header=Buffer.alloc(13);header.writeUInt32BE(64,0);header.writeUInt32BE(64,4);header[8]=8;header[9]=2;
  const pixels=Buffer.alloc((64*3+1)*64);
  for(let y=0;y<64;y++)for(let x=0;x<64;x++){const i=y*193+1+x*3;const square=(Math.floor(x/8)+Math.floor(y/8))%2;pixels[i]=square?240:30;pixels[i+1]=square?180:90;pixels[i+2]=square?30:160;}
  return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),
    chunk('tEXt',Buffer.from('Description\0PRUEBA TECNICA HU-01 - NO ES UNA INCIDENCIA REAL')),
    chunk('IDAT',deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]);
}

async function verifyReport(){
  const png=technicalPng();
  const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
  let proof;
  try{proof=JSON.parse(await readFile(proofPath,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
  if(!proof){
    const form=new FormData();
    form.append('datos',new Blob([JSON.stringify({tipoBarreraId:barrier.id,distritoId:district.id,
      severidadDeclarada:'BAJA',descripcion:'PRUEBA TECNICA HU-01: comprobacion de registro, Storage privado y consulta. NO representa una incidencia real ni debe derivarse a atencion municipal.',
      direccionReferencia:'Punto tecnico del piloto; no corresponde a una incidencia real.',latitud:-16.3989,longitud:-71.535})],{type:'application/json'}));
    form.append('foto',new Blob([png],{type:'image/png'}),'prueba-tecnica.png');
    // Record the attempt before POST: never retry an ambiguous create automatically.
    proof={attemptedAt:new Date().toISOString(),pending:true,expectedPhotoSha256:sha256(png)};
    await writeFile(proofPath,JSON.stringify(proof,null,2),{mode:0o600,flag:'wx'});
    const created=await api('/api/reportes',{method:'POST',body:form});
    assert.equal(created.status,201,'Report create failed; inspect the attempt before retrying');
    const report=await created.json();
    proof={...proof,reportId:report.id,code:report.codigo,state:report.estado,pending:false,createStatus:201};
    await writeFile(proofPath,JSON.stringify(proof,null,2),{mode:0o600});
  }
  assert.ok(proof.reportId,'An ambiguous create already exists; inspect the database before retrying');
  const detailResponse=await api('/api/reportes/'+proof.reportId);
  assert.equal(detailResponse.status,200);
  const detail=await detailResponse.json();
  assert.equal(detail.estado,'RECIBIDO'); assert.equal(detail.fotos.length,1);
  assert.ok(detail.descripcion.startsWith('PRUEBA TECNICA HU-01:'));
  assert.equal(detail.fotos[0].enlace,`/api/reportes/${proof.reportId}/fotos/${detail.fotos[0].id}`);
  assert.ok(!JSON.stringify(detail).includes('objectKey'));
  const photoResponse=await api(detail.fotos[0].enlace);
  assert.equal(photoResponse.status,200);
  assert.equal(photoResponse.headers.get('content-type'),'image/png');
  assert.ok(photoResponse.headers.get('cache-control')?.includes('no-store'));
  const received=Buffer.from(await photoResponse.arrayBuffer());
  assert.equal(sha256(received),proof.expectedPhotoSha256??proof.sha256??sha256(png),'Downloaded bytes must match the uploaded fixture');
  const unauthReport=await fetch(backend+'/api/reportes/'+proof.reportId,{signal:AbortSignal.timeout(15000)});
  const unauthPhoto=await fetch(backend+detail.fotos[0].enlace,{signal:AbortSignal.timeout(15000)});
  assert.equal(unauthReport.status,401);assert.equal(unauthPhoto.status,401);
  proof={...proof,verifiedAt:new Date().toISOString(),detailStatus:200,photoStatus:200,
    photoBytes:received.length,sha256:sha256(received),photoNoStore:true,unauthenticatedReportStatus:401,unauthenticatedPhotoStatus:401};
  await writeFile(proofPath,JSON.stringify(proof,null,2),{mode:0o600});
  console.log(JSON.stringify(proof,null,2));
}
