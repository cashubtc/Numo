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

    def test_downloads_an_existing_arm64_release_and_uses_its_tag_commit(self):
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        gh_log = self.root / "gh.jsonl"
        github_env = self.root / "github.env"
        gh = fake_bin / "gh"
        gh.write_text(
            '#!/usr/bin/env python3\n'
            'import json, os, pathlib, sys\n'
            'with open(os.environ["GH_LOG"], "a") as log:\n'
            '    log.write(json.dumps(sys.argv[1:]) + "\\n")\n'
            'directory = pathlib.Path(sys.argv[sys.argv.index("--dir") + 1])\n'
            'directory.mkdir(parents=True, exist_ok=True)\n'
            'filename = sys.argv[sys.argv.index("--pattern") + 1]\n'
            '(directory / filename).write_bytes(b"existing arm64 apk")\n')
        gh.chmod(0o755)
        git = fake_bin / "git"
        git.write_text('#!/bin/sh\nprintf "tagcommit\\n"\n')
        git.chmod(0o755)
        self.environment.update(GH_TOKEN="token", GITHUB_ENV=str(github_env),
                                GH_LOG=str(gh_log), PATH=f"{fake_bin}{os.pathsep}{os.environ['PATH']}")

        self.run_command("download-release-apk")

        self.assertEqual([[
            "release", "download", "v1.10", "--pattern", "numo-v1.10-arm64-v8a.apk",
            "--dir", "app/build/outputs/apk/release", "--clobber",
        ]], [json.loads(line) for line in gh_log.read_text().splitlines()])
        apk = self.root / "app/build/outputs/apk/release/numo-v1.10-arm64-v8a.apk"
        self.assertEqual(b"existing arm64 apk", apk.read_bytes())
        self.assertEqual("RELEASE_COMMIT=tagcommit\n", github_env.read_text())

    def test_zapstore_links_the_signing_key_and_publishes_the_arm64_apk(self):
        runner_temp = self.root / "runner temp"
        runner_temp.mkdir()
        apk = self.root / "app/build/outputs/apk/release/numo-v1.10-arm64-v8a.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"signed arm64 apk")
        keytool_log = self.root / "keytool.jsonl"
        zsp_log = self.root / "zsp.jsonl"
        nak_log = self.root / "nak.jsonl"
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        keytool = fake_bin / "keytool"
        keytool.write_text(
            '#!/usr/bin/env python3\n'
            'import json, os, pathlib, sys\n'
            'with open(os.environ["KEYTOOL_LOG"], "a") as log:\n'
            '    log.write(json.dumps(sys.argv[1:]) + "\\n")\n'
            'if os.environ.get("FAIL_JKS") == "1" and "JKS" in sys.argv:\n'
            '    sys.exit(1)\n'
            'destination = pathlib.Path(sys.argv[sys.argv.index("-destkeystore") + 1])\n'
            'destination.write_bytes(b"pkcs12")\n')
        keytool.chmod(0o755)
        zsp = runner_temp / "zsp"
        zsp.write_text(
            '#!/usr/bin/env python3\n'
            'import json, os, sys\n'
            'with open(os.environ["ZSP_LOG"], "a") as log:\n'
            '    log.write(json.dumps(sys.argv[1:]) + "\\n")\n'
            'if sys.argv[1] == "identity":\n'
            '    print(\'{"kind":30509}\')\n')
        zsp.chmod(0o755)
        nak = runner_temp / "nak"
        nak.write_text(
            '#!/usr/bin/env python3\n'
            'import json, os, sys\n'
            'payload = sys.stdin.read()\n'
            'with open(os.environ["NAK_LOG"], "a") as log:\n'
            '    log.write(json.dumps({"args": sys.argv[1:], "stdin": payload}) + "\\n")\n')
        nak.chmod(0o755)
        self.environment.update(
            RUNNER_TEMP=str(runner_temp), GITHUB_SHA="abc123", SIGN_WITH="nsec1test",
            KEYSTORE_PASSWORD="store password", KEY_ALIAS="release", KEY_PASSWORD="key password",
            KEYTOOL_LOG=str(keytool_log), ZSP_LOG=str(zsp_log), NAK_LOG=str(nak_log),
            PATH=f"{fake_bin}{os.pathsep}{os.environ['PATH']}")

        for pre_release, channel, fail_jks in (("false", "main", False), ("true", "beta", True)):
            with self.subTest(channel=channel, fail_jks=fail_jks):
                keytool_log.write_text("")
                zsp_log.write_text("")
                nak_log.write_text("")
                self.environment["PRE_RELEASE"] = pre_release
                self.environment["FAIL_JKS"] = "1" if fail_jks else "0"
                expected_commit = "tagcommit" if pre_release == "true" else "abc123"
                if pre_release == "true":
                    self.environment["RELEASE_COMMIT"] = "tagcommit"
                else:
                    self.environment.pop("RELEASE_COMMIT", None)
                result = self.run_command("publish-zapstore")

                keytool_calls = [json.loads(line) for line in keytool_log.read_text().splitlines()]
                source_types = [call[call.index("-srcstoretype") + 1] for call in keytool_calls]
                self.assertEqual(["JKS", "PKCS12"] if fail_jks else ["JKS"], source_types)
                self.assertEqual([
                    ["identity", "--link-key", str(runner_temp / "zapstore-release-key.p12"),
                     "--link-key-expiry", "2y", "--offline", "--no-color"],
                    ["publish", "--quiet", "--skip-preview", "--no-color", "--commit", expected_commit,
                     "--channel", channel, "release/zapstore.yaml"],
                ], [json.loads(line) for line in zsp_log.read_text().splitlines()])
                nak_call = json.loads(nak_log.read_text())
                self.assertEqual([
                    "event", "--github", "wss://relay.primal.net", "wss://relay.damus.io",
                    "wss://relay.zapstore.dev",
                ], nak_call["args"])
                self.assertEqual('{"kind":30509}\n', nak_call["stdin"])
                self.assertNotIn("nsec1test", result.stdout + result.stderr)

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
