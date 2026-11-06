"""Owner-scoped account aliases. Local profile directory names are not identities."""
import contextlib
import json
import re
import sqlite3

import talent_pool as pool


def key(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{64}', value):
        raise ValueError('招聘账号标识无效')
    return value


def schema(db):
    db.execute('''CREATE TABLE IF NOT EXISTS hr_account_aliases(
        owner TEXT NOT NULL,alias TEXT NOT NULL,canonical TEXT NOT NULL,
        PRIMARY KEY(owner,alias))''')
    db.execute('''CREATE TABLE IF NOT EXISTS hr_account_record_aliases(
        owner TEXT NOT NULL,kind TEXT NOT NULL,alias TEXT NOT NULL,canonical TEXT NOT NULL,
        PRIMARY KEY(owner,kind,alias))''')
    db.execute('''CREATE TABLE IF NOT EXISTS hr_account_merge_archive(
        owner TEXT NOT NULL,table_name TEXT NOT NULL,record_id TEXT NOT NULL,body TEXT NOT NULL,
        PRIMARY KEY(owner,table_name,record_id))''')


def resolve(owner, value, db=None):
    value = key(value)
    if db is None:
        if not pool.DB.exists():
            return value
        with contextlib.closing(sqlite3.connect(pool.DB.resolve().as_uri()+'?mode=ro', uri=True)) as connection:
            connection.row_factory = sqlite3.Row
            connection.execute('PRAGMA query_only=ON')
            return resolve(owner, value, connection)
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='hr_account_aliases'").fetchone():
        return value
    row = db.execute('SELECT canonical FROM hr_account_aliases WHERE owner=? AND alias=?',
                     (owner, value)).fetchone()
    return row['canonical'] if row else value


def record(db, owner, kind, value):
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='hr_account_record_aliases'").fetchone():
        return value
    row = db.execute('SELECT canonical FROM hr_account_record_aliases WHERE owner=? AND kind=? AND alias=?',
                     (owner, kind, value)).fetchone()
    return row['canonical'] if row else value


def identify(db, owner, local, identity):
    """Called only by the paired desktop after verifying its native identity.

    Never automatically combine two populated account histories. Existing
    verified aliases win; a legacy-only account can bind its stable identity.
    """
    schema(db)
    local, identity = key(local), key(identity)
    known = db.execute('SELECT canonical FROM hr_account_aliases WHERE owner=? AND alias=?',
                       (owner, identity)).fetchone()
    if known:
        canonical = known['canonical']
        if resolve(owner, local, db) not in (local, canonical):
            raise ValueError('账号身份关联冲突，请核对历史记录')
    else:
        canonical = resolve(owner, local, db)
        if identity != canonical:
            tables = ('hr_resume_pool', 'hr_passive_rules', 'hr_sourcing_jobs', 'hr_sourcing_contacts')
            for table in tables:
                if db.execute("SELECT 1 FROM sqlite_master WHERE name=?", (table,)).fetchone() and db.execute(
                        f'SELECT 1 FROM {table} WHERE owner=? AND account=? LIMIT 1', (owner, identity)).fetchone():
                    raise ValueError('同一招聘账号存在两份历史记录，请先核对合并')
        db.execute('INSERT INTO hr_account_aliases VALUES(?,?,?)', (owner, identity, canonical))
    return canonical


def archive(db, owner, table, row):
    db.execute('INSERT OR IGNORE INTO hr_account_merge_archive VALUES(?,?,?,?)',
               (owner, table, row['id'], json.dumps(dict(row), ensure_ascii=False)))


def merge_verified(db, owner, canonical, alias):
    """Offline operator migration after independent identity verification.

    Caller owns backup/transaction. Conflicting review decisions, file bytes,
    linked job histories and unknown account tables abort the whole migration.
    No attachment or profile file is deleted.
    """
    canonical, alias = key(canonical), key(alias)
    if canonical == alias:
        raise ValueError('不能合并同一个账号标识')
    schema(db)
    if resolve(owner, canonical, db) != canonical or resolve(owner, alias, db) not in (alias, canonical):
        raise ValueError('账号已关联其他历史')
    allowed = {'hr_passive_rules', 'hr_resume_pool', 'hr_resume_commands', 'hr_talent_messages',
               'hr_passive_events', 'hr_sourcing_jobs', 'hr_sourcing_contacts', 'hr_job_availability'}
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for table in tables:
        if 'account' in {r[1] for r in db.execute(f'PRAGMA table_info("{table}")')} and table not in allowed:
            raise ValueError('存在尚未核对的账号关联表：'+table)
    summary = {'duplicatesMerged': 0, 'resumesMoved': 0, 'rulesMerged': 0, 'jobsMerged': 0}
    if 'talents' in tables and db.execute(
            'SELECT 1 FROM talents WHERE owner=? AND source_id LIKE ? LIMIT 1',
            (owner, 'passive:'+alias+':%')).fetchone():
        raise ValueError('新账号历史已流转人才，需要先核对关联关系')

    def link(kind, old, new):
        db.execute('INSERT OR REPLACE INTO hr_account_record_aliases VALUES(?,?,?,?)',
                   (owner, kind, old, new))

    for table, kind in [('hr_passive_rules', 'rule'), ('hr_sourcing_jobs', 'job')]:
        if table not in tables:
            continue
        for source in db.execute(f'SELECT * FROM {table} WHERE owner=? AND account=?', (owner, alias)).fetchall():
            target = db.execute(f'SELECT * FROM {table} WHERE owner=? AND account=? AND job_id=?',
                                (owner, canonical, source['job_id'])).fetchone()
            if not target:
                db.execute(f'UPDATE {table} SET account=? WHERE id=?', (canonical, source['id']))
                continue
            if json.loads(source['body']) != json.loads(target['body']):
                raise ValueError('同一职位存在不同配置，不能自动覆盖')
            archive(db, owner, table, source)
            if kind == 'job':
                for field in ('criteria', 'demand_source'):
                    a, b = json.loads(source[field]), json.loads(target[field])
                    if a and a != b:
                        raise ValueError('重复职位含独立配置，不能自动合并')
                for child in ('hr_sourcing_refs', 'hr_sourcing_runs', 'hr_sourcing_candidates', 'hr_sourcing_contacts'):
                    if child in tables and db.execute(f'SELECT 1 FROM {child} WHERE owner=? AND job=? LIMIT 1',
                                                      (owner, source['id'])).fetchone():
                        raise ValueError('重复职位已有独立寻源记录，需单独核对')
                summary['jobsMerged'] += 1
            else:
                if source['updated_at'] > target['updated_at']:
                    archive(db, owner, table, target)
                    db.execute('''UPDATE hr_passive_rules SET enabled=?,enabled_at=?,version=?,updated_at=?
                        WHERE id=?''', (source['enabled'], source['enabled_at'],
                                        max(source['version'], target['version'])+1,
                                        source['updated_at'], target['id']))
                db.execute('UPDATE hr_resume_pool SET rule_id=? WHERE owner=? AND account=? AND rule_id=?',
                           (target['id'], owner, alias, source['id']))
                summary['rulesMerged'] += 1
            link(kind, source['id'], target['id'])
            db.execute(f'DELETE FROM {table} WHERE id=? AND owner=?', (source['id'], owner))

    for source in db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND account=?', (owner, alias)).fetchall():
        target = db.execute('''SELECT * FROM hr_resume_pool WHERE owner=? AND account=?
            AND friend_id=? AND message_id=?''', (owner, canonical, source['friend_id'], source['message_id'])).fetchone()
        if target:
            if source['file_hash'] != target['file_hash']:
                raise ValueError('同一附件消息的内容不一致，不能自动合并')
            if source['status'] != 'pending':
                raise ValueError('重复简历已有审核结果，不能自动覆盖')
            if db.execute('SELECT 1 FROM hr_resume_commands WHERE owner=? AND resume_id=?',
                          (owner, source['id'])).fetchone():
                raise ValueError('重复简历有关联发送记录')
            archive(db, owner, 'hr_resume_pool', source)
            link('resume', source['id'], target['id'])
            db.execute('DELETE FROM hr_resume_pool WHERE owner=? AND id=?', (owner, source['id']))
            summary['duplicatesMerged'] += 1
        else:
            db.execute('UPDATE hr_resume_pool SET account=? WHERE owner=? AND id=?',
                       (canonical, owner, source['id']))
            summary['resumesMoved'] += 1
    if 'hr_job_availability' in tables:
        rows = db.execute('SELECT * FROM hr_job_availability WHERE owner=? AND account IN (?,?) ORDER BY checked_at DESC',
                          (owner, alias, canonical)).fetchall()
        if rows:
            db.execute('DELETE FROM hr_job_availability WHERE owner=? AND account IN (?,?)', (owner, alias, canonical))
            db.execute('INSERT INTO hr_job_availability VALUES(?,?,?,?)',
                       (owner, canonical, rows[0]['body'], rows[0]['checked_at']))
    for table in allowed - {'hr_passive_rules', 'hr_sourcing_jobs', 'hr_resume_pool', 'hr_job_availability'}:
        if table in tables:
            db.execute(f'UPDATE {table} SET account=? WHERE owner=? AND account=?', (canonical, owner, alias))
    db.execute('INSERT OR REPLACE INTO hr_account_aliases VALUES(?,?,?)', (owner, alias, canonical))
    return summary
