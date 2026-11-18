"""Isolated local MySQL for acceptance only. Never connects to an existing instance."""
import argparse
import csv
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / "runtime" / "dev-mysql.json"
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def alive(pid):
    result = subprocess.run(["tasklist", "/FI", f"PID eq {int(pid)}", "/FO", "CSV", "/NH"],
                            capture_output=True, creationflags=FLAGS, timeout=10)
    return any(len(row) > 1 and row[1] == str(pid) for row in csv.reader(result.stdout.decode(errors="replace").splitlines()))


def launch(binary, state):
    scratch = Path(state["scratch"]).resolve()
    if (ROOT / "runtime").resolve() not in scratch.parents:
        raise ValueError("Recorded MySQL data must stay within this project's runtime directory")
    with (scratch / "server.log").open("ab") as log:
        process = subprocess.Popen([str(binary / "mysqld.exe"), "--no-defaults",
            "--basedir=" + str(binary.parent), "--datadir=" + str(scratch / "data"),
            "--bind-address=127.0.0.1", "--port=" + str(state["port"]), "--shared-memory",
            "--shared-memory-base-name=" + state["memory"], "--mysqlx=OFF", "--skip-log-bin",
            "--innodb-buffer-pool-size=128M", "--console"], stdout=log, stderr=log, creationflags=FLAGS)
    deadline = time.time() + 60
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError((scratch / "server.log").read_text(errors="replace")[-1500:])
        try:
            sql(binary, state, "SELECT 1")
            state["pid"] = process.pid
            STATE.write_text(json.dumps(state), encoding="utf-8")
            return
        except (RuntimeError, subprocess.TimeoutExpired):
            time.sleep(.5)
    process.terminate()
    raise TimeoutError("Isolated MySQL startup timed out")


def sql(binary, state, statement):
    command = [str(binary / "mysql.exe"), "--no-defaults", "--protocol=MEMORY", "--host=localhost",
               "--shared-memory-base-name=" + state["memory"], "--user=root", "--password=",
               "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]
    result = subprocess.run(command, input=statement.encode(), capture_output=True, creationflags=FLAGS, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout.decode().strip()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=["start", "stop"])
    p.add_argument("--mysql-bin", default=r"D:\develop\mysql8.0\bin")
    a = p.parse_args()
    binary = Path(a.mysql_bin).resolve()
    if a.action == "stop":
        if STATE.exists():
            state = json.loads(STATE.read_text())
            try:
                sql(binary, state, "SHUTDOWN")
            except RuntimeError:
                if alive(state["pid"]):
                    raise
            deadline = time.time() + 20
            while alive(state["pid"]):
                if time.time() > deadline:
                    raise TimeoutError("Isolated MySQL is still shutting down")
                time.sleep(.2)
            print("Isolated MySQL stopped.")
        return
    if not STATE.exists():
        scratch = ROOT / "runtime" / ("dev-mysql-" + uuid.uuid4().hex[:10])
        scratch.mkdir(parents=True)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        state = {"port": port, "memory": "TCM_DEV_" + uuid.uuid4().hex,
                 "password": secrets.token_hex(24), "scratch": str(scratch)}
        subprocess.run([str(binary / "mysqld.exe"), "--no-defaults", "--initialize-insecure",
                        "--basedir=" + str(binary.parent), "--datadir=" + str(scratch / "data")],
                       check=True, capture_output=True, creationflags=FLAGS, timeout=120)
        launch(binary, state)
        password = state["password"]
        sql(binary, state, "CREATE DATABASE tcm_stage_one CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
            + f"CREATE USER 'tcm_stage'@'127.0.0.1' IDENTIFIED BY '{password}';"
            + f"CREATE USER 'tcm_stage'@'localhost' IDENTIFIED BY '{password}';"
            + "GRANT ALL ON tcm_stage_one.* TO 'tcm_stage'@'127.0.0.1';"
            + "GRANT ALL ON tcm_stage_one.* TO 'tcm_stage'@'localhost';")
    state = json.loads(STATE.read_text())
    try:
        sql(binary, state, "SELECT 1")
    except RuntimeError:
        if alive(state["pid"]):
            raise RuntimeError("Recorded database process is still running but is not responding")
        with socket.socket() as s:
            s.bind(("127.0.0.1", state["port"]))
        launch(binary, state)
    print("Isolated MySQL ready on 127.0.0.1:" + str(state["port"]), flush=True)


if __name__ == "__main__":
    main()
