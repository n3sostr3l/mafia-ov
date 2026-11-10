// End-to-end HTTP/WebSocket checks against a running LOCAL dev instance. Creates synthetic test data.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
const base = new URL(process.env.APP_BASE_URL || 'http://127.0.0.1:4200');
assert(['127.0.0.1', 'localhost'].includes(base.hostname), 'Smoke tests are restricted to localhost.');
let cookie = '', csrf = '';
async function request(path, method = 'GET', body, expected = 200, anonymous = false) {
 const headers = {};
 if (!anonymous && cookie) headers.Cookie = cookie;
 if (method !== 'GET') headers['X-CSRF-TOKEN'] = csrf;
 if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
 const response = await fetch(new URL('/api/v1' + path, base), { method, headers, body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body) });
 if (!anonymous) { const set = response.headers.getSetCookie(); if (set.length) cookie = set.map(c => c.split(';')[0]).join('; '); }
 const text = await response.text(); assert.equal(response.status, expected, `${method} ${path.split('?')[0]}: ${text}`);
 return text ? JSON.parse(text) : null;
}
csrf = (await request('/auth/csrf')).token;
const name = 'smoke_' + randomUUID().slice(0, 8), password = randomUUID() + 'A9';
const registered = await request('/auth/register', 'POST', { username: name, email: name + '@example.test', password });
assert(registered.verificationUrl, 'Use the dev profile; this check never reads real mail.');
await request('/auth/verify', 'POST', { token: new URL(registered.verificationUrl).searchParams.get('verify') });
await request('/auth/login', 'POST', { login: name, password });
csrf = (await request('/auth/csrf')).token;
const tournament = await request('/tournaments', 'POST', { name: 'Проверка приложения ' + name });
let game = await request(`/tournaments/${tournament.id}/games`, 'POST', { tableNumber: 1, gameNumber: 1, demo: true });
const id = game.id;
const { token } = await request(`/games/${id}/overlay-link`);
let publicState = await request(`/public/games/${id}?token=${token}`, 'GET', undefined, 200, true);
assert(publicState.state.players.every(p => p.role === 'UNKNOWN'));
assert.equal(publicState.state.checks.length, 0);
await request(`/games/${id}`, 'GET', undefined, 401, true);
const frames = [];
const wsUrl = new URL(`/ws/games/${id}?token=${token}`, base); wsUrl.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:';
const socket = new WebSocket(wsUrl);
socket.addEventListener('message', event => { frames.push(JSON.parse(event.data)); });
async function waitFor(check, description) {
 const deadline = Date.now() + 10000;
 while (!check() && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
 assert(check(), description);
}
try {
 await waitFor(() => frames.length > 0, 'Public WebSocket must receive an initial snapshot.');
 const firstRevision = game.revision;
 game.state.players[0].name = 'Проверка эфира';
 game = await request(`/games/${id}`, 'PUT', { revision: game.revision, state: game.state, description: 'Проверка WebSocket' });
 await waitFor(() => frames.some(frame => frame.revision === game.revision), 'WebSocket must deliver committed changes.');
 assert.equal(frames.at(-1).state.players[0].name, 'Проверка эфира');
 assert(frames.at(-1).state.players.every(p => p.role === 'UNKNOWN'));
 await request(`/games/${id}`, 'PUT', { revision: firstRevision, state: game.state }, 409);
 const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7foAAAAASUVORK5CYII=', 'base64');
 const form = new FormData(); form.append('file', new Blob([png], { type: 'image/png' }), 'test.png');
 const image = await request('/uploads', 'POST', form);
 game.state.theme.rolePhotos.SHERIFF = image.id; game.state.theme.useRolePhotos = true;
 game = await request(`/games/${id}`, 'PUT', { revision: game.revision, state: game.state });
 publicState = await request(`/public/games/${id}?token=${token}`, 'GET', undefined, 200, true);
 assert.equal(publicState.state.players[3].photo, null, 'A hidden role must not select its role photo.');
 assert.deepEqual(publicState.state.theme.rolePhotos, {});
 await request(`/public/games/${id}/images/${image.id}?token=${token}`, 'GET', undefined, 404, true);
 game.state.showRoles = true;
 game = await request(`/games/${id}`, 'PUT', { revision: game.revision, state: game.state });
 publicState = await request(`/public/games/${id}?token=${token}`, 'GET', undefined, 200, true);
 assert.equal(publicState.state.players[3].photo, image.id);
 const imageResponse = await fetch(new URL(`/api/v1/public/games/${id}/images/${image.id}?token=${token}`, base));
 assert.equal(imageResponse.status, 200); assert.equal(imageResponse.headers.get('content-type'), 'image/png');
 const badForm = new FormData(); badForm.append('file', new Blob(['<script>test</script>'], { type: 'image/png' }), 'bad.png');
 await request('/uploads', 'POST', badForm, 400);
 let closed = false; socket.addEventListener('close', () => { closed = true; });
 await request(`/games/${id}/overlay-link`, 'POST');
 await waitFor(() => closed, 'Rotation must close existing public sockets.');
 await request(`/public/games/${id}?token=${token}`, 'GET', undefined, 404, true);
 await request('/auth/logout', 'POST');
 await request(`/games/${id}`, 'GET', undefined, 401);
 console.log('PASS: registration, email verification, session, CSRF, CRUD, public redaction, live WebSocket, optimistic revisions, image validation, photo privacy, token rotation, logout.');
} finally { socket.close(); }
