import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

test('merged Flyway versions are unique and preserve test branch migrations', () => {
  const files = readdirSync(new URL('../../src/main/resources/db/migration/', import.meta.url));
  const versions = files.filter(f => /^V\d+__/.test(f)).map(f => f.match(/^V(\d+)__/)[1]);
  assert.equal(versions.length, new Set(versions).size);
  assert.ok(files.includes('V44__add_social_login_identities.sql'));
  assert.ok(files.includes('V47__track_user_registration_source.sql'));
  assert.ok(files.includes('V48__track_image_execution_steps.sql'));
});

test('test workspace theme and collapsed card controls survive telemetry merge', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const app = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
  assert.ok(html.includes('/resend-theme.css'));
  assert.ok(html.includes('/image-progress.css'));
  assert.ok(html.includes('/vendor/qrcode.js'));
  assert.ok(!html.includes('/vendor/liquid-glass.css'));
  assert.ok(app.includes('renderAiVideoCardExpandButton(item, true)'));
  assert.ok(app.includes('renderAiVideoCardExpandButton(item, false)'));
});

test('expired sessions close workspace dialogs and clear the retained password', () => {
  const app = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
  const showLogin = app.match(/function showLogin\([^]*?\n}\n\nasync function showDashboard/)[0];
  assert.ok(showLogin.includes('document.querySelectorAll("dialog[open]").forEach(dialog => dialog.close())'));
  assert.ok(showLogin.includes('passwordInput.value = ""'));
});
