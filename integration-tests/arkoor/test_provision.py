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
