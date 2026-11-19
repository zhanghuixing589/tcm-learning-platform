"""Grant acceptance accounts roles only, refusing a non-isolated fixture database."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from dev_mysql import sql

contributor, reviewer, technician = (int(value) for value in sys.argv[1:4])
state = json.loads((ROOT / "runtime" / "dev-mysql.json").read_text(encoding="utf-8-sig"))
scratch = Path(state["scratch"]).resolve()
if (ROOT / "runtime").resolve() not in scratch.parents:
    raise ValueError("Acceptance grants require the isolated database under project runtime")
statements = ["USE tcm_stage_one", "START TRANSACTION"]
for user, role in [(contributor, "CONTRIBUTOR"), (reviewer, "REVIEWER"), (technician, "ADMIN")]:
    statements.append(f"INSERT IGNORE INTO sys_user_roles(user_id,role_id) SELECT {user},id FROM sys_roles WHERE code='{role}'")
statements.append("COMMIT")
sql(Path(r"D:\develop\mysql8.0\bin"), state, ";\n".join(statements) + ";")
print("Granted acceptance roles only in the isolated database; no business content was seeded.")
