import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
LEGACY = ROOT / "docs" / "history" / "legacy-release-workflows"


class DevelopmentComposeTest(unittest.TestCase):
    def render_compose(self):
        environment = os.environ.copy()
        environment.update(
            MYSQL_PASSWORD="mysql-test-password",
            MYSQL_ROOT_PASSWORD="mysql-root-test-password",
            REDIS_PASSWORD="redis-test-password",
        )
        with tempfile.NamedTemporaryFile("w", delete=False) as env_file:
            env_file.write(
                "MYSQL_PASSWORD=mysql-test-password\n"
                "MYSQL_ROOT_PASSWORD=mysql-root-test-password\n"
                "REDIS_PASSWORD=redis-test-password\n"
            )
            env_path = env_file.name
        self.addCleanup(Path(env_path).unlink, missing_ok=True)
        result = subprocess.run(
            [
                "docker",
                "compose",
                "--env-file",
                env_path,
                "-f",
                str(ROOT / "compose.dev.yaml"),
                "config",
                "--format",
                "json",
            ],
            cwd=ROOT,
            env=environment,
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(result.stdout)

    def test_default_stack_renders_only_isolated_mysql_and_redis(self):
        config = self.render_compose()

        self.assertEqual({"mysql", "redis"}, set(config["services"]))
        for service in config["services"].values():
            self.assertNotIn("build", service)
            self.assertNotIn("container_name", service)
        self.assertEqual("mysql:8.4", config["services"]["mysql"]["image"])
        self.assertEqual("redis:8-alpine", config["services"]["redis"]["image"])
        self.assertEqual({"mysql_data", "redis_data"}, set(config["volumes"]))

    def test_dependency_ports_are_loopback_only_and_use_development_defaults(self):
        config = self.render_compose()

        mysql_port = config["services"]["mysql"]["ports"][0]
        redis_port = config["services"]["redis"]["ports"][0]
        self.assertEqual(("127.0.0.1", "3307", 3306),
                         (mysql_port["host_ip"], mysql_port["published"], mysql_port["target"]))
        self.assertEqual(("127.0.0.1", "6380", 6379),
                         (redis_port["host_ip"], redis_port["published"], redis_port["target"]))
        self.assertEqual("ai_commerce_dev", config["services"]["mysql"]["environment"]["MYSQL_DATABASE"])
        self.assertEqual("devuser", config["services"]["mysql"]["environment"]["MYSQL_USER"])

    def test_healthchecks_authenticate_without_putting_passwords_in_cli_arguments(self):
        config = self.render_compose()
        mysql_health = config["services"]["mysql"]["healthcheck"]["test"][-1]
        redis_health = config["services"]["redis"]["healthcheck"]["test"][-1]

        self.assertIn("MYSQL_PWD=", mysql_health)
        self.assertIn("--protocol=TCP", mysql_health)
        self.assertIn("SELECT 1", mysql_health)
        self.assertNotIn("-p$", mysql_health)
        self.assertIn("REDISCLI_AUTH=", redis_health)
        self.assertNotIn("redis-cli -a", redis_health)
        self.assertIn("redis-cli -h 127.0.0.1 ping", redis_health)
        self.assertNotIn("redis-cli --host", redis_health)
        self.assertIn("ping", redis_health)


class DevelopmentWorkflowTest(unittest.TestCase):
    def test_active_workflows_do_not_automatically_package_or_deploy_core_app(self):
        active = {path.name for path in WORKFLOWS.glob("*.yml")}
        self.assertEqual(
            {"development-checks.yml", "hr-desktop.yml", "operations.yml"},
            active,
        )
        development = (WORKFLOWS / "development-checks.yml").read_text()
        forbidden = (
            "schedule:",
            "workflow_call:",
            "docker build",
            "setup-buildx",
            "upload-artifact",
            "ghcr.io",
            "mvn package",
            "spring-boot:build-image",
            "scripts/ci/candidate.py",
            "scripts/ci/promote.py",
            "scripts/ci/remote.py",
        )
        for value in forbidden:
            with self.subTest(value=value):
                self.assertNotIn(value, development)
        self.assertIn("python3 -m unittest discover -s scripts/tests -p 'test_*.py'", development)
        self.assertIn("node --test frontend/test/dev-live.test.cjs", development)
        self.assertIn("node --test frontend/test/public-assets.cjs", development)
        self.assertIn("-Dtest=DevRevisionControllerTest,DevWebConfigurationTest,DevWebResourceSecurityTest,MaintenanceGateTest", development)
        self.assertIn(" test", development)

    def test_formal_core_release_workflows_are_archived_as_history(self):
        self.assertEqual(
            {"candidate.yml", "ci.yml", "deploy.yml", "release-ready.yml"},
            {path.name for path in LEGACY.glob("*.yml")},
        )

    def test_desktop_packaging_requires_explicit_dispatch(self):
        desktop = (WORKFLOWS / "hr-desktop.yml").read_text()
        trigger = desktop.split("concurrency:", 1)[0]
        self.assertIn("workflow_dispatch:", trigger)
        self.assertNotIn("push:", trigger)
        self.assertNotIn("hr-desktop-build-", desktop)

    def test_active_operations_workflow_is_read_only(self):
        operations = (WORKFLOWS / "operations.yml").read_text()
        self.assertIn("notify.py", operations)
        self.assertIn("ref: develop", operations)
        self.assertIn("options: [doctor, notification-test]", operations)
        self.assertNotIn("main-tree", operations)
        self.assertNotIn("release-check", operations)
        self.assertNotIn("host_status.py", operations)
        self.assertNotIn("rollback:", operations)
        self.assertNotIn("environment: production", operations)
        self.assertNotIn("scripts/ci/remote.py", operations)


class DevelopmentEnvironmentTemplateTest(unittest.TestCase):
    def test_template_documents_dependency_contract_without_real_secrets(self):
        template = (ROOT / ".env.development.example").read_text()
        expected = {
            "DEV_MYSQL_PORT": "3307",
            "DEV_REDIS_PORT": "6380",
            "MYSQL_DATABASE": "ai_commerce_dev",
            "MYSQL_USER": "devuser",
            "DB_USERNAME": "devuser",
            "REDIS_HOST": "127.0.0.1",
        }
        values = {}
        for line in template.splitlines():
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
        for key, value in expected.items():
            self.assertEqual(value, values[key])
        for key in ("MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "REDIS_PASSWORD", "DB_PASSWORD"):
            self.assertIn("replace-with-", values[key])
        for key in (
            "APP_ADMIN_PASSWORD",
            "APP_MODEL_SECRET_KEY",
            "APP_SHOP_CREDENTIAL_KEY",
            "APP_AUTOMATION_AGENT_TOKEN",
            "APP_PI_RUNTIME_TOKEN",
            "APP_VIDEO_REFERENCE_LINK_SECRET",
        ):
            self.assertIn("replace-with-", values[key])
        self.assertEqual("false", values["APP_DISPATCH_ENABLED"])
        self.assertIn("jdbc:mysql://127.0.0.1:3307/ai_commerce_dev", values["DB_URL"])


if __name__ == "__main__":
    unittest.main()
