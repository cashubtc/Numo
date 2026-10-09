#!/usr/bin/env python3
"""Validate the release version and Android versionCode supplied by the workflow."""

import os
import re
import sys


def validate_release(version, code):
    if not re.fullmatch(r"v?\d+\.\d+(?:\.\d+)?(?:[-.][A-Za-z0-9.-]+)?", version):
        raise ValueError("RELEASE_VERSION must be a valid release tag, for example v1.10.0")
    try:
        version_code = int(code)
    except ValueError as error:
        raise ValueError("RELEASE_CODE must be an integer") from error
    if not 0 < version_code <= 2_100_000_000:
        raise ValueError("RELEASE_CODE must be between 1 and 2100000000")


if __name__ == "__main__":
    try:
        validate_release(os.environ.get("RELEASE_VERSION", ""),
                         os.environ.get("RELEASE_CODE", ""))
    except ValueError as error:
        sys.exit(f"Release validation failed: {error}")
