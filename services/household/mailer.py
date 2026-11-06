"""SMTP delivery for private household reminders; all settings come from env.

QQ Mail: SMTP_HOST=smtp.qq.com, SMTP_PORT=465, SMTP_SECURITY=ssl,
SMTP_USER=<QQ address>, SMTP_PASSWORD=<SMTP authorization code>,
MAIL_FROM=<the same QQ address>. No network activity occurs during import or
configuration checks. This module does not decide recipients or schedules.
"""
from __future__ import annotations

import os
import smtplib
import socket
import ssl
from dataclasses import dataclass, field
from email.errors import HeaderParseError
from email.headerregistry import Address
from email.message import EmailMessage
from email.utils import formatdate, make_msgid


class EmailDeliveryError(Exception):
    """A safe user-facing error, without SMTP responses or credential values."""

    def __init__(self, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.retryable = retryable


@dataclass(frozen=True)
class _Config:
    host: str
    port: int
    security: str
    username: str
    password: str = field(repr=False)
    sender: str
    sender_name: str
    timeout: float


def _address(value: str, label: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 320 or any(c in value for c in "\r\n<>,"):
        raise EmailDeliveryError(f"{label}应填写一个有效邮箱地址")
    value = value.strip()
    try:
        value.encode("ascii")
        parsed = Address(addr_spec=value)
        if not parsed.username or not parsed.domain or parsed.addr_spec != value:
            raise ValueError()
    except (ValueError, UnicodeError, HeaderParseError):
        raise EmailDeliveryError(f"{label}应填写一个有效邮箱地址") from None
    return value


def _config() -> _Config:
    host = os.environ.get("SMTP_HOST", "").strip()
    username = os.environ.get("SMTP_USER", "").strip()
    password = os.environ.get("SMTP_PASSWORD", "")
    sender = os.environ.get("MAIL_FROM", "").strip() or username
    missing = []
    if not host:
        missing.append("SMTP_HOST")
    if not sender:
        missing.append("MAIL_FROM")
    if username and not password:
        missing.append("SMTP_PASSWORD")
    if password and not username:
        missing.append("SMTP_USER")
    if missing:
        raise EmailDeliveryError("邮件服务尚未配置：缺少 " + "、".join(missing))
    if len(host) > 253 or any(c.isspace() for c in host) or "://" in host or "/" in host:
        raise EmailDeliveryError("SMTP_HOST 应填写邮件服务器主机名")
    try:
        port = int(os.environ.get("SMTP_PORT", "465"))
        timeout = float(os.environ.get("SMTP_TIMEOUT", "15"))
        if not 1 <= port <= 65535 or not 1 <= timeout <= 60:
            raise ValueError()
    except ValueError:
        raise EmailDeliveryError("SMTP 端口或连接超时配置无效") from None
    security = os.environ.get("SMTP_SECURITY", "ssl" if port == 465 else "starttls").lower().strip()
    if security not in {"ssl", "starttls"}:
        raise EmailDeliveryError("SMTP_SECURITY 必须为 ssl 或 starttls，邮件连接需要加密")
    sender = _address(sender, "发件邮箱")
    sender_name = os.environ.get("MAIL_FROM_NAME", "拾间家务提醒").strip()
    if len(sender_name) > 100 or any(ord(c) < 32 for c in sender_name):
        raise EmailDeliveryError("发件人名称配置无效")
    return _Config(host, port, security, username, password, sender, sender_name, timeout)


def mail_available() -> bool:
    """Whether local SMTP settings are complete; this never contacts SMTP."""
    try:
        _config()
        return True
    except EmailDeliveryError:
        return False


def email_delivery_status() -> dict:
    """Configuration status for the app, without usernames or credentials."""
    try:
        config = _config()
    except EmailDeliveryError:
        return {"configured": False, "provider": None, "fromAddress": None}
    return {"configured": True, "provider": "smtp", "fromAddress": config.sender}


def send_email(to: str, subject: str, text: str, html: str | None = None) -> dict:
    """Deliver to one mailbox, or raise a sanitized EmailDeliveryError.

    The return value confirms SMTP acceptance, not arrival in the inbox. The
    caller controls retries. In particular, this function never automatically
    retries an ambiguous network failure during message acceptance.
    """
    config = _config()
    recipient = _address(to, "收件邮箱")
    if not isinstance(subject, str) or not subject.strip() or len(subject) > 250 or any(ord(c) < 32 for c in subject):
        raise EmailDeliveryError("邮件主题无效")
    if not isinstance(text, str) or not text.strip() or len(text) > 200_000:
        raise EmailDeliveryError("邮件正文无效")
    if html is not None and (not isinstance(html, str) or len(html) > 500_000):
        raise EmailDeliveryError("邮件 HTML 正文无效")
    sender = Address(addr_spec=config.sender)
    message = EmailMessage()
    message["From"] = Address(display_name=config.sender_name, username=sender.username, domain=sender.domain)
    message["To"] = recipient
    message["Subject"] = subject
    message["Date"] = formatdate(localtime=False, usegmt=True)
    message_id = make_msgid(domain=sender.domain)
    message["Message-ID"] = message_id
    message.set_content(text)
    if html is not None:
        message.add_alternative(html, subtype="html")
    client = None
    try:
        context = ssl.create_default_context()
        if config.security == "ssl":
            client = smtplib.SMTP_SSL(config.host, config.port, timeout=config.timeout, context=context)
        else:
            client = smtplib.SMTP(config.host, config.port, timeout=config.timeout)
            client.ehlo()
            client.starttls(context=context)
            client.ehlo()
        if config.username:
            client.login(config.username, config.password)
        refused = client.send_message(message, from_addr=config.sender, to_addrs=[recipient])
        if refused:
            raise EmailDeliveryError("收件服务器暂未接受邮件，请稍后再试", retryable=True)
    except smtplib.SMTPAuthenticationError as error:
        raise EmailDeliveryError("邮箱授权失败，请检查 SMTP 账号和授权码", retryable=400 <= error.smtp_code < 500) from None
    except smtplib.SMTPRecipientsRefused as error:
        transient = any(400 <= value[0] < 500 for value in error.recipients.values())
        raise EmailDeliveryError("收件邮箱被邮件服务器拒绝，请核对邮箱地址", retryable=transient) from None
    except smtplib.SMTPNotSupportedError:
        raise EmailDeliveryError("邮件服务器不支持所需的加密或认证方式") from None
    except smtplib.SMTPResponseException as error:
        raise EmailDeliveryError("邮件服务器拒绝了投递请求", retryable=400 <= error.smtp_code < 500) from None
    except ssl.SSLCertVerificationError:
        raise EmailDeliveryError("邮件服务器的安全证书验证失败") from None
    except (OSError, socket.timeout, smtplib.SMTPException):
        raise EmailDeliveryError("邮件服务暂时连接失败，请稍后重试", retryable=True) from None
    finally:
        if client is not None:
            # A failed QUIT after successful DATA must not turn acceptance into
            # an apparent failure and trigger a duplicate reminder.
            try:
                client.quit()
            except (OSError, smtplib.SMTPException):
                client.close()
    return {"provider": "smtp", "id": message_id}
