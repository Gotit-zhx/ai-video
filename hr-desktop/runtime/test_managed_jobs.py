import unittest
from unittest import mock
import tempfile
from pathlib import Path

import managed_jobs as jobs


class ManagedJobsTest(unittest.TestCase):
    def job(self, identity, name="新岗位", **extra):
        return {"encryptJobId": identity, "jobName": name, "jobStatus": 0, "jobAuditStatus": 1,
                "addressShowText": "厦门", "salaryDesc": "10-15K", "degreeName": "本科",
                "experienceName": "经验不限", "securityId": "private", **extra}

    def page(self, rows, more=False):
        return {"code": 0, "zpData": {"data": rows, "hasMore": more}}

    def test_new_jobs_without_chats_and_real_statuses_are_returned(self):
        client = mock.Mock()
        client._request.return_value = self.page([
            self.job("old", "秘书"), self.job("new", "图像算法"),
            self.job("expired", "验证工程师", jobAuditStatus=5)])
        result = jobs.listing(client)
        self.assertTrue(result["ok"])
        self.assertEqual([r["statusDesc"] for r in result["data"]["jobs"]], ["开放中", "开放中", "已失效"])
        self.assertEqual(result["data"]["jobs"][1]["jobName"], "图像算法")
        self.assertNotIn("securityId", result["data"]["jobs"][1])
        client._request.assert_called_once_with("GET", jobs.URL,
            params={"position": 0, "type": 0, "searchStr": "", "comId": "", "tagIdStr": "", "page": 1},
            extra_headers={"Referer": jobs.REFERER}, retry=False)

    def test_all_pages_are_loaded_and_duplicate_jobs_are_not_repeated(self):
        client = mock.Mock()
        client._request.side_effect = [
            self.page([self.job("a")], True), self.page([self.job("a"), self.job("b")])]
        result = jobs.listing(client)
        self.assertEqual(result["data"]["total"], 2)
        self.assertEqual(result["data"]["pagesRead"], 2)
        self.assertEqual([call.kwargs["params"]["page"] for call in client._request.call_args_list], [1, 2])

    def test_incomplete_or_repeating_pages_never_become_a_complete_empty_list(self):
        for second in [self.page([self.job("a")], True), self.page([], True),
                       {"code": 1}, {"code": 0, "zpData": {}}, self.page([{}])]:
            client = mock.Mock()
            client._request.side_effect = [self.page([self.job("a")], True), second]
            self.assertFalse(jobs.listing(client)["ok"])
            self.assertEqual(client._request.call_count, 2)

    def test_guarded_risk_errors_propagate_without_fallback_or_retry(self):
        client = mock.Mock()
        client._request.side_effect = RuntimeError("paused")
        with self.assertRaisesRegex(RuntimeError, "paused"): jobs.listing(client)
        client._request.assert_called_once()

    def test_status_mapping_preserves_auditing_and_closed_states(self):
        for state, audit, label in [(0, 0, "审核中"), (0, 6, "审核中"), (0, 2, "审核不通过"),
                                    (1, 1, "已关闭"), (3, 1, "待开放"), (0, 7, "修改审核中")]:
            self.assertEqual(jobs.status(self.job("a", jobStatus=state, jobAuditStatus=audit)), label)

    def test_account_snapshot_is_shared_without_repeated_remote_reads_and_expires(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(jobs.time, 'time', return_value=1000) as clock:
            root = Path(directory)
            client = mock.Mock()
            client.passive_delay.return_value = 0
            client.read_managed_jobs.return_value = jobs.listing(
                mock.Mock(_request=mock.Mock(return_value=self.page([self.job("a")]))))
            jobs.save_statuses(root, client.read_managed_jobs.return_value)
            self.assertEqual(jobs.availability(client, root)['statuses'], {'a': '开放中'})
            client.read_managed_jobs.assert_not_called()
            other = root / 'other-account'; other.mkdir()
            self.assertEqual(jobs.availability(client, other)['statuses'], {'a': '开放中'})
            client.read_managed_jobs.assert_called_once()
            clock.return_value = 1300
            client.read_managed_jobs.return_value = {'ok': False}
            self.assertEqual(jobs.availability(client, root)['statuses'], {})
            self.assertEqual(jobs.availability(client, root)['statuses'], {})
            self.assertEqual(client.read_managed_jobs.call_count, 2, 'A rejected list is not retried each poll')
            self.assertEqual((root / 'managed-job-statuses.json').stat().st_mode & 0o777, 0o600)

    def test_budget_and_guard_failure_never_allow_old_open_evidence(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(jobs.time, 'time', return_value=1000) as clock:
            client = mock.Mock()
            root = Path(directory)
            result = {'ok': True, 'data': {'jobs': [{'encryptJobId': 'a', 'statusDesc': '开放中'}], 'hasMore': False}}
            jobs.save_statuses(root, result)
            clock.return_value = 1300
            client.passive_delay.return_value = 120
            self.assertEqual(jobs.availability(client, root)['statuses'], {})
            client.read_managed_jobs.assert_not_called()
            client.passive_delay.return_value = 0
            client.read_managed_jobs.side_effect = RuntimeError('paused')
            self.assertEqual(jobs.availability(client, root)['statuses'], {})
            self.assertEqual(jobs.availability(client, root)['statuses'], {})
            client.read_managed_jobs.assert_called_once()

    def test_closed_removed_and_incomplete_lists_invalidate_open_status(self):
        with tempfile.TemporaryDirectory() as directory:
            client = mock.Mock()
            root = Path(directory)
            for rows in [[self.job('a')], [self.job('a', jobStatus=1)], []]:
                client._request.return_value = self.page(rows)
                snapshot = jobs.save_statuses(root, jobs.listing(client))
                self.assertEqual(snapshot['statuses'].get('a'), jobs.status(rows[0]) if rows else None)
            snapshot = jobs.save_statuses(root, {'ok': True, 'data': {'jobs': [self.job('a')], 'hasMore': True}})
            self.assertEqual(snapshot['statuses'], {})
            self.assertEqual(snapshot['validUntil'], 0)


if __name__ == "__main__":
    unittest.main()
