#!/usr/bin/env python3
"""Live test of SentinelAX-ray's line-of-sight ore layer (localhost only): a disposable Paper server and a viewer bot
that decodes every chunk (prismarine-chunk), so the probe knows which block the client really has at each spot.

  NODE_PATH=<lab>/node-ae/node_modules python3 tools/axray_probe.py --paper paper.jar --java java --jar AX-ray.jar \
      [--via ViaVersion.jar ...] --mojang-cache dir --work dir --accept-eula
"""
import argparse, json, queue, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'viewer.cjs'


class Proc:
    def __init__(self, args, cwd=None):
        self.p = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, bufsize=1)
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
    for k in ('--paper', '--jar', '--work'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--client-version', default='1.21.11')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    d = a.work / 'server'
    if d.exists():
        shutil.rmtree(d)
    (d / 'plugins').mkdir(parents=True)
    for jar in [a.jar] + a.via:
        shutil.copy2(jar, d / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, d / 'cache')
    (d / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    (d / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=5\nview-distance=6\nsimulation-distance=4\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\nspawn-animals=false\n')
    srv = Proc([a.java, '-Xms256M', '-Xmx900M', '-jar', str(a.paper.resolve()), '--nogui'], cwd=d)
    viewer, results = None, []

    def check(name, ok, detail=''):
        results.append(('PASS ' if ok else 'FAIL ') + name + ('' if ok else ': ' + detail))
        print(results[-1], flush=True)

    def cmd(*cs, wait=1.0):
        for c in cs:
            srv.send(c)
        srv.pump(seconds=wait)

    def query(x, y, z):
        viewer.send(f'query {x} {y} {z}')
        line = viewer.pump('"ev":"query"', 10)
        return json.loads(line).get('block')

    try:
        srv.pump('Done (', 300)
        # A solid stone hill (two fills: the command limit), a cave inside with ore on its walls, ore sealed in stone,
        # deepslate ore, and an emerald up in the sky on a pillar.
        cmd('forceload add -32 -32 48 48', wait=3)
        cmd('fill -12 -60 4 12 -50 16 stone', 'fill -12 -49 4 12 -40 16 stone',
            'fill -12 -60 13 12 -58 16 deepslate',  # deepslate in the ore's section, as in real deepslate layers
            'fill -3 -56 8 3 -52 12 air',
            'setblock -3 -56 10 diamond_ore', 'setblock 3 -55 9 diamond_ore', 'setblock 0 -56 12 deepslate_diamond_ore',
            'setblock 0 -48 10 diamond_ore', 'setblock 8 -55 14 gold_ore',
            'fill 30 -60 30 30 99 30 stone', 'setblock 30 100 30 emerald_ore', wait=3)
        viewer = Proc(['node', str(BOT), str(port), 'Viewer', a.client_version])
        srv.pump('Viewer joined the game', 60)
        cmd('gamemode survival Viewer', 'tp Viewer 0.5 -60 -10.5 0 0', wait=6)
        cave = [(-3, -56, 10), (3, -55, 9)]
        check('cave ore seen from outside the hill arrives as stone', all(query(*c) == 'stone' for c in cave), str([query(*c) for c in cave]))
        check('deepslate ore arrives as deepslate (its section has deepslate)', query(0, -56, 12) == 'deepslate', str(query(0, -56, 12)))
        check('ore sealed in stone arrives as stone', query(0, -48, 10) == 'stone', str(query(0, -48, 10)))
        check('emerald far away in the open arrives as stone', query(30, 100, 30) == 'stone', str(query(30, 100, 30)))

        cmd('tp Viewer 0.5 -56 10.5 0 0', wait=3)  # inside the cave
        got = [query(*c) for c in cave]
        check('inside the cave the ore on its walls is revealed', got == ['diamond_ore', 'diamond_ore'], str(got))
        check('deepslate ore in the cave revealed', query(0, -56, 12) == 'deepslate_diamond_ore', str(query(0, -56, 12)))
        check('ore sealed in stone stays hidden', query(0, -48, 10) == 'stone', str(query(0, -48, 10)))

        cmd('fill 0 -51 10 0 -49 10 air', wait=3)  # dig up towards the sealed ore: now it touches the cave
        check('digging next to sealed ore reveals it', query(0, -48, 10) == 'diamond_ore', str(query(0, -48, 10)))

        cmd('tp Viewer 60.5 -60 60.5', wait=14)     # far away and out of sight for longer than rehide-after-ticks
        cmd('tp Viewer 0.5 -60 -10.5 0 0', wait=4)
        got = [query(*c) for c in cave]
        check('revealed cave ore is hidden again after leaving', got == ['stone', 'stone'], str(got))

        cmd('tp Viewer 30.5 101 27.5 0 30', wait=4)  # next to the emerald pillar, in sight
        check('emerald above the engine height revealed in sight', query(30, 100, 30) == 'emerald_ore', str(query(30, 100, 30)))

        bad = [l for l in viewer.log if '"ev":"chunk-error"' in l or '"ev":"error"' in l or '"ev":"end"' in l]
        check('every chunk decoded and the client stayed connected', not bad, ''.join(bad[:2]))
        srv.send('sxray status'); srv.pump(seconds=1)
        st = [l for l in srv.log if 'line-of-sight layer' in l]
        print(st[-1].strip()[:300] if st else 'no status')
    finally:
        if viewer:
            viewer.p.kill()
        try:
            srv.send('stop'); srv.p.wait(60)
        except Exception:
            srv.p.kill()
        (a.work / 'server-output.log').write_text(''.join(srv.log))
        if viewer:
            (a.work / 'viewer.log').write_text(''.join(viewer.log))
    print('DONE', sum(r.startswith('PASS') for r in results), '/', len(results))
    return 0 if results and all(r.startswith('PASS') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
