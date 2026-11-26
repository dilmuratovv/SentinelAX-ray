#!/usr/bin/env python3
"""Runs the test-only mining harness (tools/mining-harness) with five bot players and prints its results.

  NODE_PATH=<lab>/node-ae/node_modules python3 tools/mining_probe.py --paper paper.jar --java java --jar AX-ray.jar \
      --harness SentinelAXrayHarness.jar [--via ...] --mojang-cache dir --work dir --accept-eula
"""
import argparse, shutil, socket, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from axray_probe import Proc, BOT


def main():
    p = argparse.ArgumentParser()
    for k in ('--paper', '--jar', '--harness', '--work'):
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
    for jar in [a.jar, a.harness] + a.via:
        shutil.copy2(jar, d / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, d / 'cache')
    (d / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    (d / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=8\nview-distance=4\nsimulation-distance=4\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\nspawn-animals=false\n')
    srv = Proc([a.java, '-Xms512M', '-Xmx1G', '-jar', str(a.paper.resolve()), '--nogui'], cwd=d)
    bots = []
    try:
        srv.pump('Done (', 600)
        for n in ('Alice', 'Bob', 'Carol', 'Dave', 'Eve'):
            bots.append(Proc(['node', str(BOT), str(port), n, a.client_version]))
            srv.pump(f'{n} joined the game', 60)
        srv.pump('HARNESS DONE', 900)
        srv.pump(seconds=2)
    finally:
        for b in bots:
            b.p.kill()
        try:
            srv.send('stop'); srv.p.wait(60)
        except Exception:
            srv.p.kill()
        (a.work / 'server-output.log').write_text(''.join(srv.log))
    for l in srv.log:
        if 'RESULT ' in l or 'BUILT ' in l or ('choices' in l and ':' in l and 'stats' not in l):
            print(l.strip()[:400])
    return 0


if __name__ == '__main__':
    sys.exit(main())
