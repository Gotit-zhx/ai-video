"""Publish only Seedance video classes/assets over the exact current production image."""
import importlib.util
from pathlib import Path
import sys

source = Path("/opt/heyi/releases/finance-settled-goods-20261026/release-finance-settled-goods.py")
spec = importlib.util.spec_from_file_location("seedance_release_base", source)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
release.PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/"
release.FAMILIES = {
    "video/application/provider/DayanVideoGenerationProvider",
    "video/application/provider/ProviderJsonSupport",
    "video/application/AiVideoGenerationService",
    "video/application/AiVideoTaskService",
    "video/domain/AiVideoTask",
    "saas/application/ModelConfigService",
}
release.FILES = {"index.html", "app.js"}
release.TAG = "seedance-mini-20261027"


def configure():
    """Change only model IDs; endpoint, keys, tenant, defaults and accepted jobs stay intact."""
    sql = release.base.sql
    rows = sql("SELECT id,organization_id,base_url,model_id,default_model FROM agent_model_configs "
               "WHERE model_type='VIDEO' AND provider='DAYAN' AND status='ACTIVE' "
               "ORDER BY organization_id,base_url,default_model DESC,updated_at DESC")
    rollback = []
    statements = []
    selected = set()
    for row in rows.splitlines():
        identifier, organization, base_url, model, default = row.split("\t")
        assert all(c in "0123456789abcdef-" for c in identifier)
        old = "CONVERT(0x" + model.encode().hex() + " USING utf8mb4)"
        group = (organization, base_url)
        if group not in selected:
            selected.add(group)
            statements.append("UPDATE agent_model_configs SET model_id='doubao-seedance-2-0-mini-260615' "
                              "WHERE id='" + identifier + "' AND model_id=" + old + " AND status='ACTIVE';")
            rollback.append("UPDATE agent_model_configs SET model_id=" + old + " WHERE id='" + identifier
                            + "' AND model_id='doubao-seedance-2-0-mini-260615';")
        else:
            statements.append("UPDATE agent_model_configs SET status='DISABLED',default_model=0 WHERE id='"
                              + identifier + "' AND model_id=" + old + " AND status='ACTIVE';")
            rollback.append("UPDATE agent_model_configs SET status='ACTIVE',default_model=" + default
                            + " WHERE id='" + identifier + "' AND model_id=" + old + " AND status='DISABLED';")
    path = release.ROOT / "rollback-video-models-deduplicated.sql"
    assert not path.exists(), "Config migration already performed"
    path.write_text("\n".join(rollback) + "\n")
    path.chmod(0o600)
    sql("START TRANSACTION;" + "".join(statements) + "COMMIT;")
    assert sql("SELECT COUNT(*) FROM agent_model_configs WHERE model_type='VIDEO' AND status='ACTIVE' "
               "AND (provider<>'DAYAN' OR model_id<>'doubao-seedance-2-0-mini-260615')") == "0"
    print("Active video configs switched to Seedance Mini; base URLs and credentials unchanged.")


if __name__ == "__main__":
    {"snapshot": release.snapshot, "build": release.build, "verify": release.verify,
     "drain-deploy": release.base.drain_deploy, "configure": configure}[sys.argv[2]]()
