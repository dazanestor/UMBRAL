#!/usr/bin/env node
/* Qvadis manager - backend local del videoportero (fase 1).
   - Sirve la UI estatica (www/).
   - /api/brightness?v=<0..100>: brillo REAL del panel via PWM en GPIO7 (pigs/pigpiod).
     El backlight es activo-bajo con PWM invertido: duty 0 = brillo maximo.
   Sin dependencias externas: solo Node estandar + el binario 'pigs' (paquete pigpio).
*/
'use strict';
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const { execFile, execFileSync, spawn } = require('child_process');
const crypto = require('crypto');

// Robustez: un error inesperado (excepción o promesa rechazada) NO debe tirar el videoportero.
// Se registra y el proceso sigue en pie (el panel, la puerta y las llamadas siguen funcionando).
process.on('uncaughtException', (e) => { try { console.error('uncaughtException:', e && e.stack || e); } catch (_) {} });
process.on('unhandledRejection', (e) => { try { console.error('unhandledRejection:', e && e.stack || e); } catch (_) {} });

const ROOT = path.join(__dirname, 'www');
// Tarjetas de audio por PUERTO USB FISICO (estable entre reinicios), no por numero de card:
// la enumeracion puede invertir card0/card1 e intercambiar home<->street. HOME=1-1.2, STREET=1-1.3.
function cardOnPort(port) {
  try {
    for (const d of fs.readdirSync('/sys/class/sound')) {
      if (!/^card\d+$/.test(d)) continue;
      let p = ''; try { p = fs.realpathSync('/sys/class/sound/' + d + '/device'); } catch (e) { continue; }
      if (p.indexOf('/' + port + '/') >= 0) return d.slice(4);
    }
  } catch (e) {}
  return null;
}
const HOME_CARD = cardOnPort('1-1.2') || '0';   // pantalla (micro/altavoz del panel)
const STREET_CARD = cardOnPort('1-1.3') || '1'; // calle
const SNAP_DIR = '/var/lib/qvadis/snaps';
try { fs.mkdirSync(SNAP_DIR, { recursive: true }); } catch (e) {}
// Captura 1 fotograma de la camara configurada (MJPEG/HTTP o RTSP) con ffmpeg.
function snapCamera(cb) {
  if (!CFG.camera || !CFG.camera.enabled || !CFG.camera.url) return cb(null);
  const name = Date.now() + '.jpg';
  const out = path.join(SNAP_DIR, name);
  const url = CFG.camera.url;
  const args = ['-y', '-nostdin'];
  if (/^rtsp:/i.test(url)) args.push('-rtsp_transport', 'tcp');
  args.push('-i', url, '-frames:v', '1', '-q:v', '4', '-f', 'image2', out);
  execFile('ffmpeg', args, { timeout: 12000 }, (e) => {
    if (e || !fs.existsSync(out)) return cb(null);
    pruneRetention();
    cb(name);
  });
}
const PORT = 8080;
const BL_GPIO = 7; // GPIO_LCD (backlight), activo-bajo
const PIR_GPIO = 9; // GPIO_PIR (sensor presencia)

