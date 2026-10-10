"""Read-only progress for the authorized two-product sandbox run; excludes credentials and generated payloads."""
import json, subprocess
from pathlib import Path
DOCKER='/Applications/Docker.app/Contents/Resources/bin/docker'
CAPTURES="'8e522bbd-9697-485f-8a36-d8bc409f9a16','f6ce2c8e-38f2-41ab-909b-709399ba6fed'"
def query(sql):
    text=subprocess.check_output([DOCKER,'exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',sql],text=True)
    return [json.loads(line) for line in text.splitlines() if line.strip()]
workflows=query(f"SELECT JSON_OBJECT('id',id,'captureId',capture_id,'status',status,'step',current_step,'publicationAttempted',publication_attempted,'error',error_message,'updatedAt',updated_at) FROM product_publish_workflows WHERE capture_id IN ({CAPTURES}) ORDER BY created_at DESC LIMIT 8")
images=query(f"SELECT JSON_OBJECT('id',id,'captureId',capture_id,'imageId',image_id,'status',status,'sourceKey',source_key,'candidateKey',candidate_key,'outputKey',output_key,'detection',detection_json,'validation',validation_json,'message',message,'updatedAt',updated_at) FROM product_watermark_checks WHERE capture_id IN ({CAPTURES}) ORDER BY created_at")
latest={}
for workflow in workflows:latest.setdefault(workflow['captureId'],workflow)
workflows=list(latest.values())
result={'workflows':workflows,'images':images}
root=Path('output/shopee-upload-20260908');root.mkdir(parents=True,exist_ok=True)
(root/'progress.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({'workflows':workflows,'images':[{k:v for k,v in i.items() if k not in ['sourceKey','candidateKey','outputKey','detection','validation']} for i in images]},ensure_ascii=False))
