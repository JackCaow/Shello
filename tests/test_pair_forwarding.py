"""Real TLS through a TCP proxy: advertised Windows ports need not be Linux listen ports."""
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlparse

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('starter',ROOT/'skills/shello-pair/scripts/start_pairing.py')
starter=importlib.util.module_from_spec(spec);spec.loader.exec_module(starter)

class PairForwardingTest(unittest.TestCase):
    def test_external_addresses_ports_pin_and_key_preservation(self):
        with tempfile.TemporaryDirectory(prefix='shello-forward-') as temp, socket.socket() as ssh, socket.socket() as proxy:
            folder=Path(temp);ssh.bind(('127.0.0.1',0));ssh.listen()
            proxy.bind(('127.0.0.1',0));proxy.listen();proxy.settimeout(.2)
            key=folder/'host';subprocess.run(['ssh-keygen','-q','-t','rsa','-b','3072','-N','','-f',str(key)],check=True)
            public=key.with_suffix('.pub').read_text().strip()
            authorized=folder/'authorized_keys';authorized.write_text('# existing access\n')
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1',0));internal=reservation.getsockname()[1]
            qr=folder/'qr';closed=threading.Event()
            def bridge(client):
                try:
                    with client, socket.create_connection(('127.0.0.1',internal),timeout=3) as server:
                        while not closed.is_set():
                            ready,_,_=select.select([client,server],[],[],.2)
                            for stream in ready:
                                data=stream.recv(65536)
                                if not data:return
                                (server if stream is client else client).sendall(data)
                except OSError:pass
            def forward():
                while not closed.is_set():
                    try:client,_=proxy.accept()
                    except socket.timeout:continue
                    except OSError:return
                    threading.Thread(target=bridge,args=(client,),daemon=True).start()
            worker=threading.Thread(target=forward,daemon=True);worker.start()
            env=dict(os.environ,SHELLO_PAIR_HOME=str(ROOT/'build/pair-launcher-env'),TMPDIR=temp)
            with (folder/'log').open('wb') as log:
                process=subprocess.Popen(['bash',str(ROOT/'companion/pair.sh'),'--host','192.168.15.191','--bind','127.0.0.1','--port',str(internal),'--advertise-port',str(proxy.getsockname()[1]),'--ssh-port','2222','--ssh-check-port',str(ssh.getsockname()[1]),'--name','Windows / Ubuntu (WSL2)','--host-key',str(key.with_suffix('.pub')),'--authorized-keys',str(authorized),'--qr-file',str(qr),'--ttl','60'],env=env,stdout=log,stderr=log,start_new_session=True)
                try:
                    deadline=time.monotonic()+90
                    while not qr.exists() and time.monotonic()<deadline and process.poll() is None:time.sleep(.1)
                    self.assertTrue(qr.exists(),'Pairing helper did not become ready')
                    fields=starter.ticket(qr.read_text());self.assertEqual('192.168.15.191',fields['host'])
                    self.assertEqual(proxy.getsockname()[1],int(fields['port']));self.assertNotEqual(internal,int(fields['port']))
                    deadline=time.monotonic()+5
                    while True:
                        try:starter.verify_listener(fields,'127.0.0.1',proxy.getsockname()[1]);break
                        except OSError:
                            if time.monotonic()>deadline:raise
                            time.sleep(.1)
                    with self.assertRaisesRegex(ValueError,'pin mismatch'):starter.verify_listener(dict(fields,pin='0'*64),'127.0.0.1',proxy.getsockname()[1])
                    def post(token):
                        c=http.client.HTTPSConnection('127.0.0.1',int(fields['port']),context=ssl._create_unverified_context(),timeout=5)
                        c.connect();self.assertEqual(fields['pin'],hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest())
                        c.request('POST','/pair',json.dumps({'token':token,'publicKey':public}),{'Content-Type':'application/json'})
                        r=c.getresponse();status,body=r.status,r.read();c.close();return status,body
                    self.assertEqual(403,post('wrong')[0]);self.assertEqual('# existing access\n',authorized.read_text())
                    status,body=post(fields['token']);self.assertEqual(200,status)
                    response=json.loads(body);self.assertEqual(2222,response['port']);self.assertEqual('Windows / Ubuntu (WSL2)',response['name']);self.assertEqual(1,response['version'])
                    self.assertEqual(' '.join(public.split()[:2]),response['hostKeys'][0]);self.assertEqual(200,post(fields['token'])[0])
                    self.assertTrue(authorized.read_text().startswith('# existing access\n'));self.assertEqual(1,authorized.read_text().count(' '.join(public.split()[:2])))
                finally:
                    closed.set();worker.join(timeout=2)
                    if process.poll() is None:os.killpg(process.pid,signal.SIGINT)
                    try:process.wait(timeout=10)
                    except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()

if __name__=='__main__':unittest.main()