// --- Config persistente ---
const CONFIG_FILE = '/var/lib/qvadis/config.json';
const CALLS_FILE = '/var/lib/qvadis/calls.json';
const CFG = {
  weather: { name: 'Palma', lat: 39.5696, lon: 2.6502 },
  camera: { enabled: false, url: '', type: 'mjpeg' },
  volume: 80,
  micvolume: 85,
  ring: { tone: 'r_Classic', volume: 85 },
  audio: { spkHome: 80, spkStreet: 70, micHome: 90, micStreet: 90 },
  doors: { aux: { enabled: true, name: 'Puerta auxiliar', type: 'door' } },
  dnd: { enabled: false, sched: false, from: '23:00', to: '08:00' },
  pin: '1234',
  screen: { off_after: 60, proximity: true, brightness: 100 },
  ota: { enabled: true },
  mobiles: [],
  remote: { domain: '', provider: 'none', host: '', token: '', url: '' },
};
try { Object.assign(CFG, JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'))); } catch (e) {}
// Escritura ATÓMICA (tmp + fsync + rename): evita que un corte de corriente a mitad de escritura
// deje el fichero corrupto. El rename es atómico en el mismo sistema de ficheros, así que al arrancar
// siempre se lee el fichero completo anterior o el nuevo, nunca uno truncado (si no, se perderían
// todos los ajustes: móviles emparejados, PIN, cámara, audio, DDNS…).
function atomicWrite(file, data, mode) {
  const tmp = file + '.tmp';
  const fd = fs.openSync(tmp, 'w', mode || 0o644);
  try { fs.writeSync(fd, data); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  fs.renameSync(tmp, file);
}
function saveConfig() { try { atomicWrite(CONFIG_FILE, JSON.stringify(CFG, null, 2)); } catch (e) { console.error('saveConfig', e.message); } }

// --- Helpers de sistema ---
function ast(cmd) { return new Promise((res) => { execFile('/usr/sbin/asterisk', ['-rx', cmd], { timeout: 4000 }, (e, out) => res(out || '')); }); }
// --- Moviles SIP dinamicos (emparejamiento desde la UI) ---
const MOBILES_PJSIP = '/var/lib/qvadis/pjsip_mobiles.conf';
const MOBILES_GLOBALS = '/var/lib/qvadis/globals.conf';
function genSecret() { return crypto.randomBytes(12).toString('base64').replace(/[^a-zA-Z0-9]/g, '').slice(0, 14); }
function nextMobileExt() { let n = 1001; const used = new Set((CFG.mobiles || []).map((x) => +x.ext)); while (used.has(n)) n++; return String(n); }
function writeMobilesConfig() {
  const m = CFG.mobiles || [];
  let pj = '; Qvadis - moviles dinamicos (generado por el manager; NO editar a mano)\n';
  m.forEach((x) => {
    pj += `\n[${x.ext}]\ntype=endpoint\ncontext=qvadis\ndisallow=all\nallow=ulaw,alaw\nauth=${x.ext}\naors=${x.ext}\nrtp_symmetric=yes\nforce_rport=yes\nrewrite_contact=yes\ndirect_media=no\nmedia_encryption=sdes\nmedia_encryption_optimistic=yes\n`;
    pj += `[${x.ext}]\ntype=auth\nauth_type=userpass\nusername=${x.ext}\npassword=${x.secret}\n`;
    pj += `[${x.ext}]\ntype=aor\nmax_contacts=1\nremove_existing=yes\nremove_unavailable=yes\nqualify_frequency=30\n`;
  });
  const ring = ['PJSIP/1000'].concat(m.map((x) => 'PJSIP/' + x.ext)).join('&');
  try { atomicWrite(MOBILES_PJSIP, pj); } catch (e) { console.error('mobiles pjsip', e.message); }
  try { atomicWrite(MOBILES_GLOBALS, 'QV_RING=' + ring + '\n'); } catch (e) {}
  ast('pjsip reload'); ast('dialplan reload');
}
// Acceso remoto (listener HTTPS): SOLO estos endpoints, y con token de un móvil emparejado.
const REMOTE_PATHS = ['/api/health', '/api/door/open', '/api/camera/stream.mjpg', '/api/camera/frame.jpg', '/api/snap', '/api/calls', '/api/calls/ack', '/api/device', '/api/cert', '/api/mobiles/fcm',
  // ajustes del portero manejables desde la app (con token de móvil): volúmenes, timbre, no molestar
  '/api/audio', '/api/audio/set', '/api/ringtone', '/api/ringtone/set', '/api/dnd', '/api/dnd/set'];
let CERT_FP = '';
try { CERT_FP = new crypto.X509Certificate(fs.readFileSync('/var/lib/qvadis/certs/qvadis.crt')).fingerprint256; } catch (e) {}
function validToken(t) { return !!t && (CFG.mobiles || []).some((m) => m.token && m.token === t); }
function remoteOk(u, req) {
  if (!REMOTE_PATHS.includes(u.pathname)) return false;
  if (u.pathname === '/api/health') return true;
  const t = u.searchParams.get('token') || (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
  return validToken(t);
}
// --- Push FCM (avisar a la app cuando suena el timbre, con el móvil dormido/fuera) ---
// La cuenta de servicio de Firebase se coloca en este fichero (no se teclea; la aporta el admin).
const FCM_SA_FILE = '/var/lib/qvadis/fcm-service-account.json';
let fcmSA = null; try { fcmSA = JSON.parse(fs.readFileSync(FCM_SA_FILE, 'utf8')); } catch (e) {}
let fcmTok = { token: '', exp: 0 };
function b64url(obj) { return Buffer.from(typeof obj === 'string' ? obj : JSON.stringify(obj)).toString('base64url'); }
function fcmAccessToken() {
  if (!fcmSA) return Promise.resolve('');
  if (fcmTok.token && Date.now() < fcmTok.exp - 60000) return Promise.resolve(fcmTok.token);
  const now = Math.floor(Date.now() / 1000);
  const jwtUnsigned = b64url({ alg: 'RS256', typ: 'JWT' }) + '.' + b64url({ iss: fcmSA.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  let sig; try { sig = crypto.createSign('RSA-SHA256').update(jwtUnsigned).sign(fcmSA.private_key, 'base64url'); } catch (e) { return Promise.resolve(''); }
  const jwt = jwtUnsigned + '.' + sig;
  return fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=' + jwt })
    .then((r) => r.json()).then((j) => { if (j.access_token) { fcmTok = { token: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000 }; return j.access_token; } return ''; }).catch(() => '');
}
function sendPush(title, body, data) {
  if (!fcmSA) return;
  const tokens = (CFG.mobiles || []).map((m) => m.fcm).filter(Boolean);
  if (!tokens.length) return;
  fcmAccessToken().then((at) => {
    if (!at) return;
    tokens.forEach((tk) => {
      // Data-only (sin 'notification'): así onMessageReceived SIEMPRE se ejecuta aunque la app
      // esté en segundo plano o cerrada, y puede despertar el SIP y pintar la llamada. Alta prioridad.
      const msg = { message: { token: tk, data: Object.assign({ title: String(title), body: String(body) }, data || {}), android: { priority: 'high', ttl: '30s' } } };
      fetch('https://fcm.googleapis.com/v1/projects/' + fcmSA.project_id + '/messages:send', { method: 'POST', headers: { Authorization: 'Bearer ' + at, 'Content-Type': 'application/json' }, body: JSON.stringify(msg) }).catch(() => {});
    });
  });
}
function readFileSafe(p) { try { return fs.readFileSync(p, 'utf8'); } catch (e) { return ''; } }
function lanIP() { const ifs = os.networkInterfaces(); for (const k in ifs) { for (const a of ifs[k]) { if (a.family === 'IPv4' && !a.internal) return a.address; } } return '127.0.0.1'; }
function wifiInfo() {
  return new Promise((res) => {
    execFile('iw', ['dev', 'wlan0', 'link'], { timeout: 3000 }, (e, out) => {
      let ssid = '', signal = null;
      (out || '').split('\n').forEach((l) => {
        l = l.trim();
        if (l.startsWith('SSID:')) ssid = l.slice(5).trim();
        else if (l.startsWith('signal:')) signal = parseInt(l.slice(7));
      });
      res({ ssid, signal });
    });
  });
}
// Registro de llamadas (persistente, ultimas 40)
let CALLS = [];
let OTA_LAST = null; // cache del ultimo check OTA {ts, local, remote, available}
let REMOTE = { publicIp: '', lastUpdate: 0, status: 'sin configurar' };
function getPublicIp() {
  // Varios servicios con fallback (algunos los bloquea la red local); UA tipo curl para IP en texto plano.
  const svcs = ['https://ifconfig.me/ip', 'https://ident.me', 'https://api.ipify.org', 'https://icanhazip.com', 'https://checkip.amazonaws.com'];
  const tryOne = (i) => {
    if (i >= svcs.length) return Promise.resolve('');
    const ac = new AbortController(); const to = setTimeout(() => ac.abort(), 6000);
    return fetch(svcs[i], { headers: { 'User-Agent': 'curl/qvadis' }, signal: ac.signal })
      .then((r) => r.text()).then((t) => { clearTimeout(to); t = (t || '').trim(); return /^\d{1,3}(\.\d{1,3}){3}$/.test(t) ? t : tryOne(i + 1); })
      .catch(() => { clearTimeout(to); return tryOne(i + 1); });
  };
  return tryOne(0);
}
function updateDdns() {
  const r = CFG.remote || {};
  return getPublicIp().then((ip) => {
    if (ip) REMOTE.publicIp = ip;
    if (!r.provider || r.provider === 'none') { REMOTE.status = r.domain ? 'IP fija / sin DDNS' : 'sin configurar'; REMOTE.lastUpdate = Date.now(); return; }
    let url = '';
    if (r.provider === 'duckdns') url = 'https://www.duckdns.org/update?domains=' + encodeURIComponent(r.host) + '&token=' + encodeURIComponent(r.token) + '&ip=' + encodeURIComponent(ip || '');
    else if (r.provider === 'generic') url = (r.url || '').replace(/\{ip\}/g, ip || '');
    if (!url) { REMOTE.status = 'config incompleta'; REMOTE.lastUpdate = Date.now(); return; }
    return fetch(url, { headers: { 'User-Agent': 'qvadis-ddns' } })
      .then((resp) => resp.text().then((t) => ({ ok: resp.ok, t: t || '' })))
      .then((o) => {
        const good = o.ok && !/\b(KO|badauth|nohost|notfqdn|abuse|error|!donator)\b/i.test(o.t);
        REMOTE.status = good ? ('actualizado' + (ip ? ' · ' + ip : '')) : ('error: ' + o.t.trim().slice(0, 40));
        REMOTE.lastUpdate = Date.now();
      }).catch(() => { REMOTE.status = 'error de red'; REMOTE.lastUpdate = Date.now(); });
  });
}
try { CALLS = JSON.parse(fs.readFileSync(CALLS_FILE, 'utf8')) || []; } catch (e) {}
function saveCalls() { try { atomicWrite(CALLS_FILE, JSON.stringify(CALLS.slice(-500))); } catch (e) {} }
function logCall(entry) { CALLS.push(Object.assign({ ts: Date.now() }, entry)); CALLS = CALLS.slice(-500); saveCalls(); }
// Retención: capturas y registro de llamadas, máximo 30 días
const RETAIN_MS = 30 * 24 * 3600 * 1000;
function pruneRetention() {
  const cutoff = Date.now() - RETAIN_MS;
  try { fs.readdirSync(SNAP_DIR).forEach((f) => { if (f.endsWith('.jpg')) { const fp = path.join(SNAP_DIR, f); try { if (fs.statSync(fp).mtimeMs < cutoff) fs.unlinkSync(fp); } catch (e) {} } }); } catch (e) {}
  const before = CALLS.length;
  CALLS = CALLS.filter((c) => (c.ts || 0) >= cutoff).slice(-500);
  if (CALLS.length !== before) saveCalls();
}

// --- No molestar ---
function dndActive() {
  const d = CFG.dnd || {};
  if (d.enabled) return true;
  if (!d.sched) return false;
  const now = new Date();
  const cur = now.getHours() * 60 + now.getMinutes();
  const p = (s) => { const m = /^(\d{1,2}):(\d{2})/.exec(s || ''); return m ? +m[1] * 60 + +m[2] : null; };
  const f = p(d.from), t = p(d.to); if (f == null || t == null) return false;
  return f <= t ? (cur >= f && cur < t) : (cur >= f || cur < t);
}

// --- Tiempo (Open-Meteo, gratis sin clave) para rellenar cuando no hay camara ---
let wxCache = null, wxTs = 0, wxKey = '';
function getWeather() {
  const w = CFG.weather;
  const key = w.lat + ',' + w.lon;
  const now = Date.now();
  if (wxCache && wxKey === key && now - wxTs < 30 * 60 * 1000) return Promise.resolve(wxCache);
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + w.lat + '&longitude=' + w.lon +
    '&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code' +
    '&hourly=temperature_2m,weather_code' +
    '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max' +
    '&timezone=auto&forecast_days=6';
  return fetch(url).then((r) => r.json()).then((j) => { j.place = w.name; wxCache = j; wxTs = now; wxKey = key; return j; });
}
function geocode(q) {
  const url = 'https://geocoding-api.open-meteo.com/v1/search?name=' + encodeURIComponent(q) + '&count=6&language=es&format=json';
  return fetch(url).then((r) => r.json()).then((j) => (j.results || []).map((x) => ({
    name: x.name + (x.admin1 ? ', ' + x.admin1 : '') + (x.country ? ' (' + x.country + ')' : ''),
    lat: x.latitude, lon: x.longitude,
  })));
}

// Puertas (modelo QVADIS_VIDEO = activo-ALTO: abrir = 1 durante 2s, cerrado = 0)
const DOORS = { front: 24, aux: 26 }; // GPIO_FRONT_DOOR, GPIO_AUX_DOOR
const DOOR_PULSE_MS = 2000;
const doorBusy = {};
// SEGURIDAD (crítico): apertura es activo-ALTO; si el proceso muere a mitad del pulso,
// el GPIO se queda ALTO y el abridor queda ENGANCHADO (riesgo de quemar la cerradura).
// Forzamos los relés a reposo (BAJO) al arrancar y, de forma SÍNCRONA, al parar/reiniciar.
function doorsOffSync() { for (const w in DOORS) { try { execFileSync('pigs', ['w', String(DOORS[w]), '0']); } catch (e) {} doorBusy[w] = false; } }
try { doorsOffSync(); } catch (e) {}
['SIGTERM', 'SIGINT', 'SIGHUP'].forEach((s) => process.on(s, () => { try { doorsOffSync(); } catch (e) {} process.exit(0); }));
process.on('exit', () => { try { doorsOffSync(); } catch (e) {} });

function pigs(args, cb) {
  execFile('pigs', args.map(String), (err) => { if (err) console.error('pigs', args.join(' '), err.message); if (cb) cb(err); });
}

function openDoor(which) {
  const g = DOORS[which];
  if (g === undefined) return false;
  if (doorBusy[which]) return true; // ya en curso, evita re-disparo
  doorBusy[which] = true;
  console.log('Abriendo puerta', which, 'GPIO' + g);
  pigs(['w', g, 1]);
  setTimeout(() => { pigs(['w', g, 0]); doorBusy[which] = false; console.log('Puerta', which, 'cerrada (fin pulso)'); }, DOOR_PULSE_MS);
  return true;
}

// --- baresip ctrl_tcp (control del panel como endpoint SIP) ---
const BS_HOST = '127.0.0.1', BS_PORT = 4444;
let bsSock = null, bsBuf = '';
const sseClients = [];
function sseSend(obj) {
  const line = 'data: ' + JSON.stringify(obj) + '\n\n';
  for (const r of sseClients) { try { r.write(line); } catch (e) {} }
}
function bsCommand(cmd, params) {
  if (!bsSock) return;
  const obj = { command: cmd };
  if (params) obj.params = params;
  const json = JSON.stringify(obj);
  try { bsSock.write(Buffer.byteLength(json) + ':' + json + ','); } catch (e) {}
}
function bsHandle(data) {
  let m; try { m = JSON.parse(data); } catch (e) { return; }
  if (!m.event) return;
  if (m.type === 'CALL_INCOMING') {
    if (dndActive()) { console.log('Llamada SIP entrante con No molestar -> rechazada'); logCall({ type: 'timbre', outcome: 'dnd' }); bsCommand('hangup'); return; }
    wake(); sseSend({ type: 'incoming', peer: m.peeruri || 'Puerta' });
    // La CALLE (baresip 1000) contesta sola al entrar el timbre: pone su audio en linea y asi
    // el `application Dial` del originate marca a los moviles de inmediato (no espera al panel).
    bsCommand('accept');
  }
  else if (m.type === 'CALL_ESTABLISHED') {
    // Con answermode=auto baresip (la CALLE) establece solo al sonar; NO es "atendida".
    // 'atendida' se marca cuando una PERSONA contesta: panel (/api/call/answer) o movil (/api/calls/ack).
    sseSend({ type: 'established' });
  }
  else if (m.type === 'CALL_CLOSED') sseSend({ type: 'closed' });
}
function bsParse() {
  for (;;) {
    const c = bsBuf.indexOf(':');
    if (c < 0) return;
    const len = parseInt(bsBuf.slice(0, c), 10);
    if (!isFinite(len)) { bsBuf = ''; return; }
    if (bsBuf.length < c + 1 + len + 1) return;
    const data = bsBuf.slice(c + 1, c + 1 + len);
    bsBuf = bsBuf.slice(c + 1 + len + 1);
    bsHandle(data);
  }
}
function bsConnect() {
  bsSock = net.connect(BS_PORT, BS_HOST);
  bsSock.on('data', (d) => { bsBuf += d.toString(); bsParse(); });
  bsSock.on('error', () => {});
  bsSock.on('close', () => { bsSock = null; setTimeout(bsConnect, 2000); });
}

// --- Timbre de calle (ring) -> suena en panel + moviles a la vez ---
const RING_GPIO = 25; // GPIO_RING, activo-bajo (pull-up)
let ringBusy = false;
// Auto-encendido ("escuchar puerta"): NO es una llamada; abre el canal de voz con la placa de
// calle (audio en card 0, habilitado por GPIO 16/22/23/17/27). Habilita la ruta de audio.
let monActive = false;
// Intercom local CALLE<->PANTALLA por PulseAudio (AEC webrtc + doble loopback), como el original.
function audioEnv() {
  const a = CFG.audio || {};
  return Object.assign({}, process.env, {
    XDG_RUNTIME_DIR: '/run/user/' + process.getuid(),
    SPK_HOME: String(a.spkHome == null ? 80 : a.spkHome),
    SPK_STREET: String(a.spkStreet == null ? 70 : a.spkStreet),
    MIC_HOME: String(a.micHome == null ? 90 : a.micHome),
    MIC_STREET: String(a.micStreet == null ? 90 : a.micStreet),
  });
}
function intercomCmd(on) {
  execFile('sh', ['/opt/qvadis-ui/intercom-' + (on ? 'on' : 'off') + '.sh'], { env: audioEnv() },
    (e) => { if (e) console.error('intercom', on, e.message); });
}
function monitorStart() { monActive = true; intercomCmd(true); }
function monitorStop() { monActive = false; intercomCmd(false); }
// Aplica los 4 volumenes a los dispositivos del intercom (en vivo, si esta cargado)
function applyVolumes() {
  const a = CFG.audio || {}; const env = audioEnv();
  const set = (kind, dev, pct) => execFile('pactl', [kind === 'sink' ? 'set-sink-volume' : 'set-source-volume', dev, Math.max(0, Math.min(100, pct == null ? 80 : pct)) + '%'], { env }, () => {});
  set('sink', 'home_ec_sink', a.spkHome); set('sink', 'street_ec_sink', a.spkStreet);
  set('source', 'home_ec_source', a.micHome); set('source', 'street_ec_source', a.micStreet);
}

// Timbre: reproduce el tono (WAV) por el altavoz de la PANTALLA (home), como el original.
const SND_DIR = '/opt/qvadis-ui/sounds';
const HOME_SINK = 'alsa_output.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-stereo';
function playRing() {
  const r = CFG.ring || {};
  const tone = (r.tone || 'r_Classic').replace(/[^0-9A-Za-z_]/g, '');
  const vol = Math.round((Math.max(0, Math.min(100, r.volume == null ? 85 : r.volume)) / 100) * 65536);
  const f = SND_DIR + '/' + tone + '.wav';
  execFile('paplay', ['--device=' + HOME_SINK, '--volume=' + vol, f],
    { env: Object.assign({}, process.env, { XDG_RUNTIME_DIR: '/run/user/' + process.getuid() }) },
    (e) => { if (e) console.error('ring play', e.message); });
}
function doorbellRing() {
  if (ringBusy) return false;
  if (dndActive()) { console.log('Timbre con No molestar activo -> no suena'); logCall({ type: 'timbre', outcome: 'dnd' }); return false; }
  ringBusy = true;
  console.log('Timbre -> grupo puerta (panel + moviles)');
  // Asegura los dispositivos AEC (por si el boot los cargó antes de que Pulse estuviera listo);
  // es idempotente y hay margen (RING_DELAY) antes del originate para que baresip pueda abrir el audio.
  execFile('sh', ['/var/lib/qvadis/audio-aec-up.sh'], { env: audioEnv() }, () => {});
  logCall({ type: 'timbre', outcome: 'sonando' });
  const entry = CALLS[CALLS.length - 1]; // referencia de ESTA llamada (para foto/atendida/perdida)
  // Autocaptura: foto del visitante al sonar (si hay camara), adjunta a esta llamada
  snapCamera((name) => { if (name && entry) { entry.photo = name; saveCalls(); } });
  // Push a los moviles (despierta la app para que se registre por SIP antes del Dial)
  sendPush('Puerta', 'Alguien llama a la puerta', { type: 'door', ts: String(entry.ts) });
  wake();
  playRing(); // tono del timbre por el altavoz de la pantalla
  // Local/600 = audio placeholder de la placa de calle (eco); extension door = Dial al grupo.
  // Pequeño retardo para dar tiempo a que los moviles despertados por el push se registren
  // (el panel ya esta registrado). Sin esto, el Dial fija los contactos antes de que el movil exista.
  const RING_DELAY = (CFG.mobiles && CFG.mobiles.length) ? 2500 : 0;
  setTimeout(() => {
    // Origina la CALLE (baresip 1000, answermode=auto -> contesta sola y pone su audio de calle)
    // y, ya con la calle en linea, marca a los moviles -> el movil que atienda HABLA CON LA CALLE.
    // El panel se entera por el CALL_INCOMING de baresip 1000 y usa su intercom local al atender.
    const mob = (CFG.mobiles || []).map((x) => 'PJSIP/' + x.ext).join('&');
    const cmd = mob
      ? 'channel originate PJSIP/1000 application Dial ' + mob + ',45'
      : 'channel originate Local/600@qvadis extension door@qvadis'; // sin moviles: flujo antiguo (solo panel)
    execFile('/usr/sbin/asterisk', ['-rx', cmd], (err) => {
      if (err) console.error('originate error:', err.message);
    });
  }, RING_DELAY);
  // Llamada perdida: si en ~50s (Dial dura 45s) nadie la atendio, avisa con la captura
  setTimeout(() => {
    if (entry && entry.outcome === 'sonando') {
      entry.outcome = 'perdida'; saveCalls();
      sendPush('Puerta', 'Llamada perdida en la puerta', { type: 'missed', photo: entry.photo || '', ts: String(entry.ts) });
    }
  }, 50000);
  setTimeout(() => { ringBusy = false; }, 20000); // anti-repeticion
  return true;
}
// Vigilancia del pin del timbre (flanco 1->0)
let lastRing = 1;
setInterval(() => {
  execFile('pigs', ['r', String(RING_GPIO)], (err, stdout) => {
    if (err) return;
    const v = parseInt(String(stdout).trim(), 10);
    if (lastRing === 1 && v === 0) doorbellRing();
    lastRing = v;
  });
}, 60);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json',
};

function setBrightness(pct) {
  pct = Number(pct);
  if (!isFinite(pct)) return;
  pct = Math.max(0, Math.min(100, pct));
  // Invertido: 100% -> duty 0 (siempre bajo = maximo brillo); 0% -> duty 255 (apagado)
  const duty = Math.round(255 * (1 - pct / 100));
  execFile('pigs', ['p', String(BL_GPIO), String(duty)], (err) => {
    if (err) console.error('pigs error:', err.message);
  });
}

// --- Salvapantallas + presencia (PIR) ---
let curBrightness = (CFG.screen && CFG.screen.brightness) || 100;
let screenOn = true;
let lastActivity = Date.now();
function wake() { lastActivity = Date.now(); if (!screenOn) { screenOn = true; setBrightness(curBrightness); sseSend({ type: 'screen', on: true }); } }
setInterval(() => {
  const off = (CFG.screen && CFG.screen.off_after) || 0;
  if (off > 0 && screenOn && Date.now() - lastActivity > off * 1000) { screenOn = false; setBrightness(0); sseSend({ type: 'screen', on: false }); }
}, 2000);
let lastPir = 0;
setInterval(() => {
  if (!(CFG.screen && CFG.screen.proximity)) return;
  execFile('pigs', ['r', String(PIR_GPIO)], (e, out) => {
    if (e) return; const v = parseInt(String(out).trim(), 10);
    if (v === 1 && lastPir === 0) wake();
    lastPir = v;
  });
}, 500);

// --- Cámara MJPEG: UN solo ffmpeg compartido por TODOS los clientes ---
// Antes cada <img>/cliente lanzaba su propio ffmpeg -> 2ª sesión RTSP al mismo stream
// (muchas cámaras/tokens permiten solo UNA) + doble transcodificación en la CM3+ -> parpadeo.
// Ahora un único ffmpeg lee el origen y difunde su salida mpjpeg a todos los clientes.
let camFF = null, camClients = [], camStopTimer = null;
let camBuf = Buffer.alloc(0), camLastFrame = null, camLastPoll = 0, camFailUntil = 0;
const JPEG_SOI = Buffer.from([0xFF, 0xD8]), JPEG_EOI = Buffer.from([0xFF, 0xD9]);
function camStart() {
  if (camFF) return;
  if (Date.now() < camFailUntil) return;   // backoff: si el origen está caído, no machacar ffmpeg
  const c = CFG.camera || {};
  if (!c.enabled || !c.url) return;
  const args = ['-nostdin', '-loglevel', 'error'];
  if (/^rtsp:/i.test(c.url)) args.push('-rtsp_transport', 'tcp');
  args.push('-i', c.url, '-f', 'mpjpeg', '-q:v', '5', '-r', '12', '-');
  let gotFrame = false;
  try { camFF = spawn('ffmpeg', args); } catch (e) { camFF = null; camFailUntil = Date.now() + 5000; return; }
  camFF.stdout.on('data', (chunk) => {
    for (const r of camClients) { try { r.write(chunk); } catch (e) {} }          // clientes MJPEG (si los hay)
    camBuf = Buffer.concat([camBuf, chunk]);                                       // último fotograma para frame.jpg
    if (camBuf.length > 3 * 1048576) camBuf = camBuf.slice(camBuf.length - 1048576);
    const end = camBuf.lastIndexOf(JPEG_EOI);
    if (end > 1) { const s = camBuf.lastIndexOf(JPEG_SOI, end); if (s >= 0 && s < end) { camLastFrame = camBuf.slice(s, end + 2); camBuf = camBuf.slice(end + 2); gotFrame = true; } }
  });
  camFF.stderr.on('data', () => {});
  camFF.on('error', () => { camFF = null; });
  camFF.on('close', () => {
    camFF = null; camBuf = Buffer.alloc(0);
    if (!gotFrame) camFailUntil = Date.now() + 5000;   // origen caído (p. ej. 401): espera 5 s antes de reintentar
    if (camClients.length || Date.now() - camLastPoll < 4000) setTimeout(camStart, gotFrame ? 1000 : 5000);
  });
}
function camStopLater() {  // para el ffmpeg si no hay ni clientes MJPEG ni polling reciente de frame.jpg
  clearTimeout(camStopTimer);
  camStopTimer = setTimeout(() => {
    if (camFF && camClients.length === 0 && Date.now() - camLastPoll > 3500) { try { camFF.kill('SIGKILL'); } catch (e) {} camFF = null; }
  }, 3500);
}
function camRestart() { camLastFrame = null; camBuf = Buffer.alloc(0); if (camFF) { try { camFF.kill('SIGKILL'); } catch (e) {} camFF = null; } } // al cambiar la URL

function handler(req, res, remote) {
  let u;
  try { u = new URL(req.url, 'http://localhost'); } catch (e) { res.writeHead(400); return res.end('bad'); }
  const J = (o) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
  // En el listener remoto (HTTPS) solo pasan los endpoints permitidos y con token válido
  if (remote && !remoteOk(u, req)) { res.writeHead(403, { 'Content-Type': 'application/json' }); return res.end('{"error":"forbidden"}'); }

  if (u.pathname === '/api/brightness') {
    const v = Number(u.searchParams.get('v'));
    if (isFinite(v)) { curBrightness = Math.max(0, Math.min(100, v)); CFG.screen.brightness = curBrightness; saveConfig(); screenOn = true; lastActivity = Date.now(); setBrightness(curBrightness); }
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('ok');
  }
  if (u.pathname === '/api/activity') { wake(); res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end('{"ok":true}'); }
  if (u.pathname === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, ts: Date.now() }));
  }
  if (u.pathname === '/api/weather') {
    getWeather()
      .then((j) => { res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(j)); })
      .catch((e) => { res.writeHead(502, { 'Content-Type': 'application/json' }); res.end('{"error":"weather"}'); });
    return;
  }
  if (u.pathname === '/api/weather/location') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(CFG.weather));
  }
  if (u.pathname === '/api/camera') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(CFG.camera));
  }
  if (u.pathname === '/api/camera/set') {
    const enabled = u.searchParams.get('enabled') === '1';
    const url = (u.searchParams.get('url') || '').slice(0, 300);
    const type = (u.searchParams.get('type') || 'mjpeg');
    CFG.camera = { enabled: enabled && !!url, url, type };
    saveConfig();
    camRestart();   // reinicia el ffmpeg compartido con la nueva URL (o lo para si se desactiva)
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, camera: CFG.camera }));
  }
  if (u.pathname === '/api/camera/snapshot') {
    snapCamera((name) => {
      if (!name) return J({ ok: false });
      // adjunta a la ultima llamada reciente (<2 min) o crea una entrada 'foto'
      const last = CALLS[CALLS.length - 1];
      if (last && (Date.now() - last.ts) < 120000) { last.photo = name; saveCalls(); }
      else logCall({ type: 'foto', outcome: 'foto', photo: name });
      J({ ok: true, photo: name });
    });
    return;
  }
  if (u.pathname === '/api/snap') {
    const f = (u.searchParams.get('f') || '').replace(/[^0-9A-Za-z.]/g, '');
    const fp = path.join(SNAP_DIR, f);
    if (!f || f.indexOf('..') >= 0 || !fp.startsWith(SNAP_DIR + path.sep)) { res.writeHead(400); return res.end('bad'); }
    return fs.readFile(fp, (e, d) => { if (e) { res.writeHead(404); return res.end('404'); } res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=3600' }); res.end(d); });
  }
  if (u.pathname === '/api/camera/stream.mjpg') {
    if (!CFG.camera || !CFG.camera.enabled || !CFG.camera.url) { res.writeHead(503); return res.end('no cam'); }
    res.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace;boundary=ffmpeg', 'Cache-Control': 'no-cache', 'Connection': 'close' });
    camClients.push(res);          // todos los clientes comparten UN ffmpeg (ver camStart)
    clearTimeout(camStopTimer);
    camStart();
    const drop = () => { const i = camClients.indexOf(res); if (i >= 0) camClients.splice(i, 1); camStopLater(); };
    req.on('close', drop); res.on('close', drop);
    return;
  }
  // Último fotograma JPEG (para mostrar la cámara SIN parpadeo: el panel hace polling con doble
  // búfer en vez de un <img> multipart, que en WebKit parpadea). Reutiliza el ffmpeg compartido.
  if (u.pathname === '/api/camera/frame.jpg') {
    if (!CFG.camera || !CFG.camera.enabled || !CFG.camera.url) { res.writeHead(503); return res.end('no cam'); }
    camLastPoll = Date.now();
    camStart(); camStopLater();
    if (camLastFrame) { res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' }); return res.end(camLastFrame); }
    res.writeHead(503); return res.end('no frame');
  }
  if (u.pathname === '/api/weather/search') {
    geocode(u.searchParams.get('q') || '')
      .then((list) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(list)); })
      .catch((e) => { res.writeHead(502, { 'Content-Type': 'application/json' }); res.end('[]'); });
    return;
  }
  if (u.pathname === '/api/weather/set') {
    const lat = parseFloat(u.searchParams.get('lat')), lon = parseFloat(u.searchParams.get('lon'));
    const name = (u.searchParams.get('name') || '').slice(0, 60);
    if (isFinite(lat) && isFinite(lon) && name) {
      CFG.weather = { name, lat, lon }; saveConfig(); wxCache = null;
      res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ ok: true, weather: CFG.weather }));
    }
    res.writeHead(400, { 'Content-Type': 'application/json' }); return res.end('{"ok":false}');
  }
  if (u.pathname === '/api/door/open') {
    const which = u.searchParams.get('which') || 'front';
    const ok = openDoor(which);
    res.writeHead(ok ? 200 : 400, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ok, door: which }));
  }
  if (u.pathname === '/api/ring') {
    const ok = doorbellRing();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ok }));
  }
  if (u.pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
    res.write(': ok\n\n');
    sseClients.push(res);
    req.on('close', () => { const i = sseClients.indexOf(res); if (i >= 0) sseClients.splice(i, 1); });
    return;
  }
  if (u.pathname === '/api/call/answer') {
    const r = CALLS.slice().reverse().find((c) => c.type === 'timbre' && c.outcome === 'sonando' && (Date.now() - (c.ts || 0) < 90000));
    if (r) { r.outcome = 'atendida'; saveCalls(); }     // el panel contesta -> atendida
    monitorStart();        // audio del panel <-> calle por loopbacks (independiente de baresip)
    bsCommand('hangup');   // corta la llamada SIP (la calle) -> cancela el Dial a los moviles
    res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end('{"ok":true}');
  }
  if (u.pathname === '/api/call/hangup') { bsCommand('hangup'); monitorStop(); res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end('{"ok":true}'); }
  if (u.pathname === '/api/ringtone') {
    fs.readdir(SND_DIR, (e, files) => {
      const tones = (files || []).filter((f) => /^r_.*\.wav$/.test(f)).map((f) => f.replace(/\.wav$/, '')).sort();
      J({ tones, current: (CFG.ring && CFG.ring.tone) || 'r_Classic', volume: (CFG.ring && CFG.ring.volume != null) ? CFG.ring.volume : 85 });
    });
    return;
  }
  if (u.pathname === '/api/ringtone/set') {
    CFG.ring = CFG.ring || {};
    const t = u.searchParams.get('tone'); if (t) CFG.ring.tone = t.replace(/[^0-9A-Za-z_]/g, '');
    const v = u.searchParams.get('volume'); if (v !== null && v !== '') CFG.ring.volume = Math.max(0, Math.min(100, parseInt(v, 10) || 85));
    saveConfig();
    if (u.searchParams.get('test')) playRing();
    return J({ ok: true, ring: CFG.ring });
  }
  if (u.pathname === '/api/audio') { return J(CFG.audio || {}); }
  if (u.pathname === '/api/audio/set') {
    CFG.audio = CFG.audio || {};
    ['spkHome', 'spkStreet', 'micHome', 'micStreet'].forEach((k) => { const v = u.searchParams.get(k); if (v !== null && v !== '') CFG.audio[k] = Math.max(0, Math.min(100, parseInt(v, 10) || 0)); });
    saveConfig(); applyVolumes();
    return J({ ok: true, audio: CFG.audio });
  }
  if (u.pathname === '/api/monitor/start' || u.pathname === '/api/intercom/start') { monitorStart(); return J({ ok: true }); }
  if (u.pathname === '/api/monitor/stop' || u.pathname === '/api/intercom/stop') { monitorStop(); return J({ ok: true }); }
  // Certificado PÚBLICO de la placa (PEM) para que la app lo ancle como CA raíz en Linphone (SIP-TLS).
  if (u.pathname === '/api/cert') {
    const pem = readFileSafe('/var/lib/qvadis/certs/qvadis.crt');
    res.writeHead(pem ? 200 : 404, { 'Content-Type': 'application/x-pem-file' });
    return res.end(pem || '');
  }
  // Un movil atendio/abrio: marca la llamada en curso como atendida (cancela el push de perdida)
  if (u.pathname === '/api/calls/ack') {
    const r = CALLS.slice().reverse().find((c) => c.type === 'timbre' && c.outcome === 'sonando' && (Date.now() - (c.ts || 0) < 90000));
    if (r) { r.outcome = 'atendida'; saveCalls(); }
    return J({ ok: true, acked: !!r });
  }
  if (u.pathname === '/api/call/mute') {
    const on = u.searchParams.get('on') === '1';
    execFile('amixer', ['-c', HOME_CARD, 'sset', 'Mic', on ? 'nocap' : 'cap'], () => {});
    res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ ok: true, muted: on }));
  }
  if (u.pathname === '/api/reboot') {
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true}');
    setTimeout(() => execFile('sudo', ['-n', 'systemctl', 'reboot'], () => {}), 300);
    return;
  }
  // ---- OTA A/B ----
  if (u.pathname === '/api/ota/config') { return J({ enabled: CFG.ota.enabled !== false }); }
  if (u.pathname === '/api/ota/config/set') {
    CFG.ota.enabled = u.searchParams.get('enabled') !== '0'; saveConfig();
    return J({ ok: true, enabled: CFG.ota.enabled });
  }
  if (u.pathname === '/api/ota/status') {
    execFile('sudo', ['-n', '/usr/local/sbin/qvadis-ota', 'status'], { timeout: 8000 }, (e, o) => {
      const s = {}; String(o || '').trim().split('\n').forEach((l) => { const i = l.indexOf('='); if (i > 0) s[l.slice(0, i)] = l.slice(i + 1); });
      J({ ok: !e, version: s.version_local || '?', activeSlot: s.slot_activo || '', defaultSlot: s.slot_por_defecto || '', pending: (s.pending && s.pending !== 'no') ? s.pending : null, lastCheck: OTA_LAST });
    });
    return;
  }
  if (u.pathname === '/api/ota/check') {
    if (CFG.ota.enabled === false) return J({ ok: false, disabled: true });
    execFile('sudo', ['-n', '/usr/local/sbin/qvadis-ota', 'check'], { timeout: 25000 }, (e, o) => {
      const t = String(o || ''); const m = /local=(\S+)\s+remota=(\S+)/.exec(t);
      OTA_LAST = { ts: Date.now(), local: m ? m[1] : '?', remote: m ? m[2] : '?', available: /update=si/.test(t), configured: !/remota=\?/.test(t) };
      J({ ok: true, ...OTA_LAST });
    });
    return;
  }
  if (u.pathname === '/api/ota/update') {
    if (CFG.ota.enabled === false) return J({ ok: false, disabled: true });
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true,"started":true}');
    execFile('sh', ['-c', 'sudo -n /usr/local/sbin/qvadis-ota update >/var/log/qvadis-ota-run.log 2>&1 &'], () => {});
    return;
  }
  if (u.pathname === '/api/ota/rollback') {
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true,"started":true}');
    execFile('sh', ['-c', 'sudo -n /usr/local/sbin/qvadis-ota rollback >/var/log/qvadis-ota-run.log 2>&1 &'], () => {});
    return;
  }
  if (u.pathname === '/api/ntp') { execFile('timedatectl', ['show', '-p', 'NTP', '--value'], { timeout: 3000 }, (e, o) => { J({ auto: String(o).trim() === 'yes' }); }); return; }
  if (u.pathname === '/api/ntp/set') { const on = u.searchParams.get('on') === '1'; execFile('sudo', ['-n', 'timedatectl', 'set-ntp', on ? 'true' : 'false'], { timeout: 5000 }, (e) => { J({ ok: !e, auto: on }); }); return; }
  if (u.pathname === '/api/datetime/set') {
    const dt = (u.searchParams.get('dt') || '').trim();
    if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(dt)) return J({ ok: false, err: 'format' });
    const full = dt.length === 16 ? dt + ':00' : dt;
    // Ajustar hora manual exige NTP desactivado: lo apagamos primero y luego fijamos la hora.
    execFile('sudo', ['-n', 'timedatectl', 'set-ntp', 'false'], { timeout: 5000 }, () => {
      execFile('sudo', ['-n', 'timedatectl', 'set-time', full], { timeout: 5000 }, (e) => { J({ ok: !e, auto: false }); });
    });
    return;
  }
  if (u.pathname === '/api/doors') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify(CFG.doors)); }
  if (u.pathname === '/api/doors/set') {
    CFG.doors.aux = {
      enabled: u.searchParams.get('enabled') !== '0',
      name: (u.searchParams.get('name') || 'Puerta auxiliar').slice(0, 24),
      type: u.searchParams.get('type') || 'door',
    };
    saveConfig(); res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ ok: true, doors: CFG.doors }));
  }

  if (u.pathname === '/api/diag') {
    const load = parseFloat(readFileSafe('/proc/loadavg').split(' ')[0]) || 0;
    const ncpu = os.cpus().length || 1;
    const mi = readFileSafe('/proc/meminfo');
    const mt = +(/MemTotal:\s+(\d+)/.exec(mi) || [])[1] || 0, ma = +(/MemAvailable:\s+(\d+)/.exec(mi) || [])[1] || 0;
    const up = parseFloat(readFileSafe('/proc/uptime').split(' ')[0]) || 0;
    let disk = {}; try { const s = fs.statfsSync('/'); disk = { freeMB: Math.round(s.bsize * s.bavail / 1048576), totalMB: Math.round(s.bsize * s.blocks / 1048576) }; } catch (e) {}
    Promise.all([ast('pjsip show contacts'), wifiInfo()]).then(([c, wifi]) => {
      // Cualquier móvil emparejado (su extensión) que aparezca registrado en los contactos
      const sipMobile = (CFG.mobiles || []).some((m) => new RegExp('\\b' + String(m.ext).replace(/[^0-9]/g, '') + '/').test(c));
      J({ cpu: Math.min(100, Math.round(load / ncpu * 100)), load, ncpu, memUsedMB: Math.round((mt - ma) / 1024), memTotalMB: Math.round(mt / 1024), disk, uptime: up, sipPanel: /1000\//.test(c), sipMobile, wifi });
    });
    return;
  }
  if (u.pathname === '/api/remote') {
    const r = CFG.remote || {};
    return J({ domain: r.domain || '', provider: r.provider || 'none', host: r.host || '', hasToken: !!r.token, url: r.url || '', publicIp: REMOTE.publicIp, lastUpdate: REMOTE.lastUpdate, status: REMOTE.status, push: !!fcmSA, httpsPort: 8443, tlsPort: 5061, certFingerprint: CERT_FP });
  }
  if (u.pathname === '/api/remote/set') {
    const old = CFG.remote || {};
    CFG.remote = {
      domain: (u.searchParams.get('domain') || '').slice(0, 120).trim(),
      provider: u.searchParams.get('provider') || 'none',
      host: (u.searchParams.get('host') || '').slice(0, 120).trim(),
      token: (u.searchParams.get('token') || '') || (old.token || ''), // vacío = conservar
      url: (u.searchParams.get('url') || '').slice(0, 300).trim(),
    };
    saveConfig();
    updateDdns().then(() => J({ ok: true, status: REMOTE.status, publicIp: REMOTE.publicIp }));
    return;
  }
  if (u.pathname === '/api/device') {
    const ci = readFileSafe('/proc/cpuinfo');
    const serial = (/Serial\s*:\s*(\w+)/.exec(ci) || [])[1] || '';
    const model = (readFileSafe('/proc/device-tree/model').replace(/\0/g, '').trim()) || (/Model\s*:\s*(.+)/.exec(ci) || [])[1] || '';
    const tz = readFileSafe('/etc/timezone').trim() || (Intl.DateTimeFormat().resolvedOptions().timeZone || '');
    const camera = !!(CFG.camera && CFG.camera.enabled && CFG.camera.url);
    return J({ hostname: os.hostname(), serial, model, timezone: tz, fw: 'Qvadis abierto 1.0', kernel: os.release(), host: lanIP(), sipExt: '1001', sipPort: 5060, camera });
  }
  if (u.pathname === '/api/wifi') { wifiInfo().then(J); return; }
  if (u.pathname === '/api/wifi/scan') {
    // Buscar redes: el helper escanea y reinicia el supplicant (interrupcion breve y segura).
    execFile('sudo', ['-n', '/usr/local/sbin/qvadis-wifi', 'scan'], { timeout: 45000 }, (e, o) => {
      const nets = String(o || '').trim().split('\n').filter(Boolean).map((l) => {
        const p = l.split('\t'); return { ssid: p[0], signal: Number(p[1]), secure: p[2] === '1' };
      }).sort((a, b) => b.signal - a.signal);
      J({ ok: !e, networks: nets });
    });
    return;
  }
  if (u.pathname === '/api/wifi/connect') {
    const ssid = (u.searchParams.get('ssid') || '').trim();
    const psk = u.searchParams.get('psk') || '';
    // Rechaza caracteres de control (saltos de línea, etc.): defensa adicional contra inyección
    // en el config de wpa_supplicant (el helper además escribe el SSID en hex).
    if (!ssid || ssid.length > 32 || /[\x00-\x1f]/.test(ssid)) return J({ ok: false, err: 'ssid' });
    if (psk && (psk.length < 8 || psk.length > 63 || /[\x00-\x1f]/.test(psk))) return J({ ok: false, err: 'psk' });
    // El helper revierte solo si no asocia; timeout amplio para cubrir el reinicio del supplicant.
    execFile('sudo', ['-n', '/usr/local/sbin/qvadis-wifi', 'connect', ssid, psk], { timeout: 60000 }, (e, o) => {
      const out = String(o || '');
      if (!e && /^OK/.test(out)) { const m = out.match(/ip=(\S+)/); J({ ok: true, ssid, ip: m ? m[1] : '' }); }
      else J({ ok: false, err: 'connect', reverted: /reverted/.test(out) });
    });
    return;
  }
  if (u.pathname === '/api/net') {
    execFile('sudo', ['-n', '/usr/local/sbin/qvadis-net', 'show'], { timeout: 6000 }, (e, o) => {
      const m = {}; String(o || '').trim().split('\n').forEach((l) => { const i = l.indexOf('='); if (i > 0) m[l.slice(0, i)] = l.slice(i + 1); });
      const ipc = m.ip || ''; const ip = ipc.split('/')[0]; const prefix = ipc.includes('/') ? Number(ipc.split('/')[1]) : 24;
      J({ mode: m.mode || 'dhcp', ip, prefix, gateway: m.gateway || '', dns: (m.dns || '').trim().split(/\s+/).filter(Boolean) });
    });
    return;
  }
  if (u.pathname === '/api/net/set') {
    const mode = u.searchParams.get('mode') || 'dhcp';
    const ipre = /^(\d{1,3}\.){3}\d{1,3}$/;
    if (mode === 'dhcp') {
      execFile('sudo', ['-n', '/usr/local/sbin/qvadis-net', 'dhcp'], { timeout: 30000 }, (e, o) => {
        const out = String(o || ''); J({ ok: !e && /^OK/.test(out), mode: 'dhcp', reverted: /reverted/.test(out) });
      });
      return;
    }
    const ip = (u.searchParams.get('ip') || '').trim();
    const prefix = Math.max(1, Math.min(32, parseInt(u.searchParams.get('prefix')) || 24));
    const gw = (u.searchParams.get('gateway') || '').trim();
    const dns1 = (u.searchParams.get('dns1') || '').trim();
    const dns2 = (u.searchParams.get('dns2') || '').trim();
    if (!ipre.test(ip) || !ipre.test(gw) || (dns1 && !ipre.test(dns1)) || (dns2 && !ipre.test(dns2))) return J({ ok: false, err: 'bad-input' });
    const args = ['-n', '/usr/local/sbin/qvadis-net', 'static', ip + '/' + prefix, gw];
    if (dns1) args.push(dns1);
    if (dns2) args.push(dns2);
    execFile('sudo', args, { timeout: 30000 }, (e, o) => {
      const out = String(o || ''); J({ ok: !e && /^OK/.test(out), mode: 'static', reverted: /reverted/.test(out) });
    });
    return;
  }
  if (u.pathname === '/api/volume') { return J({ volume: CFG.volume }); }
  if (u.pathname === '/api/volume/set') {
    const v = Math.max(0, Math.min(100, parseInt(u.searchParams.get('v')) || 0));
    CFG.volume = v; saveConfig();
    execFile('amixer', ['-c', HOME_CARD, 'sset', 'PCM', v + '%', 'unmute'], () => {});
    return J({ ok: true, volume: v });
  }
  if (u.pathname === '/api/volume/test') {
    // tono breve para calibrar el volumen (habilitar amplificador)
    pigs(['w', 16, 1]); pigs(['w', 22, 1]); pigs(['w', 23, 1]);
    execFile('aplay', ['-D', 'plughw:' + HOME_CARD, '/opt/qvadis-ui/sounds/voltone.wav'], () => {});
    return J({ ok: true });
  }
  if (u.pathname === '/api/micvolume') { return J({ micvolume: CFG.micvolume }); }
  if (u.pathname === '/api/micvolume/set') {
    const v = Math.max(0, Math.min(100, parseInt(u.searchParams.get('v')) || 0));
    CFG.micvolume = v; saveConfig();
    execFile('amixer', ['-c', HOME_CARD, 'sset', 'Mic', v + '%', 'cap'], () => {});
    return J({ ok: true, micvolume: v });
  }
  if (u.pathname === '/api/mic/test') {
    // habilitar micro+altavoz, grabar 3s y reproducir (calibrar micro)
    pigs(['w', 16, 1]); pigs(['w', 22, 1]); pigs(['w', 23, 1]); pigs(['w', 17, 1]); pigs(['w', 27, 1]);
    execFile('sh', ['-c', 'arecord -D plughw:' + HOME_CARD + ' -f S16_LE -r 48000 -c 1 -d 3 /tmp/mictest.wav && aplay -D plughw:' + HOME_CARD + ' /tmp/mictest.wav'], () => {});
    return J({ ok: true });
  }
  if (u.pathname === '/api/dnd') { return J(Object.assign({ active: dndActive() }, CFG.dnd)); }
  if (u.pathname === '/api/dnd/set') {
    CFG.dnd = {
      enabled: u.searchParams.get('enabled') === '1',
      sched: u.searchParams.get('sched') === '1',
      from: u.searchParams.get('from') || CFG.dnd.from,
      to: u.searchParams.get('to') || CFG.dnd.to,
    };
    saveConfig(); return J({ ok: true, dnd: CFG.dnd, active: dndActive() });
  }
  if (u.pathname === '/api/pin') { return J({ set: !!CFG.pin }); }
  if (u.pathname === '/api/pin/verify') { return J({ ok: (u.searchParams.get('pin') || '') === CFG.pin }); }
  if (u.pathname === '/api/pin/set') {
    const old = u.searchParams.get('old') || '', nw = u.searchParams.get('new') || '';
    if (CFG.pin && old !== CFG.pin) return J({ ok: false, err: 'old' });
    if (!/^\d{4}$/.test(nw)) return J({ ok: false, err: 'format' });
    CFG.pin = nw; saveConfig(); return J({ ok: true });
  }
  if (u.pathname === '/api/screen') { return J(CFG.screen); }
  if (u.pathname === '/api/screen/set') {
    const oa = u.searchParams.get('off_after'), px = u.searchParams.get('proximity');
    if (oa != null) CFG.screen.off_after = parseInt(oa) || 0;
    if (px != null) CFG.screen.proximity = px === '1';
    saveConfig(); return J({ ok: true, screen: CFG.screen });
  }
  if (u.pathname === '/api/calls') { return J(CALLS.slice(-30).reverse()); }
  if (u.pathname === '/api/mobiles') {
    ast('pjsip show contacts').then((c) => {
      J((CFG.mobiles || []).map((x) => ({ ext: x.ext, name: x.name, registered: new RegExp('\\b' + x.ext + '/').test(c), push: !!x.fcm })));
    });
    return;
  }
  if (u.pathname === '/api/mobiles/pair') {
    const name = (u.searchParams.get('name') || 'Móvil').slice(0, 24).trim() || 'Móvil';
    const ext = nextMobileExt();
    const secret = genSecret();
    const token = genSecret() + genSecret(); // token para abrir puerta / ver cámara en remoto
    CFG.mobiles = CFG.mobiles || [];
    CFG.mobiles.push({ name, ext, secret, token, created: Date.now() });
    saveConfig();
    writeMobilesConfig();
    const r = CFG.remote || {};
    return J({ ok: true, name, ext, secret, token, host: lanIP(), port: 5060, tlsPort: 5061, httpsPort: 8443, domain: r.domain || '', certFingerprint: CERT_FP });
  }
  if (u.pathname === '/api/mobiles/remove') {
    const ext = u.searchParams.get('ext') || '';
    CFG.mobiles = (CFG.mobiles || []).filter((x) => x.ext !== ext);
    saveConfig();
    writeMobilesConfig();
    return J({ ok: true });
  }
  if (u.pathname === '/api/mobiles/fcm') {
    // La app registra su token FCM; se autentica con su token de móvil
    const tok = u.searchParams.get('token') || (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
    const m = (CFG.mobiles || []).find((x) => x.token && x.token === tok);
    if (!m) return J({ ok: false, err: 'auth' });
    m.fcm = (u.searchParams.get('fcm') || '').slice(0, 400);
    saveConfig();
    return J({ ok: true });
  }

  // Estaticos
  let rel = u.pathname === '/' ? '/index.html' : u.pathname;
  rel = path.normalize(rel).replace(/^(\.\.(\/|\\|$))+/, '');
  const fp = path.join(ROOT, rel);
  if (!fp.startsWith(ROOT)) { res.writeHead(403); return res.end('forbidden'); }
  fs.readFile(fp, (err, data) => {
    if (err) { res.writeHead(404); return res.end('404'); }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
    });
    res.end(data);
  });
}

