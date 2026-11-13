from types import SimpleNamespace
import io
import json
import unittest
import urllib.error
from unittest.mock import patch

import provision


class ProvisionTest(unittest.TestCase):
    def test_bitcoin_accepts_unquoted_scalar_rpc_results(self):
        with patch.object(provision, "command", return_value=SimpleNamespace(stdout="txid\n")):
            self.assertEqual("txid", provision.bitcoin("sendtoaddress", "bcrt1address", "2"))

    def test_payer_funding_is_visible_before_boarding(self):
        events = []
        wallet = {"rounds": {"address": "bcrt1server", "total_balance": 200_000_000}}

        def docker_command(*args, **kwargs):
            if args[:3] == ("exec", "-T", "captaind"):
                return SimpleNamespace(stdout=json.dumps(wallet))
            if args[-2:] == ("board", "1000000 sat"):
                self.assertIn("funding visible", events)
                events.append("board")
            return SimpleNamespace(returncode=0)

        def payer_command(*args):
            if args == ("onchain", "address"):
                return {"address": "bcrt1payer"}
            if args == ("balance",):
                return {"spendable_sat": 998_900 if "board" in events else 0}
            return {}

        def readiness_command(args, **kwargs):
            self.assertTrue(args[1].endswith("/scripts/wait_for_arkoor_funding.py"))
            self.assertIn("2000000", args)
            self.assertTrue(kwargs["check"])
            events.append("funding visible")

        with patch.object(provision, "command", side_effect=docker_command), \
                patch.object(provision, "bark", side_effect=payer_command), \
                patch.object(provision, "bitcoin") as bitcoin, \
                patch.object(provision.subprocess, "run", side_effect=readiness_command), \
                patch.object(provision, "wait_for_lightning"):
            provision.provision()
        bitcoin.assert_any_call("sendtoaddress", "bcrt1payer", "0.02")
        bitcoin.assert_any_call("-generate", "6")
        self.assertEqual(["funding visible", "board"], events)

    def test_readiness_waits_for_invoice_creation_after_backend_startup(self):
        quote = {"request": "lnbcrt3300n", "state": "UNPAID"}
        responses = [urllib.error.URLError("Ark pool is starting"),
                     io.BytesIO(json.dumps(quote).encode())]
        with patch.object(provision.urllib.request, "urlopen", side_effect=responses) as urlopen, \
                patch.object(provision.time, "sleep"):
            provision.wait_for_lightning()
        self.assertEqual(2, urlopen.call_count)

    def test_readiness_rejects_mainnet_invoice(self):
        quote = {"request": "lnbc3300n", "state": "UNPAID"}
        with patch.object(provision.urllib.request, "urlopen",
                          return_value=io.BytesIO(json.dumps(quote).encode())):
            with self.assertRaises(RuntimeError):
                provision.wait_for_lightning()

    def test_readiness_timeout_fails(self):
        with patch.object(provision.time, "monotonic", side_effect=[0, 91]):
            with self.assertRaises(RuntimeError):
                provision.wait_for_lightning()


if __name__ == "__main__":
    unittest.main()
