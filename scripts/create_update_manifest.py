#!/usr/bin/env python3
"""Create update.json from the actual signed universal APK, without third-party Python packages."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request


def apk_metadata(apk, aapt):
    output = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", output, re.M)
    if not package or not sdk:
        raise ValueError("Cannot read APK package metadata")
    if package[1] != "com.electricdreams.numo":
        raise ValueError("Unexpected APK package")
    return dict(packageName=package[1], versionCode=int(package[2]),
                versionName=package[3], minSdk=int(sdk[1]))


def latest_version_code(repository):
    """Compare against the last published stable feed; drafts and prereleases are excluded."""
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/releases/latest",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "Numo-release"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            release = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    asset = next((asset for asset in release["assets"] if asset["name"] == "update.json"), None)
    if asset is None:
        # Bootstrap: older releases predate signed metadata. Release maintainers must
        # choose a versionCode above all previous releases (including Play builds).
        return None
    with urllib.request.urlopen(asset["browser_download_url"], timeout=30) as response:
        envelope = json.loads(response.read(128 * 1024 + 1))
    # This is a publishing guard, not a client trust decision. Clients authenticate
    # the signature independently using the installed APK's certificate.
    payload = json.loads(base64.b64decode(envelope["payload"], validate=True))
    return int(payload["versionCode"])


def create_manifest(args):
    if args.repository != "cashubtc/Numo":
        raise ValueError("Only the official release repository is supported")
    if not re.fullmatch(r"v?\d+\.\d+(?:\.\d+)?(?:[-.][A-Za-z0-9.-]+)?", args.tag):
        raise ValueError("Invalid release tag")
    if not args.apk.name.endswith("-universal.apk"):
        raise ValueError("Expected a universal APK")
    metadata = apk_metadata(args.apk, args.aapt)
    if metadata["versionName"] != args.tag.removeprefix("v"):
        raise ValueError("APK versionName does not match the release tag")
    if not 0 < metadata["versionCode"] <= 2_100_000_000:
        raise ValueError("Invalid versionCode")
    if args.check_latest:
        previous = latest_version_code(args.repository)
        if previous is not None and metadata["versionCode"] <= previous:
            raise ValueError(f"versionCode must be greater than the published version ({previous})")
        if previous is None:
            print("Bootstrapping update feed: verify versionCode exceeds all existing releases.")
    verification = subprocess.check_output(
        [args.apksigner, "verify", "--print-certs", str(args.apk)], text=True)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", verification)
    if len(signers) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    size = args.apk.stat().st_size
    if not 0 < size <= 1024 ** 3:
        raise ValueError("APK size is outside updater limits")
    notes = args.notes_file.read_text() if args.notes_file else ""
    if len(notes) > 16_000:
        raise ValueError("Release notes exceed updater limit")
    with args.apk.open("rb") as apk_file:
        digest = hashlib.file_digest(apk_file, "sha256").hexdigest()
    payload = dict(schemaVersion=1, channel=args.channel, **metadata,
                   apkUrl=f"https://github.com/{args.repository}/releases/download/{args.tag}/{args.apk.name}",
                   size=size, sha256=digest, releaseNotes=notes)
    with tempfile.TemporaryDirectory(prefix="numo-manifest-") as temporary:
        payload_path = Path(temporary) / "payload.json"
        payload_path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        subprocess.run(["java", str(Path(__file__).with_name("SignUpdateManifest.java")),
                        str(payload_path), str(args.output), signers[0]], check=True)
    print(f"Created signed manifest for versionCode {metadata['versionCode']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", default="cashubtc/Numo")
    parser.add_argument("--channel", choices=["stable", "beta"], default="stable")
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument("--check-latest", action="store_true")
    create_manifest(parser.parse_args())
