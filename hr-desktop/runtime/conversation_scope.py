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
        # The SDK's accept-resume operation validates its exact incoming
        # message and reads friend_detail directly, not through the helper
        # above. Apply the same job boundary to that existing read as well.
        detail = getattr(client_type, 'friend_detail', None)
        if callable(detail):
            @functools.wraps(detail)
            def checked_detail(client, friend_ids, *args, **kwargs):
                result = detail(client, friend_ids, *args, **kwargs)
                if self.expected is not None and result.get('code') == 0:
                    friend, job = self.expected
                    data = result.get('zpData') or {}
                    rows = data.get('friendList', []) if isinstance(data, dict) else []
                    matches = [row for row in rows if isinstance(row, dict)
                               and str(row.get('friendId') or row.get('uid') or '') == friend]
                    if (list(map(str, friend_ids)) != [friend] or len(matches) != 1
                            or str(matches[0].get('encryptJobId') or matches[0].get('encJobId') or '') != job):
                        self.violation = True
                        raise ScopeError("会话与当前职位不一致或职位未核实，本次没有发送")
                return result
            client_type.friend_detail = checked_detail

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