// Envoltorio: si un endpoint lanza (síncrono), respondemos 500 y seguimos vivos.
function safeHandle(req, res, remote) {
  try { handler(req, res, remote); }
  catch (e) {
    console.error('handler error:', e && e.stack || e);
    try { if (!res.headersSent) { res.writeHead(500, { 'Content-Type': 'application/json' }); } res.end('{"error":"internal"}'); } catch (_) {}
  }
}
const server = http.createServer((req, res) => safeHandle(req, res, false));
let httpsServer = null;
try {
  const tlsOpt = { cert: fs.readFileSync('/var/lib/qvadis/certs/qvadis.crt'), key: fs.readFileSync('/var/lib/qvadis/certs/qvadis.key') };
  httpsServer = https.createServer(tlsOpt, (req, res) => safeHandle(req, res, true));
} catch (e) { console.error('HTTPS deshabilitado (falta cert en /var/lib/qvadis/certs):', e.message); }

server.listen(PORT, '127.0.0.1', () => {
  // HTTP solo en localhost (lo usa el kiosco); el acceso remoto va por HTTPS :8443 con token
  console.log('qvadis manager escuchando en 127.0.0.1:' + PORT);
  if (httpsServer) httpsServer.listen(8443, '0.0.0.0', () => console.log('qvadis manager HTTPS (remoto) en :8443'));
  // Frecuencia PWM alta en el backlight: a baja frecuencia aparecen lineas
  // verticales (bate con el refresco del LCD). 8 kHz las elimina.
  execFile('pigs', ['pfs', String(BL_GPIO), '8000'], () => {
    setBrightness(curBrightness); // backlight al brillo guardado
  });
  // Volumen guardado del altavoz y micro
  execFile('amixer', ['-c', HOME_CARD, 'sset', 'PCM', (CFG.volume || 80) + '%', 'unmute'], () => {});
  execFile('amixer', ['-c', HOME_CARD, 'sset', 'Mic', (CFG.micvolume || 85) + '%', 'cap'], () => {});
  // Estado seguro de las puertas al arrancar: cerradas (0)
  pigs(['w', DOORS.front, 0]);
  pigs(['w', DOORS.aux, 0]);
  // Conexion al control de baresip (eventos de llamada)
  bsConnect();
  // Retención de capturas/llamadas (30 días): al arrancar y cada 6 h
  pruneRetention();
  setInterval(pruneRetention, 6 * 3600 * 1000);
  // Reconstruir la config de moviles emparejados (por si Asterisk arrancó antes)
  writeMobilesConfig();
  // Dispositivos AEC (home/street) persistentes: los usan el intercom local y baresip (audio movil)
  execFile('sh', ['/var/lib/qvadis/audio-aec-up.sh'], { env: audioEnv() }, (e) => { if (e) console.error('aec-up', e.message); });
  // DDNS / IP pública: al arrancar y cada 5 min
  updateDdns();
  setInterval(updateDdns, 5 * 60 * 1000);
  // Auto-check OTA en segundo plano (no bloquea el arranque): a los 40s y luego cada 12h
  const otaCheck = () => { if (CFG.ota && CFG.ota.enabled === false) return; execFile('sudo', ['-n', '/usr/local/sbin/qvadis-ota', 'check'], { timeout: 25000 }, (e, o) => {
    const t = String(o || ''); const m = /local=(\S+)\s+remota=(\S+)/.exec(t);
    OTA_LAST = { ts: Date.now(), local: m ? m[1] : '?', remote: m ? m[2] : '?', available: /update=si/.test(t), configured: !/remota=\?/.test(t) };
  }); };
  setTimeout(otaCheck, 40000);
  setInterval(otaCheck, 12 * 3600 * 1000);
});
