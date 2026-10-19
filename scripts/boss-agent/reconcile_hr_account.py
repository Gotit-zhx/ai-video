"""Dry-run by default; merge only an independently verified account pair."""
import argparse
import datetime
import json
import os
from pathlib import Path
import sqlite3

import hr_account_identity as identities


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True, type=Path)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--canonical', required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--expected-duplicates', required=True, type=int)
    args = parser.parse_args()
    os.umask(0o077)
    source = sqlite3.connect(args.db.resolve().as_uri()+'?mode=ro', uri=True, timeout=30)
    source.row_factory = sqlite3.Row
    if not args.apply:
        db = sqlite3.connect(':memory:')
        source.backup(db)
        backup = None
        db.execute('BEGIN IMMEDIATE')
    else:
        db = sqlite3.connect(args.db, timeout=30)
        db.execute('BEGIN IMMEDIATE')
        # The write reservation excludes concurrent writers while a separate
        # read connection backs up the exact pre-migration committed state.
        backup = args.db.with_name(args.db.name+'.before-account-merge-'+
                                  datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
        with sqlite3.connect(backup) as target:
            source.backup(target)
    db.row_factory = sqlite3.Row
    try:
        result = identities.merge_verified(db, args.owner, args.canonical, args.alias)
        if result['duplicatesMerged'] != args.expected_duplicates:
            raise ValueError('重复数量已变化，已回滚，需要重新核对')
        remaining = db.execute('''SELECT count(*) FROM (SELECT friend_id,message_id,count(*) n
            FROM hr_resume_pool WHERE owner=? AND account=? GROUP BY friend_id,message_id HAVING n>1)''',
                               (args.owner, args.canonical)).fetchone()[0]
        if remaining:
            raise ValueError('仍有重复附件，已回滚')
        if args.apply:
            db.commit()
        else:
            db.rollback()
        print(json.dumps({'applied': args.apply, 'backup': str(backup) if backup else None, **result}))
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close(); source.close()


if __name__ == '__main__':
    main()
