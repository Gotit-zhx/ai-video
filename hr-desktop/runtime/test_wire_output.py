import io
import json
import unittest
from unittest import mock

import managed_jobs
from wire_output import write_result


class WireOutputTest(unittest.TestCase):
    def test_real_job_listing_round_trips_under_windows_stdout_code_pages(self):
        client = mock.Mock()
        client._request.side_effect = [
            {"code": 0, "zpData": {"hasMore": True, "data": [{
                "encryptJobId": "first", "jobName": "人力资源经理（招聘）",
                "addressShowText": "厦门·思明", "degreeName": "本科",
                "experienceName": "3–5年", "salaryDesc": "15–20K",
                "jobStatus": 0, "jobAuditStatus": 1}]}},
            {"code": 0, "zpData": {"hasMore": False, "data": [{
                "encryptJobId": "second", "jobName": "AI项目经理🚀",
                "jobStatus": 1, "jobAuditStatus": 1}]}}
        ]
        result = managed_jobs.listing(client)
        self.assertEqual(result["data"]["pagesRead"], 2)
        for codec in ("cp936", "cp1252", "utf-8"):
            with self.subTest(codec=codec):
                raw = io.BytesIO()
                output = io.TextIOWrapper(raw, encoding=codec, errors="strict")
                write_result(result, output)
                payload = raw.getvalue()
                self.assertTrue(payload.isascii())
                self.assertEqual(json.loads(payload.decode("utf-8")), result)
                output.detach()

    def test_errors_preserve_chinese_without_console_encoding(self):
        raw = io.BytesIO()
        output = io.TextIOWrapper(raw, encoding="cp1252")
        value = {"ok": False, "error": {"message": "仅开放中的职位可操作"}}
        write_result(value, output)
        self.assertEqual(json.loads(raw.getvalue()), value)
        output.detach()


if __name__ == "__main__":
    unittest.main()
