"""Offline contract checks for publication ordering and website links; no network calls."""
import argparse
import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import full_packages
import publish_update
import source_snapshot

BASE = Path(__file__).resolve().parents[2]


class WorkflowTest(unittest.TestCase):
    def workflow(self, corrupt=False, site_push_fails=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); site = root / 'site'; source = root / 'source'; source.mkdir()
            (site / 'ochrecl/assets').mkdir(parents=True); (site / 'ochrecl/docs').mkdir()
            original = (BASE / 'website-publish/ochrecl/index.html').read_text()
            (site / 'ochrecl/index.html').write_text(original)
            (site / 'ochrecl/docs/index.html').write_text('Version 0.3.0-alpha.2')
            config = root / 'config.json'; config.write_text(json.dumps({'source': str(source), 'website': str(site), 'gh': '/mock/gh', 'base': str(BASE)}))
            notes = root / 'notes.txt'; notes.write_text('Offline test only')
            folder = root / 'packages'; folder.mkdir()
            names = ['OchreCL-0.3.0-alpha.3-' + platform for platform in ('macos-arm64.dmg', 'macos-x64.dmg', 'windows-x64.zip', 'linux-x64.tar.gz')]
            names += ['OchreCL-update.jar', 'OchreCL-source.zip']
            files = [folder / n for n in names]
            for file in files: file.write_bytes(file.name.encode())
            sums = folder / 'SHA256SUMS.txt'; sums.write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest() + '  ' + f.name + '\n' for f in files)); files += [sums]
            state = {'release': None, 'calls': []}
            def fake(*args, cwd=None):
                state['calls'].append(args)
                if args[:3] == ('git', 'rev-parse', 'HEAD'): return '1234567890abcdef'
                if args[:3] == ('git', 'ls-remote', '--tags'): return ''
                if args[:3] == ('git', 'status', '--porcelain'): return ' M ochrecl/index.html'
                if args[:3] == ('/mock/gh', 'api', 'user'): return 'Fishyra'
                if args[:2] == ('/mock/gh', 'api'):
                    endpoint = args[2]
                    if endpoint.endswith('/git/ref/heads/main'): return json.dumps({'object': {'sha': '1234567890abcdef'}})
                    if 'releases?' in endpoint: return json.dumps([state['release']] if state['release'] else [])
                    if '/releases/tags/' in endpoint: return json.dumps(state['release'])
                if args[:3] == ('/mock/gh', 'release', 'create'):
                    upload = args[4:args.index('--repo')]
                    state['release'] = {'tag_name': 'v0.3.0-alpha.3', 'draft': True, 'target_commitish': '1234567890abcdef', 'html_url': 'https://example.invalid/offline-only', 'assets': [{'name': Path(p).name, 'digest': 'sha256:' + hashlib.sha256(Path(p).read_bytes()).hexdigest()} for p in upload]}
                    if corrupt: state['release']['assets'][0]['digest'] = 'sha256:' + '0' * 64
                    return ''
                if args[:3] == ('/mock/gh', 'release', 'edit'):
                    self.assertEqual((site / 'ochrecl/index.html').read_text(), original)
                    state['release']['draft'] = False; return ''
                if args[:3] == ('git', 'push', 'origin') and site_push_fails and sum(c[:3] == ('git', 'push', 'origin') for c in state['calls']) == 2: raise RuntimeError('offline website push failed')
                if args[0] == 'git': return ''
                raise AssertionError(args)
            args = argparse.Namespace(source=str(source), config=str(config), jar=str(BASE / 'builds/0.3.0-alpha.2/OchreCL-update.jar'), version='0.3.0-alpha.3', notes=str(notes), full=True, publish=True, cleanup_platforms=True)
            with patch.object(full_packages, 'cleanup_platform_assets', return_value=4) as cleanup, patch.object(source_snapshot, 'prepare', return_value='1234567890abcdef'), patch.object(publish_update, 'command', side_effect=fake), patch.object(publish_update, 'clean_repo'), patch.object(full_packages, 'build', return_value=files), contextlib.redirect_stdout(io.StringIO()):
                if site_push_fails:
                    with self.assertRaisesRegex(RuntimeError, 'website push failed'): publish_update.publish(args)
                    cleanup.assert_not_called()
                    self.assertTrue(all(file.exists() for file in files))
                elif corrupt:
                    with self.assertRaisesRegex(RuntimeError, '附件与本次不一致'): publish_update.publish(args)
                    self.assertTrue(state['release']['draft'])
                    self.assertEqual((site / 'ochrecl/index.html').read_text(), original)
                    self.assertFalse(any(c[:3] == ('/mock/gh', 'release', 'edit') for c in state['calls'])); cleanup.assert_not_called()
                else:
                    publish_update.publish(args)
                    self.assertFalse(state['release']['draft'])
                    self.assertEqual(len(state['release']['assets']), 7)
                    page = (site / 'ochrecl/index.html').read_text()
                    self.assertEqual(page.count('/releases/download/v0.3.0-alpha.3/'), 4)
                    self.assertNotIn('/releases/download/v0.3.0-alpha.2/', page)
                    self.assertEqual((site / 'ochrecl/assets/SHA256SUMS.txt').read_bytes(), sums.read_bytes())
                    self.assertIn(('git', 'add', 'ochrecl/index.html', 'ochrecl/docs/index.html', 'ochrecl/assets/SHA256SUMS.txt'), state['calls']); cleanup.assert_called_once()
    def test_website_failure_keeps_local_installers(self): self.workflow(site_push_fails=True)
    def test_full_release_then_website(self): self.workflow()
    def test_digest_mismatch_retains_draft_and_website(self): self.workflow(corrupt=True)


if __name__ == '__main__': unittest.main()
