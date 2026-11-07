#!/usr/bin/env python3
"""Synthetic HTTP acceptance inside the same egress-disabled Docker network."""
import hashlib
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


def request(base,path,body=None,headers=None,opener=None):
    req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type":"application/json",**(headers or {})})
    try:
        with (opener or urllib.request.build_opener()).open(req,timeout=10) as response:
            return response.status,response.read(),dict(response.headers)
    except urllib.error.HTTPError as response:return response.code,response.read(),dict(response.headers)


def main():
    base='http://app:8080';front='http://frontend:80'
    for _ in range(120):
        try:
            code,body,_=request(base,'/actuator/health')
            if code==200 and json.loads(body)['status']=='UP':break
        except (OSError,ValueError):pass
        time.sleep(2)
    else:raise RuntimeError('Clean full image failed startup/health')
    assert request(base,'/api/v1/finance/shops')[0]==401
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    assert request(base,'/api/v1/auth/login',{'organizationCode':'heyi','phone':'13800000000',
        'password':os.environ['HEYI_SMOKE_PASSWORD']},opener=opener)[0]==200
    assert request(base,'/api/v1/finance/shops',opener=opener)[0]==200
    manifest=json.loads(Path('/qa/release.json').read_text())
    for path,digest in manifest['images']['frontend']['smokeAssets'].items():
        code,body,_=request(front,'/'+path)
        assert code==200 and hashlib.sha256(body).hexdigest()==digest,path
    control=Path('/run/heyi-ops');marker=control/'maintenance.json'
    marker.write_text('{"owner":"ci"}')
    try:
        for path in ['/api/v1/finance/shops','/api/v1/auth/login','/callback/test']:
            code,body,headers=request(base,path,{})
            assert code==503 and headers['Retry-After']=='30' and json.loads(body)['success'] is False
        for path in ['/api/v1/auth/login','/erphoyee/','/downloads/test']:
            code,body,headers=request(front,path,{})
            assert code==503 and headers['Retry-After']=='30' and json.loads(body)['success'] is False,path
        assert request(front,'/_heyi/nginx-status')[0]==403
        assert request(front,'/_heyi/ops/status')[0]==404
        assert request(base,'/actuator/health')[0]==200
        code,body,_=request(base,'/_heyi/ops/status',headers={'X-Heyi-Ops-Token':(control/'status-token').read_text()})
        assert code==200 and json.loads(body)['maintenance'] is True
    finally:marker.unlink(missing_ok=True)
    assert request(base,'/api/v1/finance/shops')[0]==401
    print('Clean full images: migrations, health, auth, synthetic read, static files and maintenance passed')


if __name__=='__main__':main()
