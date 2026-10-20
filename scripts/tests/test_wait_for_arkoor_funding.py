import importlib.util
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "wait_for_arkoor_funding.py"
SPEC = importlib.util.spec_from_file_location("wait_for_arkoor_funding", SCRIPT)
funding = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(funding)


class FundingReadinessTest(unittest.TestCase):
    def test_waits_until_the_entire_funding_amount_is_confirmed(self):
        with patch.object(funding, "confirmed_balance", side_effect=[0, 1_999_999, 2_000_000]) \
                as balance, patch.object(funding.time, "monotonic", return_value=0), \
                patch.object(funding.time, "sleep") as sleep:
            funding.wait_for_funding(2_000_000)
        self.assertEqual(3, balance.call_count)
        self.assertEqual(2, sleep.call_count)

    def test_retries_transient_balance_query_failures(self):
        error = subprocess.CalledProcessError(1, "bark", stderr="indexer is starting")
        with patch.object(funding, "confirmed_balance", side_effect=[error, 2_000_000]) \
                as balance, patch.object(funding.time, "monotonic", return_value=0), \
                patch.object(funding.time, "sleep"):
            funding.wait_for_funding(2_000_000)
        self.assertEqual(2, balance.call_count)

    def test_timeout_reports_the_last_confirmed_balance(self):
        with patch.object(funding, "confirmed_balance", return_value=0) as balance, \
                patch.object(funding.time, "monotonic", side_effect=[0, 0, 0, 61]), \
                patch.object(funding.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "last confirmed balance: 0 sats"):
                funding.wait_for_funding(2_000_000)
        balance.assert_called_once_with(60)
        sleep.assert_called_once_with(1)

    def test_timeout_bounds_queries_and_sleep_and_reports_query_errors(self):
        error = subprocess.TimeoutExpired("bark", 0.5, stderr="indexer unavailable")
        with patch.object(funding, "confirmed_balance", side_effect=error) as balance, \
                patch.object(funding.time, "monotonic", side_effect=[0, 0.5, 0.75, 1]), \
                patch.object(funding.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "indexer unavailable"):
                funding.wait_for_funding(2_000_000, timeout_seconds=1)
        balance.assert_called_once_with(0.5)
        sleep.assert_called_once_with(0.25)

    def test_balance_query_uses_confirmed_funds_and_inherits_compose_project(self):
        result = SimpleNamespace(stdout=json.dumps({
            "total_sat": 2_000_000, "confirmed_sat": 0, "trusted_pending_sat": 2_000_000,
        }))
        with patch.object(funding.subprocess, "run", return_value=result) as run:
            self.assertEqual(0, funding.confirmed_balance(2.5))
        args, kwargs = run.call_args
        self.assertEqual([
            "docker", "compose", "--file", str(funding.COMPOSE_FILE), "run", "--rm",
            "--no-deps", "-T", "bark", "--quiet", "onchain", "balance",
        ], args[0])
        self.assertEqual(2.5, kwargs["timeout"])
        self.assertTrue(kwargs["check"])
        self.assertNotIn("env", kwargs)

    def test_rejects_nonpositive_amounts_and_timeouts(self):
        for amount, timeout in [(0, 60), (-1, 60), (2_000_000, 0), (2_000_000, -1)]:
            with self.subTest(amount=amount, timeout=timeout):
                with self.assertRaises(ValueError):
                    funding.wait_for_funding(amount, timeout)


if __name__ == "__main__":
    unittest.main()
