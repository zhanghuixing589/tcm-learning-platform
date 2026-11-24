"""Create test-only profile content in the recorded isolated acceptance database."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from dev_mysql import sql

owner, reader = (int(value) for value in sys.argv[1:3])
state = json.loads((ROOT / "runtime" / "dev-mysql.json").read_text(encoding="utf-8-sig"))
scratch = Path(state["scratch"]).resolve()
if (ROOT / "runtime").resolve() not in scratch.parents:
    raise ValueError("Fixture database must be isolated under this project's runtime directory")

def text(value):
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"

titles = ["如何核对一篇中医科普文章的来源", "阅读方剂资料时，先看哪些信息", "中药名称与别名：整理学习笔记的方法", "从四气五味开始，建立知识框架", "把零散知识连起来：我的专题阅读笔记"]
statements = ["USE tcm_stage_one", "START TRANSACTION"]
section_name = f"浏览器验收科普分区 {reader}"
statements += [
    "INSERT INTO community_sections(code,name,normalized_name,description,lifecycle)"
    f" VALUES('e2e-collection-{reader}',{text(section_name)},{text(section_name)},"
    f"{text('仅用于个人中心浏览器验收。')},'FORMAL')",
    "SET @section=LAST_INSERT_ID()",
    "SET @policy=(SELECT policy_id FROM community_text_policy_heads WHERE id=1)",
]
for index in range(24):
    title = titles[index] if index < len(titles) else f"知识来源核对笔记（第{index + 1}篇）"
    body = "这是一条仅用于浏览器验收的学习记录，用于检查内容列表的排版、筛选和分页。\n\n阅读资料时，可以先记录名称、出处与版本，再整理自己的理解。本条内容不作为医学建议或正式知识来源。"
    # Keep the same sorted UTF-8 payload as CommunitySourceHash for plain text with no references.
    canonical = json.dumps(dict(body=body, references=[], sectionId="@SECTION@", sourceFormat="PLAIN_TEXT", title=title),
                           ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    prefix, suffix = canonical.split("@SECTION@")
    source_hash = f"SHA2(CONCAT({text(prefix)},CAST(@section AS CHAR),{text(suffix)}),256)"
    statements += [
        f"INSERT INTO content_resources(resource_type,owner_id) VALUES('ARTICLE',{owner})",
        "SET @resource=LAST_INSERT_ID()",
        f"INSERT INTO content_revisions(resource_id,version_no,title,body,created_by,revision_state,approved_at) VALUES(@resource,1,{text(title)},{text(body)},{owner},'APPROVED',UTC_TIMESTAMP(3))",
        "SET @revision=LAST_INSERT_ID()",
        "INSERT INTO community_post_profiles(resource_id,section_id,public_edited_at) VALUES(@resource,@section,UTC_TIMESTAMP(3))",
        "INSERT INTO community_revision_profiles(revision_id,resource_id,section_id,source_format,format_policy_id,release_method,source_sha256,body_char_count)"
        f" VALUES(@revision,@resource,@section,'PLAIN_TEXT',@policy,'MANUAL_REVIEW',{source_hash},{len(body)})",
        "UPDATE content_resources SET visibility_status='PUBLISHED',published_revision_id=@revision,published_at=UTC_TIMESTAMP(3) WHERE id=@resource",
        f"INSERT INTO resource_bookmarks(user_id,resource_id,created_at) VALUES({reader},@resource,UTC_TIMESTAMP(3)-INTERVAL {index} MINUTE)",
    ]
statements += [
    f"INSERT INTO sys_user_roles(user_id,role_id) SELECT {reader},id FROM sys_roles WHERE code='CONTRIBUTOR'",
    f"INSERT INTO user_follows(follower_id,followed_id) VALUES({reader},{owner}),({owner},{reader})",
    f"UPDATE sys_users SET bio={text('关注本草、方剂与可信知识，在阅读中慢慢积累。')} WHERE id={reader}",
    "COMMIT",
]
sql(Path(r"D:\develop\mysql8.0\bin"), state, ";\n".join(statements) + ";")
print("Created 24 test-only collection entries in the isolated acceptance database.")
