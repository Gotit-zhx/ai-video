"""HOYEE ERP: isolated WSGI application, SQLite storage, no existing app dependencies."""
import csv
import sys
sys.modules.setdefault("server", sys.modules[__name__])
import hashlib
import hmac
import io
import json
import mimetypes
import os
import re
import secrets
import sqlite3
import time
from datetime import datetime, timezone
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.parse import parse_qs

BASE = Path(__file__).resolve().parent
DATA = Path(os.environ.get('ERP_DATA_DIR', str(BASE / '.data')))
DATA.mkdir(parents=True, exist_ok=True)
DB = DATA / 'erp.sqlite3'
PREFIX = '/erphoyee'
ORIGIN = os.environ.get('ERP_ORIGIN', 'https://hoyee.org.cn').rstrip('/')
COOKIE = 'hoyee_erp_session'
SECURE = os.environ.get('ERP_COOKIE_SECURE', 'true') == 'true'
SESSION_TTL = 86400

class Problem(Exception):
    def __init__(self, message, status=400):
        self.message, self.status = message, status

def connect():
    db = sqlite3.connect(DB, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    return db

def now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')

def uid():
    return secrets.token_hex(12)

def hashed(value):
    return hashlib.sha256(value.encode()).hexdigest()

def password_hash(password, salt):
    return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 600000).hex()

def init_db():
    with connect() as db:
        db.execute('PRAGMA journal_mode=WAL')
        db.executescript('''
        CREATE TABLE IF NOT EXISTS workspaces(id TEXT PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL, created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY, salt TEXT NOT NULL, password TEXT NOT NULL, workspace TEXT REFERENCES workspaces(id));
        CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, workspace TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY, workspace TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, kind TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1);
        CREATE INDEX IF NOT EXISTS record_workspace ON records(workspace,kind);
        CREATE UNIQUE INDEX IF NOT EXISTS product_unique_sku ON records(workspace,json_extract(payload,'$.sku')) WHERE kind='products';
        CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, workspace TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, action TEXT NOT NULL, detail TEXT NOT NULL, created TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS tickets(id TEXT PRIMARY KEY, email TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL, created TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS limits(bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS oauth_states(state TEXT PRIMARY KEY, workspace TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, platform TEXT NOT NULL, session TEXT NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS credentials(shop TEXT PRIMARY KEY REFERENCES records(id) ON DELETE CASCADE, encrypted TEXT NOT NULL);
        ''')
        db.execute('INSERT OR IGNORE INTO workspaces VALUES(?,?,?,?)', ('owner', 'HOYEE 运营中心', 'owner', int(time.time())))
        if not db.execute('SELECT 1 FROM users LIMIT 1').fetchone():
            password = os.environ.get('ERP_ADMIN_PASSWORD')
            if password:
                if len(password) < 16:
                    raise RuntimeError('ERP_ADMIN_PASSWORD must contain at least 16 characters')
                salt = secrets.token_hex(16)
                db.execute('INSERT INTO users VALUES(?,?,?,?)', ('admin', salt, password_hash(password, salt), 'owner'))

def event(db, workspace, action, detail):
    db.execute('INSERT INTO events VALUES(?,?,?,?,?)', (uid(), workspace, action, detail, now()))
    db.execute('DELETE FROM events WHERE workspace=? AND id NOT IN (SELECT id FROM events WHERE workspace=? ORDER BY created DESC LIMIT 500)', (workspace, workspace))

def put(db, workspace, kind, payload):
    key = uid()
    db.execute('INSERT INTO records VALUES(?,?,?,?,1)', (key, workspace, kind, json.dumps(payload, ensure_ascii=False)))
    return key

def items(db, workspace, kind):
    return [dict(json.loads(r['payload']), id=r['id'], version=r['version']) for r in db.execute('SELECT * FROM records WHERE workspace=? AND kind=? ORDER BY rowid DESC', (workspace, kind))]

