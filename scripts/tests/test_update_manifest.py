import argparse
import base64
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error


spec = importlib.util.spec_from_file_location(
    "update_manifest", Path(__file__).resolve().parents[1] / "create_update_manifest.py")
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


class UpdateManifestTest(unittest.TestCase):
    def args(self, apk):
        return argparse.Namespace(apk=apk, tag="v1.10", repository="cashubtc/Numo",
                                  channel="stable", aapt="aapt", apksigner="apksigner",
                                  output=apk.parent / "update.json", notes_file=None,
                                  check_latest=True)

    @patch.object(manifest.subprocess, "check_output")
    def test_reads_version_and_sdk_from_the_apk(self, output):
        output.return_value = (
            "package: name='com.electricdreams.numo' versionCode='26' versionName='1.10'\n"
            "sdkVersion:'24'\n")
        self.assertEqual(manifest.apk_metadata(Path("app.apk"), "aapt"),
                         dict(packageName="com.electricdreams.numo", versionCode=26,
                              versionName="1.10", minSdk=24))

    @patch.object(manifest, "latest_version_code", return_value=26)
    @patch.object(manifest, "apk_metadata")
    @patch.object(manifest.subprocess, "run")
    def test_reused_version_code_cannot_be_published(self, run, metadata, latest):
        metadata.return_value = dict(packageName="com.electricdreams.numo",
                                    versionCode=26, versionName="1.10", minSdk=24)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "greater than"):
                manifest.create_manifest(self.args(Path(directory) / "numo-v1.10-universal.apk"))
        run.assert_not_called()

    @patch.object(manifest, "apk_metadata")
    def test_release_tag_must_match_the_embedded_version(self, metadata):
        metadata.return_value = dict(packageName="com.electricdreams.numo",
                                    versionCode=26, versionName="1.9", minSdk=24)
        with self.assertRaisesRegex(ValueError, "versionName"):
            manifest.create_manifest(self.args(Path("numo-v1.10-universal.apk")))

    @patch.object(manifest.urllib.request, "urlopen")
    def test_publishing_guard_uses_latest_stable_release_endpoint(self, urlopen):
        release = dict(assets=[dict(name="update.json",
                                   browser_download_url="https://github.com/cashubtc/Numo/update.json")])
        envelope = dict(payload=base64.b64encode(b'{"versionCode":26}').decode())
        urlopen.side_effect = [io.BytesIO(json.dumps(release).encode()),
                               io.BytesIO(json.dumps(envelope).encode())]
        self.assertEqual(26, manifest.latest_version_code("cashubtc/Numo"))
        request = urlopen.call_args_list[0].args[0]
        self.assertEqual("https://api.github.com/repos/cashubtc/Numo/releases/latest", request.full_url)

    @patch.object(manifest.urllib.request, "urlopen")
    def test_legacy_release_without_metadata_bootstraps_the_feed(self, urlopen):
        urlopen.return_value = io.BytesIO(b'{"assets":[]}')
        self.assertIsNone(manifest.latest_version_code("cashubtc/Numo"))

    @patch.object(manifest.urllib.request, "urlopen")
    def test_rate_limit_is_a_failure_not_permission_to_skip_version_validation(self, urlopen):
        urlopen.side_effect = urllib.error.HTTPError("https://api.github.com", 403, "rate limit", {}, None)
        with self.assertRaises(urllib.error.HTTPError):
            manifest.latest_version_code("cashubtc/Numo")


if __name__ == "__main__":
    unittest.main()
