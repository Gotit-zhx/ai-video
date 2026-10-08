"""Reject cross-job writes using the SDK's existing official friend detail read."""
import contextlib
import functools

CODE = "JOB_CONVERSATION_MISMATCH"


class ScopeError(RuntimeError):
    pass


class ConversationScope:
    def __init__(self, client_type):
        self.expected = None
        self.violation = False
        original = client_type._require_chat_friend_data

        @functools.wraps(original)
        def require(client, friend_id):
            data = original(client, friend_id)
            if self.expected is not None:
                friend, job = self.expected
                actual_friend = str(data.get("friendId") or data.get("uid") or "")
                actual_job = str(data.get("encryptJobId") or data.get("encJobId") or "")
                if str(friend_id) != friend or actual_friend != friend or actual_job != job:
                    self.violation = True
                    raise ScopeError("会话与当前职位不一致或职位未核实，本次没有发送")
            return data

        # No extra BOSS request, navigation, SDK source change, or fallback.
        # This check runs before geekClick/sendText/ExchangeResume can execute.
        client_type._require_chat_friend_data = require

    @contextlib.contextmanager
    def expect(self, friend, job):
        previous = self.expected
        self.expected = (str(friend), str(job))
        self.violation = False
        try:
            yield
        finally:
            self.expected = previous

    def failure(self):
        return {"ok": False, "error": {"code": CODE,
                "message": "会话与当前职位不一致或职位未核实，本次没有发送"}}
