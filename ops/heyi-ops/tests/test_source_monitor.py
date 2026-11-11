import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from heyi_ops import monitor, tls
from heyi_ops.state import Store, atomic
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'scripts/ci'))
import notify


class SourceMonitorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source = self.root / 'source'
        self.source.mkdir()
        self.runtime = self.root / 'runtime.json'
        atomic(self.runtime, {'sourceRoot': str(self.source)})
        self.journal = self.source / '.dev/shared-reload.json'
        self.now = 2000000000
        self.owner = 'a' * 32
        self.state = {'owner': self.owner, 'phase': 'compiling',
                      'createdAt': self.now - 30, 'updatedAt': self.now - 5}
        atomic(self.journal, self.state)
        self.host = Mock()
        self.host.marker = self.root / 'control/maintenance.json'
        atomic(self.host.marker, {'owner': self.owner, 'createdAt': self.now - 29})
        self.host.request.return_value = (503, b'{}')
        self.host.counters.return_value = {'jobs': 0}
        self.host.sql.return_value = '0'
        self.store = Store(self.root, self.root / 'lock')
        self.config = {'root': str(self.root), 'backupRoot': str(self.root / 'backup'),
                       'permanentContainers': ['ai-commerce-app', 'ai-commerce-frontend']}
        patches = [
            patch.object(monitor, 'SOURCE_RUNTIME_CONFIG', self.runtime, create=True),
            patch.object(monitor.time, 'time', return_value=self.now),
            patch.object(monitor.shutil, 'disk_usage', return_value=SimpleNamespace(free=100 * monitor.GIB)),
            patch.object(monitor, 'inspect', side_effect=lambda name: {
                'State': {'Running': True, 'OOMKilled': False, 'Health': {'Status': 'unhealthy'}},
                'Config': {'Image': 'fixture'}, 'Image': 'fixture'}),
            patch.object(tls, 'observe', return_value={'expiresAt': self.now + 90 * 86400, 'matches': True}),
        ]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)

    def diagnose(self):
        return monitor.diagnose(self.config, self.store, self.host)

    def test_owned_source_reload_suppresses_only_application_availability(self):
        self.host.sql.side_effect = RuntimeError('database unavailable')
        with patch.object(monitor.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)), \
                patch.object(tls, 'observe', side_effect=RuntimeError('certificate unavailable')):
            result = self.diagnose()
        self.assertTrue(result['plannedMaintenance'])
        alerts = {(a['service'], a['type']) for a in result['alerts']}
        self.assertNotIn(('app', 'availability'), alerts)
        self.assertNotIn(('ai-commerce-app', 'unhealthy'), alerts)
        for alert in [('ai-commerce-frontend', 'unhealthy'), ('database', 'probe-failed'),
                      ('disk', 'free-space'), ('tls', 'unreadable')]:
            self.assertIn(alert, alerts)
        self.assertNotIn('app', result['verifiedScopes'])

    def test_foreign_or_invalid_owner_never_suppresses_availability(self):
        for owner in ('b' * 32, '../foreign', '', None):
            with self.subTest(owner=owner):
                atomic(self.journal, dict(self.state, owner=owner))
                result = self.diagnose()
                self.assertFalse(result['plannedMaintenance'])
                self.assertIn('availability', [a['type'] for a in result['alerts']])

    def test_source_maintenance_does_not_hide_application_oom(self):
        with patch.object(monitor, 'inspect', return_value={
                'State': {'Running': False, 'OOMKilled': True},
                'Config': {'Image': 'fixture'}, 'Image': 'fixture'}):
            result = self.diagnose()
        self.assertTrue(result['plannedMaintenance'])
        self.assertIn(('ai-commerce-app', 'unhealthy'),
                      {(a['service'], a['type']) for a in result['alerts']})

    def test_terminal_or_unknown_phase_never_suppresses_availability(self):
        for phase in ('ready', 'recovered', 'unexpected'):
            with self.subTest(phase=phase):
                atomic(self.journal, dict(self.state, phase=phase))
                self.assertFalse(self.diagnose()['plannedMaintenance'])

    def test_completed_reload_verifies_source_scope_so_stalled_incident_can_close(self):
        atomic(self.journal, dict(self.state, phase='recovered'))
        self.host.marker.unlink()
        result = self.diagnose()
        self.assertFalse(result['plannedMaintenance'])
        self.assertIn('source-development', result['verifiedScopes'])
        self.assertNotIn('source_reload_stalled', [a['type'] for a in result['alerts']])

    def test_existing_notifier_suppresses_external_failure_only_during_valid_window(self):
        remote = Mock()
        remote.__enter__ = Mock(return_value=remote)
        remote.__exit__ = Mock(return_value=False)
        for updated, suppressed in ((self.now - 5, True), (self.now - 301, False)):
            with self.subTest(updated=updated):
                atomic(self.journal, dict(self.state, createdAt=self.now - 400, updatedAt=updated))
                remote.call.return_value = self.diagnose()
                with patch.object(notify, 'Remote', return_value=remote), \
                        patch.object(notify.urllib.request, 'urlopen', side_effect=OSError('restarting')), \
                        patch.object(notify, 'reconcile') as reconcile, \
                        patch.dict(notify.os.environ, {'HEYI_NOTIFICATION_TEST': 'false'}), \
                        contextlib.redirect_stdout(io.StringIO()):
                    notify.main()
                alerts, verified = reconcile.call_args.args
                services = {alert['service'] for alert in alerts}
                self.assertEqual(suppressed, 'external' not in services)
                self.assertIn('ai-commerce-frontend', services)
                if suppressed: self.assertNotIn('external', verified)

    def test_stale_or_overlong_reload_alerts_and_does_not_hide_application_failure(self):
        for fields in ({'updatedAt': self.now - 301, 'createdAt': self.now - 400},
                       {'createdAt': self.now - 1201}, {'phase': 'needs_attention'}):
            with self.subTest(fields=fields):
                atomic(self.journal, dict(self.state, **fields))
                result = self.diagnose()
                self.assertFalse(result['plannedMaintenance'])
                alerts = {a['type'] for a in result['alerts']}
                self.assertIn('source_reload_stalled', alerts)
                self.assertIn('availability', alerts)

    def test_invalid_time_order_or_future_times_never_authorize_suppression(self):
        for fields in ({'createdAt': self.now + 1}, {'updatedAt': self.now + 1},
                       {'updatedAt': self.now - 31}, {'createdAt': True}, {'createdAt': None}):
            with self.subTest(fields=fields):
                atomic(self.journal, dict(self.state, **fields))
                self.assertFalse(self.diagnose()['plannedMaintenance'])
        atomic(self.journal, self.state)
        atomic(self.host.marker, {'owner': self.owner, 'createdAt': self.now + 1})
        self.assertFalse(self.diagnose()['plannedMaintenance'])

    def test_missing_malformed_or_redirected_source_journal_never_suppresses(self):
        self.journal.unlink()
        self.assertFalse(self.diagnose()['plannedMaintenance'])
        self.journal.write_text('{broken')
        self.assertFalse(self.diagnose()['plannedMaintenance'])
        self.journal.unlink()
        outside = self.root / 'outside.json'
        atomic(outside, self.state)
        self.journal.symlink_to(outside)
        self.assertFalse(self.diagnose()['plannedMaintenance'])

    def test_source_root_must_be_absolute_and_canonical(self):
        linked = self.root / 'linked-source'
        linked.symlink_to(self.source, target_is_directory=True)
        for root in ('relative/source', str(linked), str(self.source / '..' / 'source')):
            with self.subTest(root=root):
                atomic(self.runtime, {'sourceRoot': root})
                self.assertFalse(self.diagnose()['plannedMaintenance'])


if __name__ == '__main__':
    unittest.main()
