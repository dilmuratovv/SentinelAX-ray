#!/usr/bin/env python3
"""Live test (localhost only) of chunk packets that Paper shares between players. World#refreshChunk (WorldEdit and
many other plugins call it) builds ONE chunk packet and sends that same object to every player watching the chunk.
Two viewer bots stand side by side; after a refresh each one must still get the full protection AND the reveals:

  storage (SentinelAE):  a chest behind a wall stays air for both and comes back for both when the wall goes;
                         a chest in plain sight is shown to both again after the refresh.
  ore (SentinelAX-ray):  cave ore in sight is shown to both again after the refresh; ore sealed in stone stays
                         hidden from both and is revealed to both when it is dug open.

  NODE_PATH=<lab>/node-ae/node_modules python3 tools/refresh_probe.py --mode storage|ore --paper paper.jar --java java \
      --jar plugin.jar --probe SentinelRefreshProbe.jar [--via other.jar ...] --mojang-cache dir --work dir --accept-eula

The probe plugin is tools/refresh-probe (javac against a Paper API, plus its plugin.yml).
"""
import argparse, json, queue, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'viewer.cjs'
VIEWERS = ('Alice', 'Bruno')


class Proc:
    def __init__(self, args, cwd=None):
        self.p = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.q, self.log = queue.Queue(), []
        threading.Thread(target=lambda: [self.q.put(l) for l in self.p.stdout], daemon=True).start()

    def send(self, t):
        self.p.stdin.write(t + '\n'); self.p.stdin.flush()

    def pump(self, until=None, seconds=0.0):
        end = time.monotonic() + seconds
        while True:
            try:
                l = self.q.get(timeout=0.1)
                self.log.append(l)
                if until and until in l:
                    return l
            except queue.Empty:
                pass
            if time.monotonic() >= end:
                if until:
                    raise RuntimeError('no "' + until + '"')
                return None