def seed(db, workspace):
    shops = []
    for platform, region, label in [('shopee', 'SG', 'HOYEE Living · SG'), ('tiktok', 'SG', 'HOYEE Studio · SG')]:
        shops.append(put(db, workspace, 'shops', dict(name=label, platform=platform, region=region, status='demo', last_sync=None)))
    products = [('Linen Everyday Tote', 'HY-BG-001', 29.90, 86, 20, 'bag', '日常亚麻托特包'), ('Ceramic Morning Mug', 'HY-HM-002', 18.50, 12, 20, 'mug', '晨间陶瓷马克杯'), ('Essential Cotton Tee', 'HY-AP-003', 35.00, 124, 30, 'tee', '经典纯棉 T 恤'), ('Portable Table Light', 'HY-HM-004', 49.00, 8, 15, 'lamp', '便携暖光台灯'), ('Everyday Water Bottle', 'HY-SP-005', 24.90, 64, 20, 'bottle', '轻量随行水杯'), ('Canvas Weekend Bag', 'HY-BG-006', 59.00, 43, 10, 'bag', '周末帆布旅行袋')]
    ids = []
    for i, (name, sku, price, stock, threshold, art, cn) in enumerate(products):
        ids.append(put(db, workspace, 'products', dict(name=cn, english=name, sku=sku, price=price, stock=stock, threshold=threshold, art=art, currency='SGD', shop_id=shops[i % 2], status='draft', updated=now())))
    for i in range(12):
        p = products[i % len(products)]
        put(db, workspace, 'orders', dict(number=f'DEMO-{2609001+i}', product_id=ids[i%6], product_name=p[6], sku=p[1], shop_id=shops[i % 2], quantity=1+(i%2), total=round(p[2]*(1+i%2),2), currency='SGD', status=['pending','pending','processing','shipped','completed','completed'][i%6], customer=f'Demo customer {i+1:02}', tracking='DEMO-TRACKING' if i%6>=3 else '', created=datetime.fromtimestamp(time.time()-(i//2)*86400, timezone.utc).isoformat(timespec='seconds')))
    event(db, workspace, 'workspace.created', '独立演示工作区已创建；所有商品与订单均为合成示例数据。')

def cleanup(db):
    t = int(time.time())
    db.execute('DELETE FROM sessions WHERE expires<?', (t,))
    db.execute("DELETE FROM workspaces WHERE mode='demo' AND created<?", (t-SESSION_TTL,))
    db.execute('DELETE FROM limits WHERE expires<?', (t,))
    db.execute('DELETE FROM oauth_states WHERE expires<?', (t,))
    cutoff=datetime.fromtimestamp(t-90*86400, timezone.utc).isoformat(timespec='seconds')
    db.execute('DELETE FROM tickets WHERE created<?', (cutoff,))

def rate_limit(db, env, name, maximum=12):
    ip = env.get('HTTP_X_REAL_IP', env.get('REMOTE_ADDR', 'unknown'))
    bucket = hashed(ip+name) + ':' + str(int(time.time())//60)
    db.execute('INSERT INTO limits VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1', (bucket, int(time.time())+120))
    count=db.execute('SELECT count FROM limits WHERE bucket=?',(bucket,)).fetchone()[0]
    db.commit()
    if count > maximum:
        raise Problem('操作过于频繁，请一分钟后重试。 / Too many requests.',429)

def current(db, env):
    cookie = SimpleCookie()
    try: cookie.load(env.get('HTTP_COOKIE',''))
    except Exception: raise Problem('请重新登录。',401)
    raw = cookie[COOKIE].value if COOKIE in cookie else ''
    token=hashed(raw)
    row=db.execute('SELECT w.*,s.expires FROM sessions s JOIN workspaces w ON w.id=s.workspace WHERE s.token=? AND s.expires>?',(token,int(time.time()))).fetchone()
    if not row: raise Problem('登录已过期，请重新进入工作台。 / Please sign in.',401)
    return dict(row), token

def cookie_header(raw, age=SESSION_TTL):
    return ('Set-Cookie',f'{COOKIE}={raw}; Path={PREFIX}; HttpOnly; SameSite=Lax; Max-Age={age}'+('; Secure' if SECURE else ''))

def body(env):
    try: length = int(env.get('CONTENT_LENGTH') or 0)
    except ValueError: raise Problem('Invalid Content-Length')
    if length > 32768: raise Problem('请求内容过大。',413)
    if not env.get('CONTENT_TYPE','').startswith('application/json'): raise Problem('Content-Type must be application/json',415)
    try: data=json.loads(env['wsgi.input'].read(length) or b'{}')
    except (ValueError,UnicodeError): raise Problem('Invalid JSON')
    if not isinstance(data,dict): raise Problem('Expected an object')
    return data

def textfield(data, key, maximum=120, optional=False):
    value=data.get(key, '')
    if not isinstance(value,str): raise Problem(f'{key}: 必须是文本。')
    value=value.strip()
    if len(value)>maximum or (not value and not optional): raise Problem(f'{key}: 请填写有效内容（最多 {maximum} 字）。')
    return value

def integer(data,key,low=0,high=1000000):
    v=data.get(key)
    if isinstance(v,bool) or not isinstance(v,int) or not low<=v<=high: raise Problem(f'{key}: 请输入 {low}–{high} 的整数。')
    return v

def require_origin(env):
    if env.get('HTTP_ORIGIN') != ORIGIN or env.get('HTTP_X_ERPHOYEE') != '1': raise Problem('请求来源校验失败。',403)

def api(env, path, method):
    headers=[]
    with connect() as db:
        if method=='GET' and path=='/health':
            db.execute('SELECT 1').fetchone()
            return {'status':'ok','service':'erphoyee'},headers
        if method not in ('GET','HEAD'): require_origin(env)
        data=body(env) if method in ('POST','PATCH','DELETE') else {}
        if method=='POST' and path in ('/demo','/login'):
            cleanup(db)
            rate_limit(db,env,path,8)
            if path=='/demo':
                if db.execute("SELECT count(*) FROM workspaces WHERE mode='demo'").fetchone()[0]>=2000: raise Problem('演示容量已满，请稍后再试。',503)
                workspace=uid()
                db.execute('INSERT INTO workspaces VALUES(?,?,?,?)',(workspace,'HOYEE 演示空间','demo',int(time.time())))
                seed(db,workspace)
            else:
                name=textfield(data,'username',80)
                pwd=textfield(data,'password',200)
                user=db.execute('SELECT * FROM users WHERE name=?',(name,)).fetchone()
                salt=user['salt'] if user else '00'*16
                candidate=password_hash(pwd,salt)
                if not user or not hmac.compare_digest(candidate,user['password']): raise Problem('账号或密码不正确。',401)
                workspace=user['workspace']
                event(db,workspace,'session.login','管理员登录')
            raw=secrets.token_urlsafe(32)
            db.execute('INSERT INTO sessions VALUES(?,?,?)',(hashed(raw),workspace,int(time.time())+SESSION_TTL))
            return {'ok':True},[cookie_header(raw)]
        if method=='POST' and path=='/support':
            rate_limit(db,env,'support',4)
            email=textfield(data,'email',180)
            if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',email): raise Problem('请输入有效邮箱。')
            if data.get('consent') is not True: raise Problem('请同意隐私政策后提交。')
            category=textfield(data,'category',40)
            if category not in ('support','onboarding','privacy','deletion'): raise Problem('Invalid category')
            message=textfield(data,'message',3000)
            ticket='HY-'+secrets.token_hex(5).upper()
            db.execute('INSERT INTO tickets VALUES(?,?,?,?,?,?)',(ticket,email,category,message,'open',now()))
            return {'ticket':ticket},headers
        ws,session=current(db,env)
        workspace=ws['id']
        if method=='GET' and path=='/bootstrap':
            from connectors import availability
            return {'workspace':ws,'shops':items(db,workspace,'shops'),'products':items(db,workspace,'products'),'orders':items(db,workspace,'orders'),'events':[dict(r) for r in db.execute('SELECT action,detail,created FROM events WHERE workspace=? ORDER BY rowid DESC LIMIT 100',(workspace,))],'connectors':availability(),'tickets':[dict(r) for r in db.execute('SELECT * FROM tickets ORDER BY rowid DESC LIMIT 100')] if ws['mode']=='owner' else []},headers
        if method=='POST' and path=='/logout':
            db.execute('DELETE FROM sessions WHERE token=?',(session,))
            return {'ok':True},[cookie_header('',0)]
        if method=='POST' and path=='/reset':
            if ws['mode']!='demo': raise Problem('仅演示空间可重置。',403)
            rate_limit(db,env,'reset',6)
            db.execute('DELETE FROM records WHERE workspace=?',(workspace,))
            db.execute('DELETE FROM events WHERE workspace=?',(workspace,))
            seed(db,workspace)
            return {'ok':True},headers
        if method=='DELETE' and path=='/workspace':
            if data.get('confirmation')!='DELETE': raise Problem('请输入 DELETE 确认删除。')
            if ws['mode']=='demo':
                db.execute('DELETE FROM workspaces WHERE id=?',(workspace,))
                return {'ok':True},[cookie_header('',0)]
            db.execute('DELETE FROM records WHERE workspace=?',(workspace,))
            db.execute('DELETE FROM oauth_states WHERE workspace=?',(workspace,))
            db.execute('DELETE FROM events WHERE workspace=?',(workspace,))
            event(db,workspace,'data.deleted','管理员已删除本工作区的店铺、商品、订单、授权凭证与历史业务记录。')
            return {'ok':True},headers
        if method=='POST' and path.startswith('/authorize/'):
            if ws['mode']!='owner': raise Problem('演示空间不接受真实店铺授权。请由管理员在正式工作区连接。',403)
            from connectors import authorization_url
            return {'url':authorization_url(db,workspace,session,path.split('/')[-1])},headers
        if method=='POST' and path=='/shops':
            if len(items(db,workspace,'shops'))>=30: raise Problem('最多创建 30 个店铺档案。')
            name=textfield(data,'name',80); platform=textfield(data,'platform',12)
            if platform not in ('shopee','tiktok'): raise Problem('请选择 Shopee 或 TikTok Shop。')
            region=textfield(data,'region',5)
            if region not in ('SG','MY','TH','VN','PH','ID','TW','BR','US','GB'): raise Problem('不支持的站点。')
            key=put(db,workspace,'shops',dict(name=name,platform=platform,region=region,status='demo' if ws['mode']=='demo' else 'unconnected',last_sync=None))
            event(db,workspace,'shop.created',f'创建店铺档案：{name}（尚未获得平台授权）')
            return {'id':key},headers
        if method=='POST' and path=='/products':
            if len(items(db,workspace,'products'))>=500: raise Problem('首版工作区最多 500 个商品。')
            payload=validate_product(db,workspace,data)
            key=put(db,workspace,'products',payload)
            event(db,workspace,'product.created','创建本地商品草稿：'+payload['sku'])
            return {'id':key},headers
        match=re.fullmatch(r'/(products|orders|shops)/([a-f0-9]{24})',path)
        if match:
            kind,key=match.groups()
            row=db.execute('SELECT * FROM records WHERE id=? AND workspace=? AND kind=?',(key,workspace,kind)).fetchone()
            if not row: raise Problem('记录不存在。',404)
            record=json.loads(row['payload'])
            if method=='DELETE' and kind=='shops':
                if data.get('confirmation')!='DISCONNECT': raise Problem('请确认断开连接。')
                record['status']='disconnected'; record['last_sync']=None
                db.execute('DELETE FROM credentials WHERE shop=?',(key,))
                db.execute('UPDATE records SET payload=?,version=version+1 WHERE id=?',(json.dumps(record,ensure_ascii=False),key))
                event(db,workspace,'shop.disconnected','已断开连接并删除本地凭证：'+record['name'])
                return {'ok':True},headers
            if method=='PATCH':
                if data.get('version')!=row['version']: raise Problem('记录已更新，请刷新后重试。',409)
                if kind=='products':
                    oldstock=record['stock']
                    updated=validate_product(db,workspace,data,key)
                    event(db,workspace,'inventory.updated',f"{record['sku']}：库存 {oldstock} → {updated['stock']}；本地记录，未推送平台。")
                    record=updated
                elif kind=='orders':
                    if ws['mode']!='demo': raise Problem('当前版本仅支持演示订单履约；正式平台写操作尚未开放。',409)
                    transition={'pending':'processing','processing':'shipped','shipped':'completed'}
                    target=data.get('status')
                    if transition.get(record['status'])!=target: raise Problem('无效订单状态转换。',409)
                    tracking=textfield(data,'tracking',80,optional=target!='shipped')
                    record['status']=target
                    if target=='shipped':record['tracking']=tracking
                    event(db,workspace,'order.updated',record['number']+' → '+target+'（模拟履约，未提交平台）')
                else: raise Problem('不支持该操作。',405)
                updated=db.execute('UPDATE records SET payload=?,version=version+1 WHERE id=? AND version=?',(json.dumps(record,ensure_ascii=False),key,row['version']))
                if updated.rowcount!=1:raise Problem('记录已更新，请刷新后重试。',409)
                return {'ok':True},headers
        if method=='POST' and path.startswith('/tickets/') and ws['mode']=='owner':
            key=path.rsplit('/',1)[-1]
            if data.get('status') not in ('open','closed'):raise Problem('Invalid status')
            if not db.execute('UPDATE tickets SET status=? WHERE id=?',(data['status'],key)).rowcount:raise Problem('工单不存在。',404)
            return {'ok':True},headers
        if method=='GET' and path in ('/export/products','/export/orders'):
            kind=path.rsplit('/',1)[-1]
            columns=['sku','name','price','currency','stock','threshold','status'] if kind=='products' else ['number','product_name','quantity','total','currency','status','tracking']
            out=io.StringIO(); writer=csv.writer(out); writer.writerow(columns)
            for row in items(db,workspace,kind):
                values=[]
                for key in columns:
                    value=str(row.get(key,''))
                    if value.lstrip().startswith(('=','+','-','@','\t','\r')):value="'"+value
                    values.append(value)
                writer.writerow(values)
            event(db,workspace,'data.exported',kind+' CSV 导出')
            return ('\ufeff'+out.getvalue()).encode(), [('Content-Type','text/csv; charset=utf-8'),('Content-Disposition',f'attachment; filename="hoyee-{kind}.csv"')]
        raise Problem('接口不存在。',404)

def validate_product(db,workspace,data,key=None):
    name=textfield(data,'name',120);sku=textfield(data,'sku',60)
    try:
        price=float(data.get('price',-1))
        if not 0<=price<=1000000: raise ValueError()
    except (ValueError,TypeError):raise Problem('价格必须是 0–1000000 的有效数字。')
    shop=textfield(data,'shop_id',40)
    if not db.execute("SELECT 1 FROM records WHERE id=? AND workspace=? AND kind='shops'",(shop,workspace)).fetchone():raise Problem('请选择本工作区店铺。')
    for p in items(db,workspace,'products'):
        if p['sku']==sku and p['id']!=key:raise Problem('SKU 已存在。',409)
    currency=data.get('currency','SGD')
    if currency not in ('SGD','USD','MYR','THB','VND','PHP','IDR','TWD','BRL','GBP'):raise Problem('无效币种。')
    return dict(name=name,english=textfield(data,'english',120,True),sku=sku,price=round(price,2),stock=integer(data,'stock'),threshold=integer(data,'threshold'),currency=currency,shop_id=shop,status='draft',art=data.get('art') if data.get('art') in ('bag','mug','tee','lamp','bottle') else 'bag',updated=now())

STATUS={200:'OK',302:'Found',400:'Bad Request',401:'Unauthorized',403:'Forbidden',404:'Not Found',405:'Method Not Allowed',409:'Conflict',413:'Content Too Large',415:'Unsupported Media Type',429:'Too Many Requests',500:'Internal Server Error',503:'Service Unavailable'}

def application(env,start_response):
    status=200; headers=[]
    try:
        method=env['REQUEST_METHOD']; path=env.get('PATH_INFO','')
        if path==PREFIX:
            status=302; headers=[('Location',PREFIX+'/')]; result=b''
        elif path.startswith(PREFIX+'/api/'):
            result,headers=api(env,path[len(PREFIX+'/api'):],method)
        elif path in (PREFIX+'/callback/shopee',PREFIX+'/callback/tiktok') and method=='GET':
            from connectors import callback
            with connect() as db:
                ws,session=current(db,env)
                callback(db,ws['id'],session,path.rsplit('/',1)[-1],parse_qs(env.get('QUERY_STRING','')))
            status=302;headers=[('Location',PREFIX+'/app#shops')];result=b''
        elif method in ('GET','HEAD'):
            pages={'/':'index.html','/app':'app.html','/login':'login.html','/privacy':'legal.html','/terms':'legal.html','/data-deletion':'legal.html','/support':'support.html','/guide':'legal.html'}
            relative=path[len(PREFIX):] if path.startswith(PREFIX+'/') else ''
            filename=pages.get(relative)
            if not filename and relative.startswith('/assets/') and re.fullmatch(r'/assets/[a-zA-Z0-9_.-]+',relative): filename=relative[len('/assets/'):]
            allowed=BASE/'public'/filename if filename else None
            if not allowed or not allowed.is_file():raise Problem('页面不存在。',404)
            result=allowed.read_bytes();headers=[('Content-Type',mimetypes.guess_type(str(allowed))[0] or 'application/octet-stream')]
        else:raise Problem('不支持该操作。',405)
    except sqlite3.IntegrityError:
        status=409;result={'error':'记录冲突，请检查 SKU 是否重复并刷新后重试。'}
    except Problem as exc:
        status=exc.status;result={'error':exc.message}
    except Exception:
        # Never log query strings, cookies, tokens or payloads.
        import traceback
        print('ERP request failed: '+str(env.get('PATH_INFO','')).split('?')[0], flush=True)
        print("Internal error type: "+sys.exc_info()[0].__name__, flush=True)
        status=500;result={'error':'服务暂时不可用，请稍后重试。'}
    if not isinstance(result,bytes):
        result=json.dumps(result,ensure_ascii=False).encode()
        headers.append(('Content-Type','application/json; charset=utf-8'))
    headers.extend([('Cache-Control','no-store'),('X-Content-Type-Options','nosniff'),('Content-Length',str(len(result))),('Referrer-Policy','no-referrer')])
    start_response(f'{status} {STATUS.get(status,"Error")}',headers)
    return [b'' if env.get('REQUEST_METHOD')=='HEAD' else result]

init_db()

if __name__=='__main__':
    import threading
    def maintenance():
        while True:
            try:
                with connect() as db: cleanup(db)
            except Exception:
                print('ERP maintenance failed; retrying later', flush=True)
            time.sleep(300)
    threading.Thread(target=maintenance,daemon=True).start()
    try:
        from waitress import serve
        serve(application,host=os.environ.get('ERP_BIND','127.0.0.1'),port=int(os.environ.get('PORT','8137')),threads=6,channel_timeout=30,max_request_body_size=32768,ident='HOYEE ERP')
    except ImportError:
        from wsgiref.simple_server import make_server, WSGIRequestHandler
        class QuietHandler(WSGIRequestHandler):
            def log_message(self, format, *args):pass
        with make_server('127.0.0.1',int(os.environ.get('PORT','8137')),application,handler_class=QuietHandler) as httpd:
            httpd.serve_forever()
