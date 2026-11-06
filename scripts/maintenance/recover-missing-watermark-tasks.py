"""Resume a reviewed manifest of failed image-stage workflows, keeping successful images.

Usage on server: python3 recover-missing-watermark-tasks.py RELEASE_DIRECTORY
The directory must contain recovery-targets.json with exact workflow/job/capture IDs.
"""
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
targets = json.loads((root / "recovery-targets.json").read_text())
assert len(targets) == 7
assert not (root / "recovery-applied.json").exists()
for row in targets:
    for field in ("workflow", "job", "capture"):
        assert re.fullmatch(r"[a-f0-9-]{36}", row[field])
assert sum(row["failedImages"] for row in targets) == 32


def sql(query):
    return subprocess.check_output(["docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -NB --raw "$MYSQL_DATABASE"'],
        input=query.encode()).decode().strip()


def ids(field):
    return ",".join("'" + row[field] + "'" for row in targets)


assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='123' AND success=1") == "1"
backup = {}
for table, columns, where in [
    ("product_publish_workflows", ["id","status","current_step","expected_capture_version","retry_count","next_attempt_at","locked_by","locked_until","error_message","completed_at","updated_at"], "id IN ("+ids("workflow")+")"),
    ("product_captures", ["id","status","version","updated_at"], "id IN ("+ids("capture")+")"),
    ("product_image_processing_jobs", ["id","status","processed_images","error_message","completed_at","updated_at"], "id IN ("+ids("job")+")"),
    ("product_watermark_queue", ["id","image_id","job_id","status","retry_count","next_attempt_at","worker_token","lease_until","error_message","updated_at"], "job_id IN ("+ids("job")+")"),
    ("product_capture_images", ["id","capture_id","status","error_message","processed_url","updated_at"], "capture_id IN ("+ids("capture")+")"),
]:
    fields = ",".join("'"+column+"',"+column for column in columns)
    backup[table] = [json.loads(line) for line in sql("SELECT JSON_OBJECT("+fields+") FROM "+table+" WHERE "+where).splitlines()]
path = root / "recovery-before-private.json"
assert not path.exists(), "Recovery snapshot already exists; inspect before retrying"
with path.open("x") as file:
    json.dump(backup, file, ensure_ascii=False)
path.chmod(0o600)

# CHECK failures abort the batch and disconnect rolls back the uncommitted transaction.
# Temporary-table creation happens before the transaction; there is no application DDL.
statements = [
    "CREATE TEMPORARY TABLE recovery_assert(ok INT NOT NULL CHECK(ok=1))",
    "START TRANSACTION",
    """INSERT INTO recovery_assert SELECT COUNT(*)=7 FROM product_publish_workflows w
       JOIN product_captures c ON c.id=w.capture_id AND c.organization_id=w.organization_id
       JOIN product_image_processing_jobs j ON j.id=w.image_job_id AND j.organization_id=w.organization_id
       WHERE w.id IN ("""+ids("workflow")+""") AND w.status='FAILED' AND w.current_step='IMAGES'
       AND w.publication_attempted=0 AND w.publish_audit_id IS NULL AND w.error_message LIKE '%HTTP 404%'
       AND (w.locked_until IS NULL OR w.locked_until<NOW(6))
       AND c.status='FAILED' AND c.deleted_at IS NULL AND c.published_at IS NULL
       AND c.version=w.expected_capture_version AND j.status='FAILED' AND j.provider='AI_WATERMARK_V1'
       AND NOT EXISTS(SELECT 1 FROM product_watermark_queue q WHERE q.job_id=j.id AND q.status IN ('RUNNING','QUEUED'))
       FOR UPDATE""",
    "UPDATE product_captures SET status='IMAGE_PROCESSING',version=version+1,updated_at=NOW(6) WHERE id IN ("+ids("capture")+") AND status='FAILED'",
    "INSERT INTO recovery_assert VALUES(ROW_COUNT()=7)",
    "UPDATE product_image_processing_jobs SET status='RUNNING',error_message=NULL,completed_at=NULL,updated_at=NOW(6) WHERE id IN ("+ids("job")+") AND status='FAILED'",
    "INSERT INTO recovery_assert VALUES(ROW_COUNT()=7)",
    """UPDATE product_watermark_queue SET status='QUEUED',retry_count=0,worker_token=NULL,lease_until=NULL,
       error_message=NULL,next_attempt_at=TIMESTAMPADD(SECOND,MOD(CRC32(id),30),NOW(6)),updated_at=NOW(6)
       WHERE job_id IN ("""+ids("job")+") AND status='FAILED' AND error_message LIKE '%HTTP 404%'",
    "INSERT INTO recovery_assert VALUES(ROW_COUNT()=32)",
    """UPDATE product_capture_images i JOIN product_watermark_queue q ON q.image_id=i.id AND q.organization_id=i.organization_id
       SET i.status='PENDING',i.error_message=NULL,i.updated_at=NOW(6)
       WHERE q.job_id IN ("""+ids("job")+") AND q.status='QUEUED' AND i.status='FAILED'",
    "INSERT INTO recovery_assert VALUES(ROW_COUNT()=32)",
    """UPDATE product_publish_workflows w JOIN product_captures c ON c.id=w.capture_id AND c.organization_id=w.organization_id
       SET w.status='PENDING',w.expected_capture_version=c.version,w.retry_count=0,w.next_attempt_at=TIMESTAMPADD(SECOND,45,NOW(6)),
       w.locked_by=NULL,w.locked_until=NULL,w.error_message=NULL,w.completed_at=NULL,w.updated_at=NOW(6)
       WHERE w.id IN ("""+ids("workflow")+") AND w.status='FAILED' AND w.publication_attempted=0",
    "INSERT INTO recovery_assert VALUES(ROW_COUNT()=7)",
    "COMMIT",
]
sql(";\n".join(statements)+";")
summary = {"workflowsResumed": 7, "failedImagesResumed": 32, "successfulImagesRetained": 128}
(root / "recovery-applied.json").write_text(json.dumps(summary))
print(json.dumps(summary))
