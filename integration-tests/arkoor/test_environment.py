"""Fast checks for the Docker launcher and mint capability validation."""
import io
import json
import subprocess
import unittest
from unittest.mock import patch

import environment


class EnvironmentTest(unittest.TestCase):
    def test_start_waits_for_compose_health_before_checking_mint(self):
        with patch.object(environment, "compose") as compose, \
                patch.object(environment, "status") as status:
            environment.start(build=False)
        compose.assert_called_once_with(
            "up", "--detach", "--wait", "--wait-timeout", "240", "--no-build"
        )
        status.assert_called_once_with()

    def test_failed_start_is_not_reported_as_ready(self):
        error = subprocess.CalledProcessError(1, ["docker", "compose", "up"])
        with patch.object(environment, "compose", side_effect=error), \
                patch.object(environment, "status") as status, \
                patch.object(environment.subprocess, "run") as run:
            with self.assertRaises(subprocess.CalledProcessError):
                environment.start()
        status.assert_not_called()
        self.assertIn("logs", run.call_args.args[0])

    def test_mint_requires_both_methods_and_enabled_minting(self):
        for methods, disabled in [(["bolt11"], False), (["arkoor"], False),
                                  (["bolt11", "arkoor"], True)]:
            info = {"nuts": {"4": {"disabled": disabled, "methods": [
                {"method": method, "unit": "sat"} for method in methods
            ]}}}
            with self.subTest(methods=methods, disabled=disabled), \
                    patch.object(environment.urllib.request, "urlopen") as urlopen:
                urlopen.return_value.__enter__.return_value = io.BytesIO(json.dumps(info).encode())
                with self.assertRaises(RuntimeError):
                    environment.mint_info()

    def test_mint_accepts_both_methods_in_sats(self):
        info = {"nuts": {"4": {"methods": [
            {"method": method, "unit": "sat"} for method in ("arkoor", "bolt11")
        ]}}}
        with patch.object(environment.urllib.request, "urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value = io.BytesIO(json.dumps(info).encode())
            self.assertEqual(info, environment.mint_info())


if __name__ == "__main__":
    unittest.main()
