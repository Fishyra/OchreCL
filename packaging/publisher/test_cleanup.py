"""Cleanup is allowed only for verified files in this tool's package cache, never inputs or templates."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout
import io
import full_packages


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name);self.version='0.3.0-alpha.99'
        self.folder=self.base/'builds/publisher-packages'/('0.3.0-alpha.99-fixture');self.folder.mkdir(parents=True)
        self.platforms=[f'OchreCL-{self.version}-{suffix}' for suffix in ('macos-arm64.dmg','macos-x64.dmg','windows-x64.zip','linux-x64.tar.gz')]
        self.names=self.platforms+['OchreCL-update.jar','OchreCL-source.zip']
        for name in self.names:(self.folder/name).write_bytes(name.encode())
        self.hashes={name:hashlib.sha256((self.folder/name).read_bytes()).hexdigest() for name in self.names}
        (self.folder/'verified.json').write_text(json.dumps({'identity':{'version':self.version},'assets':self.hashes}))
        (self.folder/'SHA256SUMS.txt').write_text('retained checksum record')
        self.assets=[self.folder/name for name in self.names]
        template=self.base/'release-work/mac-runtime/keep.txt';template.parent.mkdir(parents=True);template.write_text('runtime template')
        self.template=template

    def clean(self):
        with redirect_stdout(io.StringIO()):return full_packages.cleanup_platform_assets({'base':str(self.base)},self.assets)

    def test_only_four_installers_removed_and_repeat_is_safe(self):
        self.assertEqual(self.clean(),4)
        for name in self.platforms:self.assertFalse((self.folder/name).exists())
        for name in ['OchreCL-update.jar','OchreCL-source.zip','SHA256SUMS.txt','verified.json']:self.assertTrue((self.folder/name).is_file())
        self.assertTrue(self.template.exists());self.assertEqual(self.clean(),0)

    def test_hash_mismatch_prevents_any_deletion(self):
        (self.folder/self.platforms[-1]).write_text('changed')
        with self.assertRaisesRegex(RuntimeError,'不一致'):self.clean()
        self.assertTrue(all((self.folder/name).exists() for name in self.platforms))

    def test_input_source_mismatch_prevents_any_deletion(self):
        (self.folder/'OchreCL-source.zip').write_text('changed')
        with self.assertRaises(RuntimeError):self.clean()
        self.assertTrue(all((self.folder/name).exists() for name in self.platforms))

    def test_symlink_and_outside_folder_refused(self):
        outside=self.base/'outside';outside.mkdir();sentinel=outside/'keep';sentinel.write_text('keep')
        link=self.folder/self.platforms[0];link.unlink();link.symlink_to(sentinel)
        with self.assertRaises(RuntimeError):self.clean()
        with self.assertRaises(RuntimeError):full_packages.cleanup_platform_assets({'base':str(self.base)},[sentinel])
        self.assertEqual(sentinel.read_text(),'keep')


if __name__=='__main__':unittest.main()
