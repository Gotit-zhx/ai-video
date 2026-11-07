#!/usr/bin/env python3
"""Render one private VMess AEAD/TCP connector without sending credentials anywhere."""
import argparse
import base64
import ipaddress
import json
import os
from pathlib import Path
import re
import uuid

TARGET = 'business-api.tiktok.com'


def configuration(share, listen, port, sources):
    if len(share) > 8192 or not share.startswith('vmess://'):
        raise ValueError('Expected one VMess share link')
    try:
        encoded = share.removeprefix('vmess://')
        if '=' not in encoded:
            encoded += '=' * (-len(encoded) % 4)
        node = json.loads(base64.b64decode(encoded, validate=True))
        if not isinstance(node['id'],str):raise ValueError()
        identity=str(uuid.UUID(node['id']))
        address, node_port = node['add'], int(node['port'])
        if not isinstance(address, str) or len(address)>253 or not re.fullmatch(r'[A-Za-z0-9._:-]+',address):
            raise ValueError()
        if not 1<=node_port<=65535 or str(node.get('aid','0'))!='0' or node.get('net')!='tcp':
            raise ValueError()
        if node.get('tls') not in (None,'','none') or node.get('type','none') not in ('','none') or node.get('host') or node.get('path'):
            raise ValueError()
        cipher=node.get('scy','auto')
        if cipher not in ('auto','aes-128-gcm','chacha20-poly1305'):
            raise ValueError()
        local=ipaddress.ip_address(listen)
        if local.version!=4 or not local.is_private or local.is_unspecified or local.is_multicast or not 1024<=port<=65535:
            raise ValueError()
        networks=[ipaddress.ip_network(value,strict=True) for value in sources]
        if not networks or any(n.version!=4 or not n.is_private or n.prefixlen<16 for n in networks):
            raise ValueError()
    except (KeyError, TypeError, ValueError, UnicodeError) as failure:
        raise ValueError('Unsupported node or listener configuration') from None
    return {
        'log':{'loglevel':'none','access':'none','error':'none'},
        'inbounds':[{'tag':'finance-http','listen':str(local),'port':port,'protocol':'http',
                     'settings':{'allowTransparent':False},'sniffing':{'enabled':False}}],
        'outbounds':[{'tag':'deny','protocol':'blackhole','settings':{}},
                     {'tag':'ads-node','protocol':'vmess',
                      'settings':{'address':address,'port':node_port,'id':identity,'security':cipher},
                      'streamSettings':{'network':'raw','security':'none'},'mux':{'enabled':False}}],
        'routing':{'domainStrategy':'AsIs','rules':[
            {'type':'field','inboundTag':['finance-http'],'source':[str(n) for n in networks],
             'network':'tcp','domain':['full:'+TARGET],'port':'443','outboundTag':'ads-node'},
            {'type':'field','inboundTag':['finance-http'],'outboundTag':'deny'}]},
        'policy':{'levels':{'0':{'handshake':8,'connIdle':180,'bufferSize':16}}}
    }


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True,help='Private file containing the share link')
    p.add_argument('--output',type=Path,required=True,help='New private JSON file; existing files are never replaced')
    p.add_argument('--listen',required=True)
    p.add_argument('--port',type=int,default=18089)
    p.add_argument('--source',action='append',required=True,help='Allowed private IPv4 subnet')
    args=p.parse_args();os.umask(0o077)
    try:
        if args.input.stat().st_size>8192:raise ValueError('Oversized share link')
        value=configuration(args.input.read_text().strip(),args.listen,args.port,args.source)
        with args.output.open('x',encoding='utf-8') as output:
            output.write(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
        print('Private advertising connector configuration written.')
    except (OSError, ValueError, TypeError):
        print('Configuration rejected; check the private input and listener settings.')
        return 1
    return 0


if __name__=='__main__':raise SystemExit(main())
