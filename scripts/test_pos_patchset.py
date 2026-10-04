"""Fixture-only patch workflow checks. No device, network or signing keys are used."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('pos_patchset', Path(__file__).with_name('pos_patchset.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PatchsetTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='pos-patch-fixture-')
        self.root = Path(self.directory.name); self.repo = self.root/'repo'; self.repo.mkdir()
        module.git(self.repo,'init')
        module.git(self.repo,'config','user.name','Patch Fixture')
        module.git(self.repo,'config','user.email','fixture@example.invalid')
        # Synthetic fixture commits are not release commits and require no private key.
        module.git(self.repo,'config','commit.gpgsign','false')
        (self.repo/'README').write_text('seed\n'); self.save('seed'); self.seed=module.commit(self.repo,'HEAD')
        (self.repo/'app.txt').write_text('upstream\n'); self.save('baseline'); self.base=module.commit(self.repo,'HEAD')
        module.git(self.repo,'switch','-c','feature')
        (self.repo/'app.txt').write_text('POS\n'); (self.repo/'driver.txt').write_text('adapter\n'); self.save('add adapter')
        (self.repo/'binary.dat').write_bytes(bytes(range(256))); self.save('add binary asset'); self.target=module.commit(self.repo,'HEAD')
        self.bundle=self.root/'bundle'; module.export(self.repo,self.base,'HEAD',self.bundle)
        module.git(self.repo,'switch','-c','upstream-next',self.base)
        (self.repo/'README').write_text('new upstream docs\n'); self.save('upstream update')
    def tearDown(self): self.directory.cleanup()
    def save(self,message):
        module.git(self.repo,'add','.'); module.git(self.repo,'commit','-m',message)
    def test_replay_preserves_new_upstream_and_binary_assets(self):
        module.apply(self.repo,self.bundle,'upstream-next','pos-port')
        self.assertEqual((self.repo/'README').read_text(),'new upstream docs\n')
        self.assertEqual((self.repo/'binary.dat').read_bytes(),bytes(range(256)))
        module.git(self.repo,'diff','--exit-code',self.target,'--','app.txt','driver.txt','binary.dat')
    def test_dirty_checkout_is_not_reset(self):
        (self.repo/'local.txt').write_text('keep me\n'); current=module.commit(self.repo,'HEAD')
        with self.assertRaises(module.PatchError): module.apply(self.repo,self.bundle,'upstream-next','pos-port')
        self.assertEqual(module.commit(self.repo,'HEAD'),current)
        self.assertEqual((self.repo/'local.txt').read_text(),'keep me\n')
    def test_older_lineage_is_rejected_before_creating_branch(self):
        with self.assertRaises(module.PatchError): module.apply(self.repo,self.bundle,self.seed,'pos-old')
        self.assertNotEqual(module.git(self.repo,'show-ref','--verify','--quiet','refs/heads/pos-old',check=False).returncode,0)
    def test_existing_branch_is_not_reused(self):
        with self.assertRaises(module.PatchError): module.apply(self.repo,self.bundle,'upstream-next','feature')
        self.assertEqual(module.commit(self.repo,'feature'),self.target)
    def test_conflict_remains_reviewable_on_new_branch(self):
        (self.repo/'app.txt').write_text('different upstream\n'); self.save('incompatible upstream edit')
        with self.assertRaises(module.PatchError): module.apply(self.repo,self.bundle,'upstream-next','pos-conflict')
        self.assertEqual(module.git(self.repo,'branch','--show-current').stdout.strip(),'pos-conflict')
        self.assertIn('UU app.txt',module.git(self.repo,'status','--porcelain').stdout)

if __name__=='__main__': unittest.main()
