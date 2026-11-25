// Test-only viewer bot (localhost): records what the server sends it, as one JSON object per line on stdout.
// Keeps the chunks it receives (prismarine-chunk) and applies block updates, so "query x y z" on stdin prints the
// block the client would have there. usage: node viewer.cjs <port> <name> [version]
const mc = require('minecraft-protocol');
const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('localhost port required');
const name = process.argv[3] || 'Viewer';
const version = process.argv[4] || '1.21.11';
const registry = require('prismarine-registry')(version);
const Chunk = require('prismarine-chunk')(registry);
const { Vec3 } = require('vec3');
const toServer = registry.protocol?.play?.toServer?.types || require('minecraft-data')(version).protocol.play.toServer.types;
const usesFlags = toServer.packet_position[1].some(f => f.name === 'flags');
const ground = usesFlags ? {flags: {onGround: true, hasHorizontalCollision: false}} : {onGround: true};
const chunks = new Map();
let minY = -64, height = 384, position = null;
const out = o => process.stdout.write(JSON.stringify(o) + '\n');
const client = mc.createClient({host: '127.0.0.1', port, username: name, version, auth: 'offline'});
client.on('error', e => out({ev: 'error', msg: e.message}));
client.on('end', r => { out({ev: 'end', reason: String(r)}); process.exit(0); });
client.on('login', p => {
  const dim = p.worldState?.dimension ?? p.dimension;
  out({ev: 'login', entityId: p.entityId});
});
client.on('position', packet => {
  const prev = position || {x: 0, y: 0, z: 0, yaw: 0, pitch: 0};
  position = {};
  for (const a of ['x', 'y', 'z', 'yaw', 'pitch']) position[a] = (packet.flags?.[a] ? prev[a] : 0) + packet[a];
  if (packet.teleportId !== undefined) client.write('teleport_confirm', {teleportId: packet.teleportId});
  client.write('position_look', {...position, ...ground});
});
setInterval(() => {
  if (!position) return;
  client.write('position', {x: position.x, y: position.y, z: position.z, ...ground});
  if (toServer.packet_tick_end) client.write('tick_end', {});
}, 50);
const key = (x, z) => x + ',' + z;
client.on('map_chunk', p => {
  try {
    const c = new Chunk({minY, worldHeight: height});
    c.load(p.chunkData);
    chunks.set(key(p.x, p.z), c);
    out({ev: 'chunk', x: p.x, z: p.z, blockEntities: (p.blockEntities || []).length});
  } catch (e) { out({ev: 'chunk-error', x: p.x, z: p.z, msg: e.message}); }
});
client.on('unload_chunk', p => chunks.delete(key(p.chunkX, p.chunkZ)));
function setState(l, id) {
  const c = chunks.get(key(l.x >> 4, l.z >> 4));
  if (c) c.setBlockStateId(new Vec3(l.x & 15, l.y, l.z & 15), id);
}
client.on('block_change', p => {
  setState(p.location, p.type);
  out({ev: 'block', x: p.location.x, y: p.location.y, z: p.location.z, block: registry.blocksByStateId[p.type]?.name});
});
client.on('multi_block_change', p => {
  const sx = p.chunkCoordinates.x * 16, sy = p.chunkCoordinates.y * 16, sz = p.chunkCoordinates.z * 16;
  for (const r of p.records) {
    const state = r >> 12, rel = r & 0xFFF;
    const l = {x: sx + (rel >> 8 & 15), y: sy + (rel & 15), z: sz + (rel >> 4 & 15)};
    setState(l, state);
  }
});
const types = registry.entitiesArray;
client.on('spawn_entity', p => out({ev: 'spawn', id: p.entityId, type: registry.entities[p.type]?.name, x: p.x, y: p.y, z: p.z}));
client.on('named_entity_spawn', p => out({ev: 'spawn', id: p.entityId, type: 'player', x: p.x, y: p.y, z: p.z}));
client.on('entity_destroy', p => out({ev: 'destroy', ids: p.entityIds}));
client.on('sound_effect', p => out({ev: 'sound', x: p.x / 8, y: p.y / 8, z: p.z / 8}));
client.on('entity_sound_effect', p => out({ev: 'entity-sound', id: p.entityId}));
client.on('world_particles', p => out({ev: 'particle', x: p.x, y: p.y, z: p.z}));
client.on('block_break_animation', p => out({ev: 'break-anim', id: p.entityId, x: p.location.x, y: p.location.y, z: p.location.z}));
client.on('open_window', p => out({ev: 'open-window', type: p.inventoryType ?? p.windowType}));
client.on('block_action', p => out({ev: 'block-action', x: p.location.x, y: p.location.y, z: p.location.z}));
client.on('world_event', p => out({ev: 'world-event', type: p.effectId, x: p.location.x, y: p.location.y, z: p.location.z}));
let buf = '';
process.stdin.on('data', d => {
  buf += d;
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
    const [cmd, ...a] = line.split(/\s+/);
    if (cmd === 'query') {
      const [x, y, z] = a.map(Number);
      const c = chunks.get(key(x >> 4, z >> 4));
      out({ev: 'query', x, y, z, block: c ? c.getBlock(new Vec3(x & 15, y, z & 15)).name : null});
    } else if (cmd === 'use') {
      const [x, y, z] = a.map(Number);
      try {
        client.write('block_place', {hand: 0, location: {x, y, z}, direction: 2, cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
          insideBlock: false, worldBorderHit: false, sequence: 2});
        out({ev: 'used', x, y, z});
      } catch (e) { out({ev: 'use-error', msg: e.message}); }
    } else if (cmd === 'dig') {
      const [x, y, z] = a.map(Number);
      client.write('block_dig', {status: 0, location: {x, y, z}, face: 1, sequence: 1});
    }
  }
});
