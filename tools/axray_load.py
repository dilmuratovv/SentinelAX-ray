#!/usr/bin/env python3
"""SentinelAX-ray load test (localhost only): thousands of exposed ores behind a wall, several viewer bots walking
(teleported) around them, and the server's own /mspt. Prints tick times and /sae status.

  NODE_PATH=<lab>/node-ae/node_modules python3 tools/ae_load.py --paper paper.jar --java java --jar SentinelAE.jar \
      --mojang-cache dir --work dir [--no-ae] --accept-eula
"""
import argparse, re, shutil, socket, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from axray_probe import Proc

BOT = Path(__file__).resolve().parent / 'client' / 'viewer.cjs'


def main():
    p = argparse.ArgumentParser()
    for k in ('--paper', '--jar', '--work'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--viewers', type=int, default=3)
    p.add_argument('--no-ae', action='store_true')
    p.add_argument('--farm', action='store_true', help='entities instead of storage blocks: items, villagers, horses, wolves')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    d = a.work / 'server'
    if d.exists():
        shutil.rmtree(d)
    (d / 'plugins').mkdir(parents=True)
    if not a.no_ae:
        shutil.copy2(a.jar, d / 'plugins' / a.jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, d / 'cache')
    (d / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    (d / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=10\nview-distance=6\nsimulation-distance=4\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\nspawn-animals=false\n')
    srv = Proc([a.java, '-Xms512M', '-Xmx1G', '-jar', str(a.paper.resolve()), '--nogui'], cwd=d)
    bots, samples = [], []
    try:
        srv.pump('Done (', 300)
        if a.farm:
            # A busy base: an item collector, a trading hall, a horse pen and dogs, half behind a wall.
            cmds = ['forceload add -16 -16 80 80', 'fill -2 -60 -2 66 -54 -2 stone', 'fill 0 -61 0 63 -61 63 stone']
            for i in range(600):
                cmds.append(f'summon item {i % 30 * 2 + 1} -60 {i // 30 * 3 + 1} {{Item:{{id:"minecraft:cobblestone",count:1}},Age:-32768,PickupDelay:32767}}')
            for i in range(120):
                cmds.append(f'summon villager {i % 12 * 5 + 2} -60 {i // 12 * 6 + 2} {{NoAI:1b}}')
            for i in range(60):
                cmds.append(f'summon horse {i % 10 * 6 + 3} -60 {i // 10 * 9 + 4}')
            for i in range(60):
                cmds.append(f'summon wolf {i % 10 * 6 + 4} -60 {i // 10 * 9 + 6}')
            srv.send(cmds[0]); srv.pump(seconds=5)  # forceload first: the wall needs loaded chunks
            for c in cmds[1:]:
                srv.send(c)
            srv.pump(seconds=12)
        else:
            for c in ('forceload add -16 -16 80 80', 'fill -2 -60 -2 66 -54 -2 stone', 'fill 0 -60 0 63 -58 63 diamond_ore',
                      'fill 0 -59 0 63 -59 63 air'):
                srv.send(c)
                srv.pump(seconds=4)
        srv.send('forceload remove all')
        for i in range(a.viewers):
            bots.append(Proc(['node', str(BOT), str(port), f'V{i}', '1.21.11']))
            srv.pump(f'V{i} joined the game', 60)
        srv.pump(seconds=5)
        # mostly behind the wall (the farm must be hidden), sometimes in plain view
        positions = [(32, -12), (10, -14), (50, -16), (70, 30), (-12, 32)]
        for step in range(16):
            for i in range(a.viewers):
                x, z = positions[(step + i) % len(positions)]
                srv.send(f'tp V{i} {x}.5 -60 {z}.5 0 0')
            srv.pump(seconds=2.5)
            mark = len(srv.log)
            srv.send('mspt')
            srv.pump('5s', 10)
            srv.pump(seconds=0.5)
            txt = re.sub(r'\x1b\[[0-9;]*m', '', ''.join(srv.log[mark:]))
            nums = [float(x) for x in re.findall(r'(\d+\.\d+)', txt.split('1m:', 1)[-1])]
            if len(nums) >= 3:
                samples.append(nums[:3])
        if not a.no_ae:
            srv.send('sxray status'); srv.pump(seconds=1)
            st = [l for l in srv.log if 'line-of-sight layer' in l]
            print(st[-1].strip()[:500] if st else 'no status')
    finally:
        for b in bots:
            b.p.kill()
        try:
            srv.send('stop'); srv.p.wait(60)
        except Exception:
            srv.p.kill()
        (a.work / 'server-output.log').write_text(''.join(srv.log))
    if samples:
        print(f'{"without SentinelAX-ray" if a.no_ae else "with SentinelAX-ray"}: samples={len(samples)} avg_mspt={sum(s[0] for s in samples)/len(samples):.2f} '
              f'worst_tick_ms={max(s[2] for s in samples):.1f} per-sample max: ' + ' '.join(f'{s[2]:.0f}' for s in samples))
    return 0


if __name__ == '__main__':
    sys.exit(main())