def main():
    p = argparse.ArgumentParser()
    for k in ('--paper', '--jar', '--probe', '--work'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--mode', choices=('storage', 'ore'), required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--client-version', default='1.21.11')
    p.add_argument('--refreshes', type=int, default=3, help='refresh rounds (the race between the two players varies)')
    p.add_argument('--netty-threads', type=int, default=1,
                   help='Netty event loops: 1 puts both players on one thread, as on a busy server (more players than loops)')
    p.add_argument('--debug', action='store_true', help="switch the plugins' debug logging on (per-player reveals)")
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    d = a.work / 'server'
    if d.exists():
        shutil.rmtree(d)
    (d / 'plugins').mkdir(parents=True)
    for jar in [a.jar, a.probe] + a.via:
        shutil.copy2(jar, d / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, d / 'cache')
    (d / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    (d / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=5\nview-distance=4\nsimulation-distance=4\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nspawn-animals=false\n')
    java = [a.java, '-Xms256M', '-Xmx900M', '-jar', str(a.paper.resolve()), '--nogui']
    # first start writes the plugins' settings (AX-ray turns Paper's anti-xray on); the test runs after a restart
    first = Proc(java, cwd=d)
    first.pump('Done (', 300)
    first.send('stop'); first.p.wait(120)
    spigot = d / 'spigot.yml'  # the server's Netty thread count (settings.netty-threads)
    spigot.write_text(spigot.read_text().replace('netty-threads: 4', f'netty-threads: {a.netty_threads}'))
    if a.debug:
        for cfg in (d / 'plugins').glob('*/config.yml'):
            cfg.write_text(cfg.read_text().replace('debug: false', 'debug: true'))
    srv = Proc(java, cwd=d)
    bots, results = {}, []

    def check(name, ok, detail=''):
        results.append(('PASS ' if ok else 'FAIL ') + name + ('' if ok else ': ' + detail))
        print(results[-1], flush=True)

    def cmd(*cs, wait=1.0):
        for c in cs:
            srv.send(c)
        srv.pump(seconds=wait)

    def query(bot, x, y, z):
        b = bots[bot]
        b.send(f'query {x} {y} {z}')
        return json.loads(b.pump('"ev":"query"', 10)).get('block')

    def both(x, y, z):
        return {n: query(n, x, y, z) for n in VIEWERS}

    def refresh(cx, cz):
        mark = len(srv.log)
        cmd(f'refreshchunk {cx} {cz}', wait=3)
        line = next((l for l in srv.log[mark:] if 'refreshed ' in l), '')
        return 'watchers=2' in line, line.strip()

    try:
        srv.pump('Done (', 300)
        threads = next((l.strip() for l in srv.log if 'threads for Netty' in l), '')
        check(f'server runs {a.netty_threads} Netty thread(s)', f'Using {a.netty_threads} thread' in threads, threads)
        cmd('forceload add -32 -32 32 32', 'setworldspawn 4 -60 -12', wait=2)  # join out of sight of the test blocks
        if a.mode == 'storage':
            # wall at z=6 (x -8..14), chest behind it, chest in sight; everything in chunk 0,0
            cmd('fill -8 -60 6 14 -54 6 stone', 'setblock 5 -60 10 chest', 'setblock 9 -60 3 chest', wait=2)
            spots = ['tp Alice 4.5 -60 1.5 0 0', 'tp Bruno 6.5 -60 1.5 0 0']
        else:
            # a stone hill with a cave (sight from inside), cave ore on its wall, ore sealed in stone; chunk 0,0
            cmd('fill 0 -60 0 15 -50 15 stone', 'fill 0 -49 0 15 -40 15 stone', 'fill 3 -56 4 11 -52 12 air',
                'setblock 12 -55 8 diamond_ore', 'setblock 8 -48 8 diamond_ore', wait=2)
            spots = ['tp Alice 5.5 -56 8.5 -90 0', 'tp Bruno 5.5 -56 6.5 -90 0']
        for n in VIEWERS:
            bots[n] = Proc(['node', str(BOT), str(port), n, a.client_version])
            srv.pump(n + ' joined the game', 60)
        cmd(*[f'gamemode survival {n}' for n in VIEWERS], *spots, wait=6)

        if a.mode == 'storage':
            hidden, seen = (5, -60, 10), (9, -60, 3)
            check('before refresh: chest behind the wall is air for both', set(both(*hidden).values()) == {'air'}, str(both(*hidden)))
            check('before refresh: chest in sight shown to both', set(both(*seen).values()) == {'chest'}, str(both(*seen)))
            for i in range(a.refreshes):
                ok, line = refresh(0, 0)
                check(f'refresh {i + 1}: one packet went to both players', ok, line)
                check(f'refresh {i + 1}: chest behind the wall stays air for both', set(both(*hidden).values()) == {'air'}, str(both(*hidden)))
                check(f'refresh {i + 1}: chest in sight shown to both again', set(both(*seen).values()) == {'chest'}, str(both(*seen)))
            cmd('fill -8 -60 6 14 -54 6 air', wait=3)
            check('wall removed after the refreshes: chest revealed to both', set(both(*hidden).values()) == {'chest'}, str(both(*hidden)))
        else:
            cave, sealed = (12, -55, 8), (8, -48, 8)
            check('before refresh: cave ore in sight shown to both', set(both(*cave).values()) == {'diamond_ore'}, str(both(*cave)))
            # sealed ore arrives as a stand-in: stone from this plugin, or a random block from Paper's anti-xray
            check('before refresh: sealed ore is hidden from both', 'diamond_ore' not in both(*sealed).values(), str(both(*sealed)))
            for i in range(a.refreshes):
                ok, line = refresh(0, 0)
                check(f'refresh {i + 1}: one packet went to both players', ok, line)
                check(f'refresh {i + 1}: cave ore in sight shown to both again', set(both(*cave).values()) == {'diamond_ore'}, str(both(*cave)))
                check(f'refresh {i + 1}: sealed ore stays hidden from both', 'diamond_ore' not in both(*sealed).values(), str(both(*sealed)))
            cmd('fill 6 -51 6 10 -49 10 air', wait=4)  # open the roof below the sealed ore: in sight of both
            check('sealed ore dug open after the refreshes: revealed to both', set(both(*sealed).values()) == {'diamond_ore'}, str(both(*sealed)))

        bad = [l for b in bots.values() for l in b.log if '"ev":"chunk-error"' in l or '"ev":"error"' in l or '"ev":"end"' in l]
        check('every chunk decoded and both clients stayed connected', not bad, ''.join(bad[:2]))
        errs = [l for l in srv.log if 'Exception' in l or 'cannot be copied' in l]
        check('no exceptions or copy fallbacks in the server log', not errs, ''.join(errs[:3]))
    finally:
        for b in bots.values():
            b.p.kill()
        try:
            srv.send('stop'); srv.p.wait(60)
        except Exception:
            srv.p.kill()
        (a.work / 'server-output.log').write_text(''.join(srv.log))
    print('DONE', sum(r.startswith('PASS') for r in results), '/', len(results))
    return 0 if results and all(r.startswith('PASS') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
