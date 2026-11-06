"""Local metadata-only repair. Input names must come from verified attachment messages.

Usage: python backfill-resume-names.py DATABASE OWNER [VERIFIED_METADATA_JSON]
Back up DATABASE before use. Does not contact BOSS, OSS or AI services.
"""
import json
from pathlib import Path
import sys

import passive_pool
import resume_storage
import talent_pool


def backfill(database, owner, records):
    talent_pool.DB = Path(database)
    repaired = 0
    with talent_pool.database() as db:
        passive_pool.schema(db)
        for item in records:
            row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND account=? AND friend_id=? AND message_id=?',
                             (owner, item['accountKey'], item['friendId'], item['messageId'])).fetchone()
            if not row or row['file_hash'] != item['fileHash']:
                raise ValueError('Verified attachment does not match archived bytes')
            detail = json.loads(row['body'])
            detail.update(originalFileName=resume_storage.original_filename(item['originalFileName']),
                          nameExtractionVersion=0)
            db.execute('UPDATE hr_resume_pool SET body=? WHERE id=? AND owner=?',
                       (passive_pool.dump(detail), row['id'], owner))
        for row in list(db.execute('SELECT * FROM hr_resume_pool WHERE owner=?', (owner,))):
            before = json.loads(row['body']).get('name', '')
            updated = passive_pool.resume_identity(db, row, 'resume-name-migration')
            repaired += json.loads(updated['body']).get('name', '') != before
        missing = sum(not json.loads(row[0]).get('name') for row in
                      db.execute('SELECT body FROM hr_resume_pool WHERE owner=?', (owner,)))
    return {'repaired': repaired, 'missing': missing}


if __name__ == '__main__':
    records = json.loads(Path(sys.argv[3]).read_text()) if len(sys.argv) > 3 else []
    print(json.dumps(backfill(sys.argv[1], sys.argv[2], records)))
