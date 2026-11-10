import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import runtime_recipe

class RuntimeRecipeTests(unittest.TestCase):
    def recipe(self):return dict(jre='sha256:'+'a'*64,platform='linux/amd64',bomSha256='b'*64)
    def test_dependency_changes_invalidate(self):
        recipe=self.recipe()
        self.assertNotEqual(runtime_recipe.recipe_digest(recipe),runtime_recipe.recipe_digest(dict(recipe,bomSha256='c'*64)))
        self.assertEqual(runtime_recipe.recipe_digest(recipe),runtime_recipe.recipe_digest(dict(reversed(list(recipe.items())))))
    def test_mutable_missing_unknown_rejected(self):
        for recipe in [dict(self.recipe(),jre='latest'),dict(self.recipe(),secret='oops'),{'jre':'sha256:'+'a'*64,'platform':'linux/amd64'}]:
            with self.assertRaises(ValueError):runtime_recipe.recipe_digest(recipe)
    def test_unqualified_runtime_does_not_hit_cache(self):
        with self.assertRaises(ValueError):runtime_recipe.qualified_runtime({'images':{'app':{'runtimeBaseDigest':'sha256:'+'f'*64,'runtimeRecipeDigest':'a'*64}}})
    def test_ci_preparation_never_grants_private_registry_access_to_pr(self):
        environment={'GITHUB_REPOSITORY':'Gotit-zhx/ai-video','GITHUB_EVENT_NAME':'pull_request','GITHUB_REF':'refs/pull/123/merge'}
        self.assertFalse(runtime_recipe.ci_runtime_allowed(environment))
        environment.update(GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REF='refs/heads/release/2026.11.10')
        self.assertTrue(runtime_recipe.ci_runtime_allowed(environment))
        environment['GITHUB_REF']='refs/heads/feature/unreviewed'
        self.assertFalse(runtime_recipe.ci_runtime_allowed(environment))
    def test_ci_runtime_import_rejects_wrong_attempt_and_archive_before_docker(self):
        import tempfile,json,hashlib
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'runtime.tar').write_bytes(b'archive')
            proof=dict(runId='1',runAttempt='1',head='a'*40,recipeDigest='b'*64,configDigest='sha256:'+'c'*64,
                       runtimeRef='ghcr.io/gotit-zhx/ai-video/app@sha256:'+'d'*64,archiveSha256=hashlib.sha256(b'archive').hexdigest())
            env={'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'2','GITHUB_SHA':'a'*40}
            with patch.dict(runtime_recipe.os.environ,env),patch.object(runtime_recipe.subprocess,'run') as run, self.assertRaises(ValueError):
                runtime_recipe.load_ci_runtime(root,proof)
            run.assert_not_called()
    def test_ci_runtime_checksum_failure_is_not_a_cache_hit(self):
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'runtime.tar').write_bytes(b'changed')
            proof=dict(runId='1',runAttempt='1',head='a'*40,
                       recipeDigest=runtime_recipe.recipe_digest(runtime_recipe.configured_recipe()),
                       configDigest='sha256:'+'c'*64,runtimeRef='ghcr.io/gotit-zhx/ai-video/app@sha256:'+'d'*64,archiveSha256='f'*64)
            with patch.dict(runtime_recipe.os.environ,{'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40}),patch.object(runtime_recipe.subprocess,'run') as run,self.assertRaisesRegex(ValueError,'checksum'):
                runtime_recipe.load_ci_runtime(root,proof)
            run.assert_not_called()
