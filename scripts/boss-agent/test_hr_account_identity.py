import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import hr_account_identity as identities
import passive_pool as feature
import talent_pool as pool

USER = {'id': 'hr', 'username': '13800000000', 'organizationCode': 'heyi',
        'platformAdmin': True, 'roles': ['ADMIN']}
MAC, WIN = 'a'*64, 'b'*64


class IdentityTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        patch = mock.patch.object(pool, 'DB', Path(folder.name)/'test.db')
        patch.start(); self.addCleanup(patch.stop)
        with pool.database() as db:
            feature.schema(db)
        self.raw = b'%PDF-1.4 fixture'

    def receipt(self, record, account, friend='123', mid='456', origin='passive', status='pending', owner='heyi'):
        with pool.database() as db:
            db.execute('''INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,
                file_key,file_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
                       (record, owner, account, friend, 'rule1', mid,
                        json.dumps({'origin': origin, 'jobId': 'job1', 'jobName': '运营'}),
                        'existing-private-file', hashlib.sha256(self.raw).hexdigest(), status,
                        feature.now(), feature.now()))

    def test_merge_keeps_original_review_source_and_receipt_link_and_is_idempotent(self):
        self.receipt('original', MAC, status='approved')
        self.receipt('duplicate', WIN, origin='boss_chat')
        self.receipt('new-message', WIN, mid='457', origin='boss_chat')
        with pool.database() as db:
            result = identities.merge_verified(db, 'heyi', MAC, WIN)
        self.assertEqual(result['duplicatesMerged'], 1)
        self.assertEqual(result['resumesMoved'], 1)
        with pool.database() as db:
            saved = db.execute("SELECT * FROM hr_resume_pool WHERE id='original'").fetchone()
            self.assertEqual(saved['status'], 'approved')
            self.assertEqual(json.loads(saved['body'])['origin'], 'passive')
            self.assertEqual(identities.record(db, 'heyi', 'resume', 'duplicate'), 'original')
            self.assertEqual(db.execute('SELECT count(*) FROM hr_resume_pool').fetchone()[0], 2)
            self.assertEqual(identities.merge_verified(db, 'heyi', MAC, WIN)['duplicatesMerged'], 0)
        self.assertEqual(identities.resolve('heyi', WIN), MAC)
        self.assertEqual(identities.resolve('another-owner', WIN), WIN)
        with mock.patch('resume_storage.validate'), mock.patch('resume_storage.store') as store:
            ack = feature.ingest(USER, {'accountKey': WIN, 'friendId': '123', 'messageId': '456',
                'ruleId': 'boss-chat-123', 'origin': 'boss_chat', 'extension': 'pdf',
                'fileBase64': base64.b64encode(self.raw).decode()})
        self.assertEqual(ack, {'id': 'original', 'stored': True})
        store.assert_not_called()

    def test_conflicting_review_aborts_entire_transaction(self):
        self.receipt('original', MAC, status='approved')
        self.receipt('duplicate', WIN, status='rejected')
        with self.assertRaisesRegex(ValueError, '审核'):
            with pool.database() as db:
                identities.merge_verified(db, 'heyi', MAC, WIN)
        self.assertEqual(identities.resolve('heyi', WIN), WIN)
        with pool.database() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM hr_resume_pool').fetchone()[0], 2)

    def test_sync_is_paginated_exact_private_and_survives_deleted_last_row(self):
        for i in range(201):
            self.receipt('r'+str(i), MAC, mid=str(1000+i))
        self.receipt('other-account', WIN)
        self.receipt('other-owner', MAC, owner='another-owner')
        first = feature.device_config(USER, {'accountKey': MAC, 'receiptSync': True})
        self.assertEqual(len(first['receipts']), 200); self.assertTrue(first['hasMore'])
        second = feature.device_config(USER, {'accountKey': MAC, 'receiptSync': True, 'receiptCursor': first['nextCursor']})
        self.assertEqual([r['id'] for r in second['receipts']], ['r200'])
        self.assertFalse(second['hasMore'])
        with pool.database() as db:
            db.execute("DELETE FROM hr_resume_pool WHERE id IN ('r200','other-account','other-owner')")
        self.receipt('later', MAC, mid='9999')
        third = feature.device_config(USER, {'accountKey': MAC, 'receiptSync': True, 'receiptCursor': second['nextCursor']})
        self.assertEqual([r['id'] for r in third['receipts']], ['later'])
        self.assertNotIn('file_key', third['receipts'][0])

    def test_identity_binding_preserves_legacy_directory_key_without_merging_populated_accounts(self):
        self.receipt('original', MAC)
        result = feature.device_config(USER, {'accountKey': MAC, 'identityKey': WIN, 'identityOnly': True})
        self.assertEqual(result['accountKey'], MAC)
        self.assertEqual(feature.device_config(USER, {'accountKey': WIN, 'identityKey': WIN,
                                                      'identityOnly': True})['accountKey'], MAC)
        self.receipt('independent', 'c'*64)
        with self.assertRaisesRegex(ValueError, '两份历史'):
            feature.device_config(USER, {'accountKey': MAC, 'identityKey': 'c'*64, 'identityOnly': True})


if __name__ == '__main__':
    unittest.main()
