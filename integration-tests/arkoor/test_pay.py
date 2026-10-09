import unittest
from unittest.mock import patch

import pay


class PayTest(unittest.TestCase):
    def test_rejects_mainnet_address_before_running_docker(self):
        with patch.object(pay, "command") as command:
            with self.assertRaises(ValueError):
                pay.pay("ark1mainnet", 330)
        command.assert_not_called()

    def test_rejects_nonpositive_amount_before_running_docker(self):
        with patch.object(pay, "command") as command:
            for amount in (0, -1):
                with self.assertRaises(ValueError):
                    pay.pay("tark1regtest", amount)
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
