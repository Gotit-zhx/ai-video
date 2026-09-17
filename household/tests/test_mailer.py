"""Mail transport tests use mocks only; no message is sent to any address."""
import os
from pathlib import Path
import smtplib
import ssl
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mailer


ENV = {
    "SMTP_HOST": "smtp.example.com", "SMTP_PORT": "465",
    "SMTP_USER": "sender@example.com", "SMTP_PASSWORD": "fake-test-authorization",
    "MAIL_FROM": "sender@example.com", "SMTP_SECURITY": "ssl",
}


class MailerTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, ENV, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.client = MagicMock()
        self.client.send_message.return_value = {}

    def send(self):
        return mailer.send_email("recipient@example.com", "还有两项家务", "今晚还有两项。", "<p>今晚还有两项。</p>")

    def test_status_checks_do_not_contact_smtp_or_expose_credentials(self):
        with patch.object(mailer.smtplib, "SMTP_SSL") as smtp:
            self.assertTrue(mailer.mail_available())
            self.assertEqual(mailer.email_delivery_status(), {"configured": True, "provider": "smtp", "fromAddress": ENV["MAIL_FROM"]})
            self.assertNotIn(ENV["SMTP_PASSWORD"], repr(mailer._config()))
            smtp.assert_not_called()
        del os.environ["SMTP_PASSWORD"]
        self.assertFalse(mailer.mail_available())

    def test_ssl_message_contains_text_html_and_exact_envelope(self):
        with patch.object(mailer.smtplib, "SMTP_SSL", return_value=self.client) as smtp:
            result = self.send()
        self.assertEqual(result["provider"], "smtp")
        self.assertIn("@example.com>", result["id"])
        self.assertEqual(smtp.call_args.kwargs["context"].verify_mode, ssl.CERT_REQUIRED)
        self.client.login.assert_called_once_with(ENV["SMTP_USER"], ENV["SMTP_PASSWORD"])
        message = self.client.send_message.call_args.args[0]
        self.assertEqual(str(message["Subject"]), "还有两项家务")
        self.assertEqual(message.get_content_type(), "multipart/alternative")
        self.assertEqual(message.get_body(preferencelist=("plain",)).get_content().strip(), "今晚还有两项。")
        self.assertEqual(self.client.send_message.call_args.kwargs, {"from_addr": "sender@example.com", "to_addrs": ["recipient@example.com"]})

    def test_starttls_completes_before_login(self):
        os.environ.update(SMTP_PORT="587", SMTP_SECURITY="starttls")
        with patch.object(mailer.smtplib, "SMTP", return_value=self.client):
            self.send()
        methods = [call[0] for call in self.client.method_calls]
        self.assertEqual(methods[:4], ["ehlo", "starttls", "ehlo", "login"])

    def test_plain_transport_and_invalid_addresses_are_rejected_without_network(self):
        with patch.object(mailer.smtplib, "SMTP_SSL") as smtp:
            for address in ("a@example..com", "@bad", "a@b.com,b@c.com", "a@b.com\r\nBcc: other@b.com"):
                with self.assertRaises(mailer.EmailDeliveryError):
                    mailer.send_email(address, "提醒", "正文")
            with self.assertRaises(mailer.EmailDeliveryError):
                mailer.send_email("recipient@example.com", "提醒\r\nBcc: other@b.com", "正文")
            smtp.assert_not_called()
        os.environ["SMTP_SECURITY"] = "none"
        self.assertFalse(mailer.mail_available())

    def test_authentication_error_is_safe_and_not_retryable(self):
        self.client.login.side_effect = smtplib.SMTPAuthenticationError(535, b"sensitive server response fake-test-authorization")
        with patch.object(mailer.smtplib, "SMTP_SSL", return_value=self.client):
            with self.assertRaises(mailer.EmailDeliveryError) as caught:
                self.send()
        self.assertFalse(caught.exception.retryable)
        self.assertNotIn("sensitive", str(caught.exception))
        self.assertNotIn(ENV["SMTP_PASSWORD"], str(caught.exception))
        self.client.send_message.assert_not_called()

    def test_network_failure_is_retryable_without_raw_error(self):
        with patch.object(mailer.smtplib, "SMTP_SSL", side_effect=TimeoutError("sensitive host details")):
            with self.assertRaises(mailer.EmailDeliveryError) as caught:
                self.send()
        self.assertTrue(caught.exception.retryable)
        self.assertNotIn("sensitive", str(caught.exception))

    def test_recipient_rejection_classifies_permanent_vs_temporary(self):
        for status, retryable in ((550, False), (450, True)):
            self.client.send_message.side_effect = smtplib.SMTPRecipientsRefused({"recipient@example.com": (status, b"private response")})
            with patch.object(mailer.smtplib, "SMTP_SSL", return_value=self.client):
                with self.assertRaises(mailer.EmailDeliveryError) as caught:
                    self.send()
            self.assertEqual(caught.exception.retryable, retryable)
            self.assertNotIn("private response", str(caught.exception))

    def test_quit_failure_does_not_repeat_an_accepted_email(self):
        self.client.quit.side_effect = smtplib.SMTPServerDisconnected("connection closed")
        with patch.object(mailer.smtplib, "SMTP_SSL", return_value=self.client):
            self.assertEqual(self.send()["provider"], "smtp")
        self.client.send_message.assert_called_once()
        self.client.close.assert_called_once()


if __name__ == "__main__":
    unittest.main(verbosity=2)
