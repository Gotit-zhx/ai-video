import unittest
from unittest import mock

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


if __name__ == "__main__":
    unittest.main()
