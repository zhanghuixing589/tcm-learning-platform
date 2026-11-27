"""Project-owned MinIO lifecycle. Credentials remain only in ignored runtime files."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / 'runtime' / 'minio.env'
STATE_FILE = ROOT / 'runtime' / 'dev-minio.json'
COMPOSE = ROOT / 'infra' / 'compose.infra.yml'
VOLUME = 'tcm-learning-platform_minio-data'
ENDPOINT = 'http://127.0.0.1:19000'
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def docker(*arguments, check=True, capture=False):
    return subprocess.run(['docker', *arguments], cwd=ROOT, check=check,
                          capture_output=capture, text=True, creationflags=FLAGS,
                          timeout=180)


def compose(*arguments):
    return docker('compose', '-f', str(COMPOSE), '--env-file', str(ENV_FILE), *arguments)


def ensure_credentials():
    if ENV_FILE.exists():
        return
    existing = docker('volume', 'inspect', VOLUME, check=False, capture=True)
    if existing.returncode == 0:
        raise RuntimeError('Existing project volume found; restore runtime/minio.env instead of regenerating its credentials.')
    message = existing.stderr.lower()
    if 'no such volume' not in message and f'volume {VOLUME} not found' not in message:
        raise RuntimeError('Cannot verify Docker volume ownership; check Docker before creating credentials.')
    ENV_FILE.parent.mkdir(parents=True, exist_ok=True)
    content = '\n'.join([
        'MINIO_ROOT_USER=tcm_local_root',
        'MINIO_ROOT_PASSWORD=' + secrets.token_hex(24),
        'TCM_MINIO_APP_ACCESS_KEY=tcm_source_app',
        'TCM_MINIO_APP_SECRET_KEY=' + secrets.token_hex(24),
        '',
    ])
    descriptor = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
        output.write(content)


def start():
    ensure_credentials()
    compose('up', '-d')
    deadline = time.monotonic() + 60
    # Explicitly bypass desktop proxy settings for this project-local endpoint.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while True:
        try:
            with opener.open(ENDPOINT + '/minio/health/ready', timeout=3) as response:
                if response.status == 200:
                    break
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('Project MinIO readiness timed out; inspect only this Compose project.')
        time.sleep(0.5)
    compose('exec', '-T', 'minio', 'sh', '/bootstrap/bootstrap.sh')
    STATE_FILE.write_text(json.dumps({
        'project': 'tcm-learning-platform', 'endpoint': ENDPOINT,
        'console': 'http://127.0.0.1:19001', 'bucket': 'tcm-learning-sources',
        'volume': VOLUME, 'credentialsFile': str(ENV_FILE),
        'applicationAccessVerified': True, 'anonymousObjectStatus': 403,
    }, indent=2), encoding='utf-8')
    print('MinIO ready: ' + ENDPOINT + ' | private bucket: tcm-learning-sources')
    print('Credentials are retained in runtime/minio.env; no values are printed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'status', 'stop'], nargs='?', default='start')
    action = parser.parse_args().action
    try:
        if action == 'start':
            start()
        elif action == 'status':
            compose('ps')
        else:
            compose('stop')
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        print(str(failure))
        raise SystemExit(1)
