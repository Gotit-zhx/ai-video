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
