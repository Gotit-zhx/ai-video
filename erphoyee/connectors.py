"""Explicit opt-in official authorization adapters. No platform write operations."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def availability():
    return {p: bool(os.environ.get('ERP_'+p.upper()+'_ENABLED')=='true' and all(os.environ.get(k) for k in keys)) for p,keys in {'shopee':['ERP_SHOPEE_PARTNER_ID','ERP_SHOPEE_PARTNER_KEY','ERP_TOKEN_KEY'],'tiktok':['ERP_TIKTOK_APP_KEY','ERP_TIKTOK_APP_SECRET','ERP_TIKTOK_SERVICE_ID','ERP_TOKEN_KEY']}.items()}


def shopee_sign(partner_id,key,path,timestamp,token='',shop_id=''):
    return hmac.new(key.encode(),f'{partner_id}{path}{timestamp}{token}{shop_id}'.encode(),hashlib.sha256).hexdigest()


def tiktok_sign(secret,path,params,body=''):
    canonical=path+''.join(str(k)+str(params[k]) for k in sorted(params) if k not in ('sign','access_token'))+body
    return hmac.new(secret.encode(),(secret+canonical+secret).encode(),hashlib.sha256).hexdigest()


def checked(platform):
    from server import Problem
    if not availability().get(platform):raise Problem('官方连接尚未开放。应用密钥、接口权限及平台联调完成后才能授权真实店铺。',409)
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        key=base64.urlsafe_b64decode(os.environ['ERP_TOKEN_KEY'])
        if len(key)!=32:raise ValueError()
        return AESGCM(key)
    except Exception:raise Problem('授权密钥配置异常，请联系管理员。',503)


def authorization_url(db,workspace,session,platform):
    from server import ORIGIN,PREFIX,hashed
    checked(platform)
    raw=secrets.token_urlsafe(32)
    db.execute('INSERT INTO oauth_states VALUES(?,?,?,?,?)',(hashed(raw),workspace,platform,session,int(time.time())+600))
    if platform=='shopee':
        host='https://open.shopee.com/auth'
        if os.environ.get('ERP_SHOPEE_SANDBOX')=='true':host='https://open.sandbox.test-stable.shopee.com/auth'
        return host+'?'+urlencode(dict(partner_id=os.environ['ERP_SHOPEE_PARTNER_ID'],auth_type='seller',redirect_uri=ORIGIN+PREFIX+'/callback/shopee',response_type='code',state=raw))
    host='https://services.us.tiktokshop.com' if os.environ.get('ERP_TIKTOK_REGION')=='US' else 'https://services.tiktokshop.com'
    return host+'/open/authorize?'+urlencode(dict(service_id=os.environ['ERP_TIKTOK_SERVICE_ID'],state=raw))


def request_json(url,payload=None,headers=None):
    from server import Problem
    raw=json.dumps(payload,separators=(',',':')).encode() if payload is not None else None
    request=Request(url,data=raw,headers=dict({'Content-Type':'application/json','Accept':'application/json'},**(headers or {})))
    try:
        with urlopen(request,timeout=18) as response:
            data=json.loads(response.read(2*1024*1024))
        if not isinstance(data,dict):raise ValueError()
        if data.get('error') or data.get('code') not in (None,0,'0'):raise ValueError()
        return data
    except Exception:
        # Do not reflect remote bodies or URLs; token URLs contain secrets.
        raise Problem('平台未完成授权请求。请检查应用权限和配置，再重新发起授权。',503)


def callback(db,workspace,session,platform,query):
    from server import Problem,hashed,put,items,event,now
    cipher=checked(platform)
    value=lambda key: query.get(key,[''])[0]
    state=value('state')
    row=db.execute('DELETE FROM oauth_states WHERE state=? AND workspace=? AND platform=? AND session=? AND expires>? RETURNING state',(hashed(state),workspace,platform,session,int(time.time()))).fetchone()
    db.commit()  # atomic single use, including upstream failures
    if not row:raise Problem('授权链接已过期或不属于当前会话，请重新连接。',403)
    code=value('code')
    if not code or code=='null' or value('error'):raise Problem('店铺未授予访问权限，请重新发起授权。')
    if len(code)>4096:raise Problem('无效授权码。')
    if platform=='shopee':
        if not value('shop_id').isdigit():raise Problem('首版仅支持店铺级授权，请选择具体店铺。')
        shop_id=int(value('shop_id')); partner=int(os.environ['ERP_SHOPEE_PARTNER_ID']);key=os.environ['ERP_SHOPEE_PARTNER_KEY'];t=int(time.time())
        host='https://partner.test-stable.shopeemobile.com' if os.environ.get('ERP_SHOPEE_SANDBOX')=='true' else 'https://partner.shopeemobile.com'
        path='/api/v2/auth/token/get'
        params=dict(partner_id=partner,timestamp=t,sign=shopee_sign(partner,key,path,t))
        token=request_json(host+path+'?'+urlencode(params),dict(code=code,partner_id=partner,shop_id=shop_id))
        if not token.get('access_token') or not token.get('refresh_token'):raise Problem('平台未返回完整授权凭证。',503)
        path='/api/v2/shop/get_shop_info';t=int(time.time())
        params=dict(partner_id=partner,timestamp=t,access_token=token['access_token'],shop_id=shop_id,sign=shopee_sign(partner,key,path,t,token['access_token'],shop_id))
        info=request_json(host+path+'?'+urlencode(params))
        shops=[dict(external_id=str(shop_id),name=info.get('shop_name','Shopee shop'),region=info.get('region',''),platform='shopee')]
    else:
        key=os.environ['ERP_TIKTOK_APP_KEY'];secret=os.environ['ERP_TIKTOK_APP_SECRET']
        response=request_json('https://auth.tiktok-shops.com/api/v2/token/get?'+urlencode(dict(app_key=key,app_secret=secret,auth_code=code,grant_type='authorized_code')))
        token=response.get('data',{})
        if not token.get('access_token') or not token.get('refresh_token'):raise Problem('平台未返回完整授权凭证。',503)
        path='/authorization/202309/shops';params=dict(app_key=key,timestamp=int(time.time()))
        params['sign']=tiktok_sign(secret,path,params)
        response=request_json('https://open-api.tiktokglobalshop.com'+path+'?'+urlencode(params),headers={'x-tts-access-token':token['access_token']})
        shops=[dict(external_id=str(s['id']),name=s.get('name','TikTok Shop'),region=s.get('region',''),platform='tiktok',shop_cipher=s.get('cipher')) for s in response.get('data',{}).get('shops',[])]
        if not shops:raise Problem('未发现可访问的店铺。请检查授权范围。',409)
    for shop in shops:
        # Keep shop cipher and token encrypted and out of public shop records.
        shop_token=dict(token,shop_cipher=shop.pop('shop_cipher',None),obtained_at=int(time.time()))
        existing=next((s for s in items(db,workspace,'shops') if s.get('external_id')==shop['external_id'] and s['platform']==platform),None)
        record=dict(shop,status='authorized',last_sync=now())
        if existing:
            record_id=existing['id'];db.execute('UPDATE records SET payload=?,version=version+1 WHERE id=?',(json.dumps(record,ensure_ascii=False),record_id))
        else:record_id=put(db,workspace,'shops',record)
        nonce=secrets.token_bytes(12)
        encrypted=base64.urlsafe_b64encode(nonce+cipher.encrypt(nonce,json.dumps(shop_token).encode(),(workspace+':'+record_id).encode())).decode()
        db.execute('INSERT INTO credentials VALUES(?,?) ON CONFLICT(shop) DO UPDATE SET encrypted=excluded.encrypted',(record_id,encrypted))
        event(db,workspace,'shop.authorized',shop['name']+' 官方店铺授权已接收；业务同步与写入需完成联调后启用。')
