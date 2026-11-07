import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import notify


class Notifications(unittest.TestCase):
    def test_incident_is_deduplicated_and_only_confirmed_scopes_close(self):
        issues=[{'number':1,'title':'[运维] app / availability (critical)','body':'<!-- heyi-ops:app:availability -->\n'},
                {'number':2,'title':'backup','body':'<!-- heyi-ops:backup:stale -->\n'}]
        calls=[]
        def api(path,method='GET',body=None):
            calls.append((path,method,body))
            return issues if path.startswith('issues?') else {}
        alert={'service':'app','type':'availability','severity':'critical'}
        with patch.object(notify,'api',side_effect=api):
            notify.reconcile([alert],{'app'})
        self.assertFalse(any(method!='GET' for _,method,_ in calls))
        calls.clear()
        with patch.object(notify,'api',side_effect=api):notify.reconcile([],{'app'})
        self.assertTrue(any(path=='issues/1' and body.get('state')=='closed' for path,_,body in calls if body))
        self.assertFalse(any(path.startswith('issues/2') for path,_,_ in calls))
    def test_severity_updates_existing_issue_without_recreating_it(self):
        issue={'number':1,'title':'[运维] disk / free-space (warning)','body':'<!-- heyi-ops:disk:free-space -->\n'}
        with patch.object(notify,'api',side_effect=lambda path,*_: [issue] if path.startswith('issues?') else {}) as api:
            notify.reconcile([{'service':'disk','type':'free-space','severity':'critical'}],{'disk'})
        self.assertTrue(any(call.args[:2]==('issues/1','PATCH') for call in api.call_args_list))
        self.assertFalse(any(call.args[:2]==('issues','POST') for call in api.call_args_list))
    def test_arbitrary_diagnostic_text_is_never_published(self):
        with patch.object(notify,'api') as api:
            with self.assertRaises(ValueError):notify.reconcile([{'service':'app','type':'password=value','severity':'critical'}],set())
            api.assert_not_called()
