"""Offline fixtures for source/JAR correspondence, archive safety and stable local source commits."""
import hashlib
import json
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
import source_snapshot as source


class SourceSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.input = self.root / 'input'; self.input.mkdir()
        self.marker = 'HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java'
        for name, data in {'settings.gradle.kts':'settings', 'build.gradle.kts':'build', 'gradlew':'wrapper', self.marker:'// current source', 'bridge/src/Bridge.java':'// bridge source'}.items():
            file=self.input/name; file.parent.mkdir(parents=True,exist_ok=True);file.write_text(data)
        self.files=source.read_source(self.input); self.jar=self.root/'update.jar';self.write_jar(self.files)

    def write_jar(self, files):
        record={'schema':1,'files':{p:hashlib.sha256(data).hexdigest() for p,data in files.items() if source.code_input(p)}}
        with zipfile.ZipFile(self.jar,'w') as jar:jar.writestr('assets/ochre-source.json',json.dumps(record))

    def test_directory_and_wrapped_zip_match(self):
        self.assertEqual(source.verify(self.jar,self.files),5)
        archive=self.root/'source.zip'
        with zipfile.ZipFile(archive,'w') as out:
            for name,data in self.files.items():out.writestr('OchreCL/'+name,data)
        self.assertEqual(source.read_source(archive),self.files)

    def test_changed_missing_and_extra_code_rejected(self):
        for modify in ('changed','missing','extra'):
            files=dict(self.files)
            if modify=='changed':files[self.marker]=b'// stale'
            elif modify=='missing':del files[self.marker]
            else:files['bridge/src/Extra.java']=b'// unrecorded'
            with self.assertRaisesRegex(RuntimeError,'不匹配'):source.verify(self.jar,files)

    def test_legacy_jar_does_not_silently_use_old_git_head(self):
        with zipfile.ZipFile(self.jar,'w') as out:out.writestr('old.txt','old')
        with self.assertRaisesRegex(RuntimeError,'没有源码校验记录'):source.verify(self.jar,self.files)

    def test_private_state_is_excluded(self):
        (self.input/'.env').write_text('must not publish')
        cache=self.input/'HMCL/build/accounts.json';cache.parent.mkdir(parents=True);cache.write_text('must not publish')
        self.assertEqual(source.read_source(self.input),self.files)

    def test_zip_traversal_and_symlinks_rejected(self):
        for mode in ('traversal','symlink','duplicate'):
            archive=self.root/(mode+'.zip')
            with zipfile.ZipFile(archive,'w') as out:
                for name,data in self.files.items():out.writestr(name,data)
                if mode=='traversal':out.writestr('../escape.txt','bad')
                elif mode=='duplicate':out.writestr(self.marker,'bad')
                else:
                    info=zipfile.ZipInfo('bridge/src/link.java');info.create_system=3;info.external_attr=(stat.S_IFLNK|0o777)<<16;out.writestr(info,'/outside')
            with self.assertRaises(RuntimeError):source.read_source(archive)

    def test_validation_precedes_mutation_and_commit_is_reused(self):
        repo=self.root/'repo';repo.mkdir()
        def git(*args):return subprocess.check_output(['git',*args],cwd=repo,text=True).strip()
        git('init','-b','main');git('config','user.name','Offline fixture');git('config','user.email','fixture@example.invalid')
        (repo/'README.md').write_text('initial');git('add','.');git('commit','-m','Initial fixture')
        initial=git('rev-parse','HEAD');(self.input/self.marker).write_text('// wrong')
        with self.assertRaises(RuntimeError):source.prepare(self.input,self.jar,repo,'0.3.0-alpha.5')
        self.assertEqual(git('rev-parse','HEAD'),initial);self.assertFalse((repo/'HMCL').exists())
        (self.input/self.marker).write_bytes(self.files[self.marker])
        head=source.prepare(self.input,self.jar,repo,'0.3.0-alpha.5');self.assertNotEqual(head,initial)
        self.assertEqual(source.prepare(self.input,self.jar,repo,'0.3.0-alpha.5'),head);self.assertEqual(git('status','--porcelain'),'')


if __name__=='__main__':unittest.main()
