import json
import base64
from pathlib import Path
import tempfile
import threading
import uuid
import unittest
from unittest import mock

import passive_pool as feature
import talent_pool as pool

USER = {'id':'hr', 'username':'13800000000', 'organizationCode':'heyi', 'platformAdmin':True, 'roles':['ADMIN']}
KEY = 'a'*64


class PassiveTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,'DB',Path(folder.name)/'test.db');patch.start();self.addCleanup(patch.stop)
        self.seed()

    def seed(self):
        with pool.database() as db:
            feature.schema(db)
            body={'name':'王同学','jobName':'运营','jobDescription':'负责岗位运营工作','jobEducation':'本科',
                  'jobExperience':'3年','resumeText':'工作经验与岗位相关。'*10,'fileName':'resume.pdf','projectId':''}
            db.execute('''INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,file_hash,created_at,updated_at)
                VALUES('r1','heyi',?,'123','rule1','456',?,'key','hash',?,?)''',(KEY,json.dumps(body),feature.now(),feature.now()))

    def template(self,**extra):
        return feature.save_speech(USER,{'name':'默认婉拒','kind':'reject','content':'{姓名}，感谢关注{职位}，本次暂不合适。','isDefault':True,**extra})

    def test_default_is_required_and_no_implicit_text_is_sent(self):
        with self.assertRaisesRegex(ValueError,'默认婉拒'):
            feature.review(USER,{'id':'r1','version':1,'action':'reject','message':'client supplied'})
        self.assertEqual(feature.listing(USER,'resume-pool',{})['items'][0]['status'],'pending')

    def test_filename_backfill_preserves_review_ai_and_manual_edits(self):
        with pool.database() as db:
            feature.schema(db)
            detail=json.loads(db.execute("SELECT body FROM hr_resume_pool WHERE id='r1'").fetchone()[0])
            detail.update(name='',extension='pdf',nameExtractionVersion=1,
                          originalFileName='苏琪简历.pdf',resumeText='姓名：张晓林')
            db.execute("UPDATE hr_resume_pool SET body=?,status='approved',ai_state='complete' WHERE id='r1'",(json.dumps(detail),))
        item=feature.listing(USER,'resume-pool',{})['items'][0]
        self.assertEqual(item['name'],'苏琪');self.assertEqual(item['nameSource'],'resume_filename')
        self.assertEqual(item['fileName'],'苏琪简历.pdf')
        self.assertEqual(item['status'],'approved');self.assertEqual(item['aiState'],'complete')
        again=feature.listing(USER,'resume-pool',{})['items'][0]
        self.assertEqual(again['version'],item['version'])
        with pool.database() as db:
            feature.schema(db)
            detail.update(name='手动姓名',nameSource='manual',nameExtractionVersion=1)
            db.execute("UPDATE hr_resume_pool SET body=? WHERE id='r1'",(json.dumps(detail),))
        self.assertEqual(feature.listing(USER,'resume-pool',{})['items'][0]['name'],'手动姓名')

    def test_job_and_review_filters_share_scoped_counts_and_cannot_cross_organizations(self):
        with pool.database() as db:
            feature.schema(db)
            for rule,job,name,owner in [('rule1','job1','运营','heyi'),('rule2','job2','秘书','heyi'),
                                        ('foreign','job1','其他组织','other')]:
                db.execute('INSERT INTO hr_passive_rules VALUES(?,?,?,?,?,1,100,1,?)',
                           (rule,owner,KEY,job,json.dumps({'jobName':name}),feature.now()))
            db.execute("INSERT INTO hr_resume_pool SELECT 'r2',owner,account,'234','rule2','567',body,file_key,file_hash,"
                       "'approved',version,ai_state,ai_result,ai_error,ai_job,ai_at,created_at,updated_at FROM hr_resume_pool WHERE id='r1'")
        first=feature.listing(USER,'resume-pool',{'jobId':'job1','status':'pending'})
        self.assertEqual([r['id'] for r in first['items']],['r1'])
        self.assertEqual(first['counts'],{'pending':1,'approved':0,'rejected':0,'all':1})
        second=feature.listing(USER,'resume-pool',{'jobId':'job2','status':'pending'})
        self.assertEqual(second['total'],0);self.assertEqual(second['counts']['approved'],1)
        self.assertEqual({j['name'] for j in second['jobs']},{'运营','秘书'})
        self.assertEqual(feature.listing(USER,'resume-pool',{'jobId':'unknown'})['counts']['all'],0)

    def test_reject_uses_configured_snapshot_without_second_confirmation(self):
        template=self.template()
        result=feature.review(USER,{'id':'r1','version':1,'action':'reject','message':'must ignore','templateVersion':999})
        self.assertEqual(result['rejectionStatus'],'queued')
        feature.save_speech(USER,{**template,'content':'changed'})
        commands=feature.device_config(USER,{'accountKey':KEY})['commands']
        self.assertEqual(len(commands),1)
        self.assertEqual(commands[0]['message'],'王同学，感谢关注运营，本次暂不合适。')
        self.assertEqual(commands[0]['templateVersion'],1)
        self.assertFalse(feature.device_config(USER,{'accountKey':'b'*64})['commands'])
        with self.assertRaises(pool.Conflict):feature.review(USER,{'id':'r1','version':1,'action':'reject'})

    def test_default_switch_disable_and_template_validation(self):
        first=self.template()
        second=self.template(name='新默认')
        items=feature.listing(USER,'speeches',{})['items']
        self.assertEqual([s['id'] for s in items if s['isDefault']],[second['id']])
        feature.save_speech(USER,{**second,'active':False,'isDefault':False})
        with self.assertRaises(ValueError):feature.review(USER,{'id':'r1','version':1,'action':'reject'})
        with self.assertRaises(ValueError):self.template(kind='invite')
        with self.assertRaises(ValueError):self.template(content='{手机号}')
        with self.assertRaises(pool.Conflict):feature.save_speech(USER,{**first,'version':0})

    def test_human_approve_does_not_invoke_ai_or_enqueue_message(self):
        with mock.patch.object(feature.threading,'Thread') as worker:
            result=feature.review(USER,{'id':'r1','version':1,'action':'approve'})
            worker.assert_not_called()
        self.assertEqual(pool.listing(USER,{})['items'][0]['stage'],'invited')
        self.assertFalse(feature.device_config(USER,{'accountKey':KEY})['commands'])
        self.assertEqual(result['status'],'approved')

    def test_approval_reads_candidate_facts_without_using_job_requirements(self):
        with pool.database() as db:
            feature.schema(db)
            detail=json.loads(db.execute("SELECT body FROM hr_resume_pool WHERE id='r1'").fetchone()[0])
            detail.update(resumeText='学历：大专\n工作年限：2年\n现居城市：广州\n最近公司：示例有限公司')
            db.execute("UPDATE hr_resume_pool SET body=? WHERE id='r1'",(json.dumps(detail),))
        feature.review(USER,{'id':'r1','version':1,'action':'approve'})
        item=pool.listing(USER,{})['items'][0]
        self.assertEqual((item['education'],item['experience'],item['city'],item['company']),
                         ('大专','2年','广州','示例有限公司'))

    def test_approval_accepts_only_active_owned_department_and_persists_selection(self):
        import hr_departments
        department=hr_departments.save(USER,{'name':'研发部','code':'DEV','status':'ACTIVE'})
        with self.assertRaises(ValueError):
            feature.review(USER,{'id':'r1','version':1,'action':'approve','departmentId':'missing'})
        feature.review(USER,{'id':'r1','version':1,'action':'approve','departmentId':department['id']})
        self.assertEqual(pool.listing(USER,{'department':department['id']})['total'],1)
        self.assertEqual(feature.listing(USER,'resume-pool',{})['items'][0]['departmentId'],department['id'])

    def test_atomic_claim_and_cancel_then_uncertain_never_requeues(self):
        self.template();feature.review(USER,{'id':'r1','version':1,'action':'reject'})
        command=feature.device_config(USER,{'accountKey':KEY})['commands'][0]
        payload={'accountKey':KEY,'id':command['id']}
        outcomes=[]
        workers=[threading.Thread(target=lambda:outcomes.append(feature.claim_command(USER,payload)['claimed'])) for _ in range(2)]
        for worker in workers:worker.start()
        for worker in workers:worker.join()
        self.assertEqual(sorted(outcomes),[False,True])
        with self.assertRaises(pool.Conflict):feature.review(USER,{'id':'r1','action':'cancel-send'})
        feature.recover()
        self.assertFalse(feature.device_config(USER,{'accountKey':KEY})['commands'])
        self.assertEqual(feature.listing(USER,'resume-pool',{})['items'][0]['rejection']['status'],'uncertain')

    def test_lost_claim_expires_even_without_restart(self):
        self.template();feature.review(USER,{'id':'r1','version':1,'action':'reject'})
        with pool.database() as db:
            feature.schema(db);db.execute("UPDATE hr_resume_commands SET status='sending',updated_at='2020-01-01'")
        self.assertEqual(feature.listing(USER,'resume-pool',{})['items'][0]['rejection']['status'],'uncertain')
        self.assertFalse(feature.device_config(USER,{'accountKey':KEY})['commands'])

    def test_ai_only_starts_explicitly_and_never_changes_review_status(self):
        with mock.patch.object(feature.threading,'Thread') as worker:
            self.assertNotIn('resumeText',feature.listing(USER,'resume-pool',{})['items'][0])
            feature.device_config(USER,{'accountKey':KEY})
            worker.assert_not_called()
            result=feature.analyze(USER,{'id':'r1'},'test-session')
            self.assertEqual(result['aiState'],'running');worker.assert_called_once()
            feature.analyze(USER,{'id':'r1'},'test-session');worker.assert_called_once()
        self.assertEqual(feature.listing(USER,'resume-pool',{'id':'r1'})['items'][0]['status'],'pending')

    def test_rule_default_off_reenable_ignores_previous_history(self):
        body={'accountKey':KEY,'jobId':'job-1','jobName':'运营'}
        first=feature.save_rule(USER,body);self.assertFalse(first['enabled'])
        with mock.patch.object(feature.time,'time',return_value=200):
            enabled=feature.save_rule(USER,{**first,'enabled':True})
        self.assertEqual(enabled['enabledAt'],200)
        self.assertEqual(len(feature.device_config(USER,{'accountKey':KEY})['rules']),1)
        with self.assertRaises(ValueError):feature.listing({**USER,'username':'other'},'resume-pool',{})

    def test_passive_reply_default_is_separate_from_rejection_and_explicit(self):
        reject=self.template()
        self.assertIsNone(feature.device_config(USER,{'accountKey':KEY})['replyTemplate'])
        reply=feature.save_speech(USER,{'name':'主动消息回复','kind':'passive_reply',
            'content':'您好，可以沟通下{职位}。','isDefault':True})
        config=feature.device_config(USER,{'accountKey':KEY})
        self.assertEqual(config['replyTemplate']['id'],reply['id'])
        defaults={s['id'] for s in feature.listing(USER,'speeches',{})['items'] if s['isDefault']}
        self.assertEqual(defaults,{reply['id'],reject['id']})
        feature.review(USER,{'id':'r1','version':1,'action':'reject'})
        self.assertEqual(feature.device_config(USER,{'accountKey':KEY})['commands'][0]['templateId'],reject['id'])
        feature.save_speech(USER,{**reply,'active':False,'isDefault':False})
        self.assertIsNone(feature.device_config(USER,{'accountKey':KEY})['replyTemplate'])

    def test_private_resume_ingestion_is_idempotent_and_does_not_call_ai(self):
        rule=feature.save_rule(USER,{'accountKey':KEY,'jobId':'job1','jobName':'运营'})
        body={'accountKey':KEY,'ruleId':rule['id'],'friendId':'888','messageId':'999',
              'name':'测试','extension':'pdf','fileBase64':base64.b64encode(b'%PDF-1.4 test').decode()}
        with mock.patch('resume_storage.store',return_value='private-key') as store, \
             mock.patch('resume_storage.extract',return_value=('resume text','note')), \
             mock.patch.object(feature.threading,'Thread') as ai:
            first=feature.ingest(USER,body);self.assertEqual(feature.ingest(USER,body),first)
            store.assert_called_once();ai.assert_not_called()
            with self.assertRaises(pool.Conflict):
                feature.ingest(USER,{**body,'fileBase64':base64.b64encode(b'%PDF-1.4 different').decode()})
        row=feature.listing(USER,'resume-pool',{'id':first['id']})['items'][0]
        self.assertEqual(row['aiState'],'idle')
        self.assertEqual(row['status'],'pending')

    def test_existing_pdf_identity_backfill_preserves_review_and_does_not_call_providers(self):
        with pool.database() as db:
            raw = json.loads(db.execute("SELECT body FROM hr_resume_pool WHERE id='r1'").fetchone()[0])
            raw.update(name='',extension='pdf',resumeText='某学院 本科\n陈佳宁\n女 | 24岁\n教育经历')
            db.execute("UPDATE hr_resume_pool SET body=? WHERE id='r1'", (json.dumps(raw),))
        with mock.patch('resume_storage.download') as download, mock.patch.object(feature.threading,'Thread') as ai:
            first=feature.listing(USER,'resume-pool',{})['items'][0]
            second=feature.listing(USER,'resume-pool',{'id':'r1'})['items'][0]
            self.assertEqual(first['name'],'陈佳宁')
            self.assertEqual(first['nameSource'],'resume_text')
            self.assertEqual(first['version'],second['version'])
            self.assertEqual(first['status'],'pending')
            self.assertEqual(first['fileName'],'陈佳宁-简历.pdf')
            download.assert_not_called();ai.assert_not_called()

    def test_approval_classification_and_invitation_are_human_controlled_and_account_bound(self):
        approved=feature.review(USER,{'id':'r1','version':1,'action':'approve'})
        self.assertEqual(feature.listing(USER,'resume-pool',{'status':'pending'})['total'],0)
        self.assertEqual(feature.listing(USER,'resume-pool',{'status':'approved'})['total'],1)
        self.assertEqual(pool.listing(USER,{'stage':'invited'})['items'][0]['id'],approved['talentId'])
        self.assertFalse(feature.device_config(USER,{'accountKey':KEY})['commands'],'Approval alone sends nothing')
        payload={'talentId':approved['talentId'],'requestId':str(uuid.uuid4()),'message':'您好，明天下午方便沟通吗？',
                 'friendId':'999','accountKey':'b'*64}
        first=feature.invitation_messages(USER,payload,write=True)
        self.assertEqual(feature.invitation_messages(USER,payload,write=True),first)
        with self.assertRaises(pool.Conflict):
            feature.invitation_messages(USER,{**payload,'message':'changed'},write=True)
        command=feature.device_config(USER,{'accountKey':KEY})['commands'][0]
        self.assertEqual(command['friendId'],'123')
        self.assertEqual(command['message'],payload['message'])
        self.assertEqual(command['kind'],'invite')
        self.assertFalse(feature.device_config(USER,{'accountKey':'b'*64})['commands'])
        self.assertFalse(feature.claim_command(USER,{'id':first['id'],'accountKey':'b'*64})['claimed'])
        self.assertTrue(feature.claim_command(USER,{'id':first['id'],'accountKey':KEY})['claimed'])
        self.assertFalse(feature.claim_command(USER,{'id':first['id'],'accountKey':KEY})['claimed'])
        feature.device_report(USER,{'commandId':first['id'],'accountKey':KEY,'status':'sent'})
        self.assertEqual(feature.invitation_messages(USER,{'talentId':approved['talentId']})['items'][0]['status'],'sent')
        self.assertFalse(feature.device_config(USER,{'accountKey':KEY})['commands'])

    def test_invitation_cannot_send_to_forged_sources_or_after_stage_change(self):
        approved=feature.review(USER,{'id':'r1','version':1,'action':'approve'})
        payload={'talentId':approved['talentId'],'requestId':str(uuid.uuid4()),'message':'您好'}
        command=feature.invitation_messages(USER,payload,write=True)
        with pool.database() as db:
            db.execute("UPDATE talents SET stage='first' WHERE id=?", (approved['talentId'],))
        self.assertFalse(feature.claim_command(USER,{'id':command['id'],'accountKey':KEY})['claimed'])
        with self.assertRaises(ValueError):feature.invitation_messages(USER,{**payload,'requestId':str(uuid.uuid4())},write=True)
        with pool.database() as db:
            db.execute("UPDATE talents SET stage='invited',source_id=? WHERE id=?", ('passive:'+KEY+':999',approved['talentId']))
        with self.assertRaisesRegex(ValueError,'已通过简历'):
            feature.invitation_messages(USER,{**payload,'requestId':str(uuid.uuid4())},write=True)

if __name__=='__main__':unittest.main()
