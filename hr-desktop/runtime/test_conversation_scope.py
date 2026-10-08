import unittest
from unittest import mock
import conversation_scope as scope


class ConversationScopeTest(unittest.TestCase):
    def client(self, data):
        read = mock.Mock(return_value=data)
        class Client:
            def _require_chat_friend_data(self, friend):
                return read(friend)
        policy = scope.ConversationScope(Client)
        return policy, Client(), read

    def test_exact_friend_and_job_reuse_one_original_read_without_mutating_payload(self):
        data={'friendId':123,'encryptJobId':'job1','securityId':'opaque'}
        policy, client, read=self.client(data)
        with policy.expect('123','job1'):
            self.assertIs(client._require_chat_friend_data(123),data)
        read.assert_called_once_with(123)
        self.assertFalse(policy.violation)

    def test_wrong_friend_wrong_job_and_missing_job_never_reach_write_payload(self):
        for data in ({'friendId':124,'encryptJobId':'job1'},
                     {'friendId':123,'encryptJobId':'other'},
                     {'friendId':123},{}):
            policy, client, read=self.client(data)
            with self.subTest(data=data),policy.expect('123','job1'):
                with self.assertRaises(scope.ScopeError):
                    client._require_chat_friend_data(123)
            self.assertTrue(policy.violation)
            read.assert_called_once()

    def test_scope_does_not_leak_to_later_explicit_operations_or_read_calls(self):
        policy, client, read=self.client({'friendId':123,'encryptJobId':'other'})
        with policy.expect('123','job1'):
            with self.assertRaises(scope.ScopeError):client._require_chat_friend_data(123)
        self.assertIsNone(policy.expected)
        self.assertEqual(client._require_chat_friend_data(123)['encryptJobId'],'other')
        with policy.expect('123','other'):
            self.assertEqual(client._require_chat_friend_data(123)['encryptJobId'],'other')
            self.assertFalse(policy.violation)

    def test_pinned_sdk_blocks_both_real_write_paths_before_browser_actions(self):
        from boss_agent_cli.api.recruiter_client import BossRecruiterClient
        original = BossRecruiterClient._require_chat_friend_data
        self.addCleanup(setattr, BossRecruiterClient, '_require_chat_friend_data', original)
        policy = scope.ConversationScope(BossRecruiterClient)
        client = object.__new__(BossRecruiterClient)
        client.friend_detail = mock.Mock(return_value={
            'code': 0, 'zpData': {'friendList': [
                {'uid': 123, 'encryptJobId': 'wrong-job', 'securityId': 'opaque'}]}})
        client._run_chat_frontend_action = mock.Mock()
        for action in (lambda: client.send_message_by_friend(123, 'configured reply'),
                       lambda: client.exchange_request_by_friend(123, 4)):
            with policy.expect('123', 'expected-job'):
                with self.assertRaises(scope.ScopeError):
                    action()
            client._run_chat_frontend_action.assert_not_called()
        self.assertEqual(client.friend_detail.call_count, 2)
        self.assertTrue(policy.violation)


if __name__ == '__main__':
    unittest.main()
