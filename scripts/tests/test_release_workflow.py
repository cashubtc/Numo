import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("validate_release", SCRIPTS / "validate_release.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class ReleaseValidationTest(unittest.TestCase):
    def test_accepts_existing_tag_formats_and_version_code_limits(self):
        for version in ("1.10", "v1.10.0", "v1.10-rc1", "1.10.0-beta.2"):
            for code in ("1", "26", "2100000000"):
                with self.subTest(version=version, code=code):
                    validator.validate_release(version, code)

    def test_rejects_invalid_tags(self):
        for version in ("", "v1", "1.10/../../file", "1.10\n", "$(id)"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError, "RELEASE_VERSION"):
                    validator.validate_release(version, "26")

    def test_rejects_non_integer_and_out_of_range_codes(self):
        for code in ("", "0", "-1", "2100000001", "26.5", "abc"):
            with self.subTest(code=code):
                with self.assertRaisesRegex(ValueError, "RELEASE_CODE"):
                    validator.validate_release("v1.10", code)

    def test_missing_workflow_inputs_fail(self):
        environment = os.environ.copy()
        environment.pop("RELEASE_VERSION", None)
        environment.pop("RELEASE_CODE", None)
        result = subprocess.run([sys.executable, str(SCRIPTS / "validate_release.py")],
                                env=environment, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("RELEASE_VERSION", result.stderr)

    def test_validation_still_runs_with_python_optimization_enabled(self):
        environment = dict(os.environ, RELEASE_VERSION="v1.10", RELEASE_CODE="0")
        result = subprocess.run([sys.executable, "-O", str(SCRIPTS / "validate_release.py")],
                                env=environment, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("RELEASE_CODE", result.stderr)


class ManualReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="numo release test ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copyfile(SCRIPTS / "manual_release.sh", self.root / "scripts/manual_release.sh")
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle.kts").write_text("// Original configuration\n")
        self.environment = dict(os.environ, RELEASE_VERSION="v1.10", RELEASE_CODE="26",
                                KEYSTORE_PATH=str(self.root / "keystore/app-release.keystore"))

    def run_command(self, command):
        return subprocess.run(["bash", str(self.root / "scripts/manual_release.sh"), command],
                              cwd=self.root.parent, env=self.environment,
                              capture_output=True, text=True, check=True)

    def test_keystore_and_signing_configuration_share_the_same_path(self):
        keystore = b"test keystore bytes\x00\xff"
        self.environment["ENCODED_KEYSTORE"] = base64.b64encode(keystore).decode()
        self.run_command("decode-keystore")
        self.run_command("configure-signing")
        self.run_command("enable-debug-symbols")

        self.assertEqual(keystore, Path(self.environment["KEYSTORE_PATH"]).read_bytes())
        configuration = (self.root / "app/build.gradle.kts").read_text()
        self.assertTrue(configuration.startswith("// Original configuration\n"))
        self.assertIn(f'storeFile = file("{self.environment["KEYSTORE_PATH"]}")', configuration)
        self.assertIn('getByName("release")', configuration)
        self.assertIn('getByName("play")', configuration)
        self.assertIn('System.getenv("KEYSTORE_PASSWORD")', configuration)
        self.assertIn('debugSymbolLevel = "FULL"', configuration)

    def test_build_commands_pass_the_same_version_to_direct_and_play_builds(self):
        gradlew = self.root / "gradlew"
        gradlew.write_text('#!/usr/bin/env python3\nimport json, sys\n'
                           'print(json.dumps(sys.argv[1:]))\n')
        gradlew.chmod(0o755)
        for command, task in (("build-apk", "assembleRelease"), ("build-bundle", "bundlePlay")):
            with self.subTest(command=command):
                result = self.run_command(command)
                self.assertEqual([task, "-PnumoVersionCode=26", "-PnumoVersionName=1.10"],
                                 json.loads(result.stdout))

    def test_renames_every_abi_apk_and_the_play_bundle_without_changing_contents(self):
        apk_dir = self.root / "app/build/outputs/apk/release"
        bundle_dir = self.root / "app/build/outputs/bundle/play"
        apk_dir.mkdir(parents=True)
        bundle_dir.mkdir(parents=True)
        suffixes = ("universal", "armeabi-v7a", "arm64-v8a", "x86_64", "x86")
        for suffix in suffixes:
            (apk_dir / f"app-{suffix}-release.apk").write_text(suffix)
        (bundle_dir / "app-play.aab").write_bytes(b"play bundle")

        self.run_command("rename-artifacts")

        self.assertEqual({f"numo-v1.10-{suffix}.apk" for suffix in suffixes},
                         {apk.name for apk in apk_dir.iterdir()})
        for suffix in suffixes:
            self.assertEqual(suffix, (apk_dir / f"numo-v1.10-{suffix}.apk").read_text())
        self.assertEqual(b"play bundle", (bundle_dir / "numo-v1.10.aab").read_bytes())

    def test_apk_without_an_abi_in_its_name_uses_the_universal_suffix(self):
        apk_dir = self.root / "app/build/outputs/apk/release"
        apk_dir.mkdir(parents=True)
        (apk_dir / "app-release.apk").write_bytes(b"universal apk")
        self.run_command("rename-artifacts")
        self.assertEqual(b"universal apk", (apk_dir / "numo-v1.10-universal.apk").read_bytes())

    def test_manifest_uses_the_channel_latest_tools_signing_path_and_literal_notes(self):
        android_home = self.root / "android sdk"
        for version in ("9.0.0", "35.0.0", "36.0.0"):
            (android_home / "build-tools" / version).mkdir(parents=True)
        runner_temp = self.root / "runner temp"
        runner_temp.mkdir()
        notes = '- Added "NFC"\n$(touch unexpected-file)\nRelease café\n'
        self.environment.update(ANDROID_HOME=str(android_home), RUNNER_TEMP=str(runner_temp),
                                RELEASE_NOTES=notes)
        (self.root / "scripts/create_update_manifest.py").write_text(
            'import json, os, sys\n'
            'print(json.dumps({"args": sys.argv[1:], "keystore": os.environ["KEYSTORE_PATH"]}))\n')

        for pre_release, channel in (("false", "stable"), ("true", "beta")):
            with self.subTest(channel=channel):
                self.environment["PRE_RELEASE"] = pre_release
                result = json.loads(self.run_command("create-manifest").stdout)
                build_tools = android_home / "build-tools/36.0.0"
                self.assertEqual([
                    "--apk", "app/build/outputs/apk/release/numo-v1.10-universal.apk",
                    "--tag", "v1.10", "--channel", channel, "--check-latest",
                    "--aapt", str(build_tools / "aapt"),
                    "--apksigner", str(build_tools / "apksigner"),
                    "--notes-file", str(runner_temp / "update-notes.txt"),
                    "--output", "app/build/outputs/apk/release/update.json",
                ], result["args"])
                self.assertEqual(self.environment["KEYSTORE_PATH"], result["keystore"])
                self.assertEqual(notes.encode(), (runner_temp / "update-notes.txt").read_bytes())
                self.assertFalse((self.root / "unexpected-file").exists())


if __name__ == "__main__":
    unittest.main()
