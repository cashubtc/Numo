import argparse
import base64
import importlib.util
import io
import json
from pathlib import Path
import subprocess
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
    def test_reads_certificate_from_legacy_and_build_tools_37_signer_labels(self, output):
        fingerprint = "a1" * 32
        labels = (
            "Signer #1",
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647)",
            "V1 Signer:",
            "V2 Signer:",
            "V3.0 Signer:",
            "V2 Signer #1:",
            "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)",
            "V3.1 Signer: (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)",
        )
        for label in labels:
            with self.subTest(label=label):
                output.return_value = (
                    f"{label} certificate DN: CN=Numo test\n"
                    f"{label} certificate SHA-256 digest: {fingerprint}\n"
                    f"{label} public key SHA-256 digest: {'b2' * 32}\n"
                    f"Source Stamp Signer: certificate SHA-256 digest: {'c3' * 32}\n"
                )
                self.assertEqual(fingerprint,
                                 manifest.apk_signing_certificate(Path("app.apk"), "apksigner"))

    @patch.object(manifest.subprocess, "check_output")
    def test_same_certificate_reported_for_multiple_schemes_is_one_certificate(self, output):
        fingerprint = "a1" * 32
        output.return_value = (
            f"V3.0 Signer: certificate SHA-256 digest: {fingerprint}\n"
            f"V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) "
            f"certificate SHA-256 digest: {fingerprint.upper()}\n"
        )
        self.assertEqual(fingerprint,
                         manifest.apk_signing_certificate(Path("app.apk"), "apksigner"))

    @patch.object(manifest.subprocess, "check_output")
    def test_distinct_apk_certificates_are_rejected_in_old_and_new_output(self, output):
        for labels in (
                ("Signer #1", "Signer #2"),
                ("V2 Signer #1:", "V2 Signer #2:"),
                ("V3.0 Signer:", "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)"),
                ("V3.2 Hybrid Classical Signer: (minSdkVersion=37, maxSdkVersion=2147483647)",
                 "V3.2 Hybrid PQC Signer: (minSdkVersion=37, maxSdkVersion=2147483647)")):
            with self.subTest(labels=labels):
                output.return_value = (
                    f"{labels[0]} certificate SHA-256 digest: {'a1' * 32}\n"
                    f"{labels[1]} certificate SHA-256 digest: {'b2' * 32}\n"
                )
                with self.assertRaisesRegex(ValueError, "exactly one APK signing certificate"):
                    manifest.apk_signing_certificate(Path("app.apk"), "apksigner")

    @patch.object(manifest.subprocess, "check_output")
    def test_missing_apk_certificate_cannot_use_a_public_key_or_source_stamp(self, output):
        for verification in (
                "",
                f"V3.0 Signer: public key SHA-256 digest: {'a1' * 32}\n",
                f"Source Stamp Signer: certificate SHA-256 digest: {'a1' * 32}\n",
                f"Signer #1 certificate SHA-256 digest: {'a1' * 31}\n"):
            with self.subTest(verification=verification):
                output.return_value = verification
                with self.assertRaisesRegex(ValueError, "exactly one APK signing certificate"):
                    manifest.apk_signing_certificate(Path("app.apk"), "apksigner")

    @patch.object(manifest.subprocess, "check_output")
    def test_apksigner_verification_failure_is_propagated(self, output):
        output.side_effect = subprocess.CalledProcessError(1, "apksigner")
        with self.assertRaises(subprocess.CalledProcessError):
            manifest.apk_signing_certificate(Path("app.apk"), "apksigner")

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
