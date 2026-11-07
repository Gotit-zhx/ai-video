import base64
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
FILE=ROOT/'ops/finance-ad-proxy/render.py'
spec=importlib.util.spec_from_file_location('finance_ad_proxy',FILE)
proxy=importlib.util.module_from_spec(spec);spec.loader.exec_module(proxy)


class FinanceAdProxyTest(unittest.TestCase):
    def node(self):
        return {'add':'node.example.invalid','port':'55555','id':'11111111-1111-4111-8111-111111111111',
                'aid':'0','net':'tcp','tls':'','type':'none','scy':'auto','host':'','path':''}

    def share(self,node=None):
        return 'vmess://'+base64.b64encode(json.dumps(node or self.node()).encode()).decode()

    def test_only_official_https_from_private_source_is_forwarded(self):
        config=proxy.configuration(self.share(),'172.18.0.1',18089,['172.18.0.0/16'])
        self.assertEqual('AsIs',config['routing']['domainStrategy'])
        allowed,blocked=config['routing']['rules']
        self.assertEqual(['full:business-api.tiktok.com'],allowed['domain'])
        self.assertEqual('443',allowed['port']);self.assertEqual(['172.18.0.0/16'],allowed['source'])
        self.assertEqual('deny',blocked['outboundTag']);self.assertEqual('deny',config['outbounds'][0]['tag'])
        self.assertEqual('none',config['log']['loglevel']);self.assertNotIn('api',config)

    def test_public_wildcard_and_wide_sources_are_rejected(self):
        for listen,port,sources in [('0.0.0.0',18089,['172.18.0.0/16']),('8.8.8.8',18089,['172.18.0.0/16']),
                                    ('127.0.0.1',443,['127.0.0.1/32']),('127.0.0.1',18089,['0.0.0.0/0']),
                                    ('127.0.0.1',18089,[])]:
            with self.subTest(listen=listen,port=port,sources=sources),self.assertRaises(ValueError):
                proxy.configuration(self.share(),listen,port,sources)

    def test_empty_or_missing_tcp_header_type_means_no_header(self):
        expected=proxy.configuration(self.share(),'127.0.0.1',18889,['127.0.0.1/32'])
        for header_type in ('',None):
            node=self.node()
            if header_type is None:node.pop('type')
            else:node['type']=header_type
            with self.subTest(header_type=header_type):
                actual=proxy.configuration(self.share(node),'127.0.0.1',18889,['127.0.0.1/32'])
                self.assertEqual(expected,actual)

    def test_omitted_base64_padding_is_accepted(self):
        for suffix in ('','x','xx'):
            node=self.node();node['ps']=suffix
            share=self.share(node)
            with self.subTest(length=len(share.rstrip('='))):
                self.assertEqual(
                    proxy.configuration(share,'127.0.0.1',18889,['127.0.0.1/32']),
                    proxy.configuration(share.rstrip('='),'127.0.0.1',18889,['127.0.0.1/32']))

    def test_invalid_base64_is_rejected_without_echoing_input(self):
        for encoded in ('A','unit-only-secret!','abcd=def',self.share().removeprefix('vmess://')+'!'):
            with self.subTest(encoded_length=len(encoded)),self.assertRaises(ValueError) as failure:
                proxy.configuration('vmess://'+encoded,'127.0.0.1',18889,['127.0.0.1/32'])
            self.assertEqual('Unsupported node or listener configuration',str(failure.exception))

    def test_unsupported_parameters_are_not_silently_downgraded(self):
        for key,value in [('aid','64'),('net','ws'),('tls','tls'),('type','http'),('scy','none'),
                          ('add','https://unit-only-secret@node.invalid'),('id',42),('port','unit-only-secret')]:
            node=self.node();node[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError) as failure:
                proxy.configuration(self.share(node),'127.0.0.1',18889,['127.0.0.1/32'])
            self.assertNotIn('unit-only-secret',str(failure.exception))

    def test_cli_never_overwrites_or_prints_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'share';target=root/'config.json';source.write_text(self.share())
            command=[sys.executable,str(FILE),'--input',str(source),'--output',str(target),
                     '--listen','127.0.0.1','--port','18889','--source','127.0.0.1/32']
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(0,result.returncode);self.assertEqual(0o600,target.stat().st_mode&0o777)
            self.assertNotIn(self.node()['id'],result.stdout+result.stderr)
            original=target.read_bytes();again=subprocess.run(command,capture_output=True,text=True)
            self.assertNotEqual(0,again.returncode);self.assertEqual(original,target.read_bytes())

    def test_service_has_no_persistent_logs_or_privileged_runtime(self):
        service=(ROOT/'ops/finance-ad-proxy/heyi-finance-ad-proxy.service').read_text()
        for item in ['DynamicUser=yes','NoNewPrivileges=yes','MemoryMax=64M','CPUQuota=10%',
                     'StandardOutput=null','StandardError=null','LoadCredential=proxy.json:',
                     '${CREDENTIALS_DIRECTORY}/proxy.json']:
            self.assertIn(item,service)
        self.assertNotIn('CAP_NET_ADMIN',service)


if __name__=='__main__':unittest.main()
