import sqlite3
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops.host import ACTIVE


class PublishBacklogSafePoint(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:');self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE product_publish_workflows (id TEXT,status TEXT,attempt_count INTEGER,locked_by TEXT,locked_until TEXT,publication_attempted INTEGER)')

    def active(self):
        return {row[0] for row in self.db.execute('SELECT id FROM product_publish_workflows WHERE '+ACTIVE['product_publish_workflows'])}

    def test_never_claimed_unlocked_queue_does_not_prevent_its_own_repair(self):
        self.db.executemany('INSERT INTO product_publish_workflows VALUES (?,?,?,?,?,?)',[
            ('pending','PENDING',0,None,None,0),('queued','QUEUED',0,None,None,0)])
        self.assertEqual(set(),self.active())
        self.assertEqual(2,self.db.execute('SELECT COUNT(*) FROM product_publish_workflows').fetchone()[0])

    def test_claimed_or_uncertain_work_remains_an_active_blocker(self):
        self.db.executemany('INSERT INTO product_publish_workflows VALUES (?,?,?,?,?,?)',[
            ('prior-claim','PENDING',1,None,None,0),
            ('owner','PENDING',0,'worker',None,0),
            ('expired-lock','PENDING',0,None,'2000-01-01',0),
            ('external','PENDING',0,None,None,1),
            ('running','RUNNING',1,'worker','2100-01-01',0),
            ('success','SUCCEEDED',1,None,None,1),('failed','FAILED',1,None,None,1)])
        self.assertEqual({'prior-claim','owner','expired-lock','external','running'},self.active())
