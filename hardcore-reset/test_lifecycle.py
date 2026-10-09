import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import lifecycle as subject

class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='hardcore-safety-')
        self.root = Path(self.tmp.name)
        (self.root / 'hardcore-reset').mkdir()
        shutil.copy(Path(__file__).with_name('ReadProperties.class'), self.root / 'hardcore-reset')
        self.properties('world')
        self.world = self.root / 'world'
        self.world.mkdir()
        (self.world / 'playerdata').mkdir()
        (self.world / 'playerdata' / 'inventory').write_text('lost')
        (self.root / 'keep.txt').write_text('keep')
        (self.root / '.reset_world').write_text(str(self.world) + '\n')

    def tearDown(self):
        self.tmp.cleanup()

    def properties(self, name, seed=''):
        (self.root / 'server.properties').write_text(f'level-name={name}\nlevel-seed={seed}\n')

    def test_reset_only_target_and_marker(self):
        subject.reset_world(self.root)
        self.assertFalse(self.world.exists())
        self.assertFalse((self.root / '.reset_world').exists())
        self.assertEqual((self.root / 'keep.txt').read_text(), 'keep')
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ['hardcore-reset', 'keep.txt', 'server.properties'])

    def test_missing_world(self):
        shutil.rmtree(self.world)
        subject.reset_world(self.root)
        self.assertFalse((self.root / '.reset_world').exists())

    def test_default(self):
        (self.root / 'server.properties').write_text('# no level-name\n')
        self.assertEqual(subject.read_world(self.root), self.world)

    def test_dangerous_names(self):
        for name in ['', '/', '.', '..', '*', '/tmp/x', '../x', 'x/../world',
                     'mods', 'libraries', 'hardcore-reset', '.hidden', 'world/', 'world.',
                     r'\u002e\u002e', r'\u002f', r'\u002a']:
            with self.subTest(name=name):
                self.properties(name)
                with self.assertRaises(ValueError): subject.reset_world(self.root)
                self.assertTrue(self.world.exists())
                self.assertTrue((self.root / '.reset_world').exists())

    def test_java_properties_duplicates_escapes_and_continuation(self):
        (self.root / 'server.properties').write_text('level-name: ignored\nlevel\\-name = wo\\\n  rld\n')
        self.assertEqual(subject.read_world(self.root), self.world)

    def test_fixed_seed_refused(self):
        self.properties('world', '123')
        with self.assertRaises(ValueError): subject.reset_world(self.root)

    def test_changed_world_refused(self):
        self.properties('other')
        with self.assertRaises(ValueError): subject.reset_world(self.root, self.world)
        self.assertTrue(self.world.exists())

    def test_marker_mismatch_and_empty_refused(self):
        for content in ['', '/tmp/other\n']:
            (self.root / '.reset_world').write_text(content)
            with self.assertRaises(ValueError): subject.reset_world(self.root)
        self.assertTrue(self.world.exists())

    def test_marker_symlink_refused(self):
        (self.root / '.reset_world').unlink()
        (self.root / '.reset_world').symlink_to(self.root / 'keep.txt')
        with self.assertRaises(OSError): subject.reset_world(self.root)

    def test_world_symlink_refused(self):
        self.world.rename(self.root / 'other')
        self.world.symlink_to(self.root / 'other')
        with self.assertRaises(ValueError): subject.reset_world(self.root)
        self.assertTrue((self.root / 'other/playerdata/inventory').exists())

    def test_nested_symlink_refused(self):
        (self.world / 'link').symlink_to(self.root / 'keep.txt')
        with self.assertRaises(ValueError): subject.reset_world(self.root)

    def test_deletion_failure_keeps_marker(self):
        with patch.object(subject.shutil, 'rmtree', side_effect=PermissionError('test')) as delete:
            delete.avoids_symlink_attacks = True
            with self.assertRaises(PermissionError): subject.reset_world(self.root)
        self.assertTrue((self.root / '.reset_world').exists())
        self.assertTrue(self.world.exists())

    def test_locked_world_refused(self):
        code = 'import fcntl,sys,time; f=open(sys.argv[1],"w"); fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); time.sleep(30)'
        process = subprocess.Popen(['python3', '-c', code, str(self.world / 'session.lock')], stdout=subprocess.PIPE)
        try:
            self.assertEqual(process.stdout.readline(), b'locked\n')
            with self.assertRaises(BlockingIOError): subject.reset_world(self.root)
            self.assertTrue(self.world.exists())
        finally:
            process.terminate()
            process.wait()
            process.stdout.close()

if __name__ == '__main__':
    unittest.main(verbosity=2)
