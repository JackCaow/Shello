"""Address boundaries and deterministic pairing-network selection; no live network changes."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('pair', Path(__file__).resolve().parents[1] / 'companion/pair.py')
pair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pair)

class PairNetworkTest(unittest.TestCase):
    def test_only_lan_loopback_and_tailnet_ipv4(self):
        for value in ('100.64.0.1', '100.127.255.254', '192.168.15.191', '10.0.0.1', '172.31.2.1', '127.0.0.1'):
            self.assertEqual(value, pair.validate_host(value))
        for value in ('100.63.255.255', '100.128.0.1', '8.8.8.8', '169.254.1.1', '0.0.0.0', '224.1.1.1', '::1', 'host.ts.net', '100.064.1.1'):
            with self.assertRaises(ValueError): pair.validate_host(value)

    def test_auto_prefers_tailnet_and_explicit_host_wins(self):
        with patch.object(pair, 'tailscale_host', return_value='100.83.228.61') as lookup:
            self.assertEqual('100.83.228.61', pair.select_host())
            self.assertEqual('100.83.228.61', pair.select_host(network='tailscale'))
            self.assertEqual('192.168.1.3', pair.select_host('192.168.1.3'))
            self.assertEqual(2, lookup.call_count)

    def test_lan_skips_tailnet_and_strict_tailnet_never_silently_falls_back(self):
        with patch.object(pair, 'tailscale_host', return_value=None) as lookup, patch.object(pair.socket, 'socket') as sock:
            sock.return_value.__enter__.return_value.getsockname.return_value = ('192.168.1.3', 1234)
            self.assertEqual('192.168.1.3', pair.select_host(network='lan'))
            lookup.assert_not_called()
            self.assertEqual('192.168.1.3', pair.select_host())
            with self.assertRaises(ValueError): pair.select_host(network='tailscale')
        for host, mode in [('192.168.1.3', 'tailscale'), ('100.64.1.2', 'lan')]:
            with self.assertRaises(ValueError): pair.select_host(host, mode)

    def test_cli_requires_running_and_ipv4(self):
        with patch.object(pair.shutil, 'which', return_value='/bin/tailscale'), patch.object(pair.os, 'access', return_value=True), patch.object(pair.subprocess, 'run') as run:
            run.return_value.stdout = b'{"BackendState":"Stopped","Self":{"TailscaleIPs":["100.64.1.2"]}}'
            self.assertIsNone(pair.tailscale_host())
            run.return_value.stdout = b'{"BackendState":"Running","Self":{"TailscaleIPs":["fd7a:115c:a1e0::1","100.64.1.2"]}}'
            self.assertEqual('100.64.1.2', pair.tailscale_host())
            run.side_effect = subprocess.TimeoutExpired('tailscale', 5)
            self.assertIsNone(pair.tailscale_host())
