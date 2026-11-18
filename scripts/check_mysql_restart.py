"""Acceptance check: stop/start the isolated database without losing accounts.

Stop app processes before running this check. Only the database recorded under
this project's ignored runtime directory is used.
"""
import json
from pathlib import Path
import subprocess
import sys
import time
from dev_mysql import STATE, ROOT, sql

binary = Path(r"D:\develop\mysql8.0\bin")
original = STATE.read_bytes()
state = json.loads(original)
before = sql(binary, state, "SELECT COUNT(*) FROM tcm_stage_one.sys_users")
try:
    subprocess.run([sys.executable, str(ROOT / "scripts/dev_mysql.py"), "stop"], check=True)
    assert STATE.exists(), "Stop must preserve connection metadata so existing data can be restarted"
    subprocess.run([sys.executable, str(ROOT / "scripts/dev_mysql.py"), "start"], check=True)
    after = sql(binary, json.loads(STATE.read_text()), "SELECT COUNT(*) FROM tcm_stage_one.sys_users")
    assert before == after, "Restart must preserve the existing accounts"
    report = {"status": "PASSED", "users_preserved": int(after)}
    (ROOT / "runtime/mysql-restart-check.json").write_text(json.dumps(report), encoding="utf-8")
    print("PASS: isolated database restart preserved all accounts")
finally:
    # Recover metadata if an older helper deleted it; never remove database data.
    if not STATE.exists():
        STATE.write_bytes(original)
