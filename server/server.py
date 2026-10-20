"""저녁득템 데모 API. Python 3.11+, SQLite, 외부 과금 없음."""
import os, json, sqlite3, secrets, hashlib, hmac, time, uuid
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlsplit, parse_qs
from contextlib import contextmanager

DB = os.environ.get('DEALS_DB', os.path.join(os.path.dirname(__file__), 'deals.db'))
KST = timezone(timedelta(hours=9))

class Problem(Exception):
    def __init__(self, message, status=400, code='VALIDATION_ERROR'):
        self.message, self.status, self.code = message, status, code

def require(ok, message, status=400, code='VALIDATION_ERROR'):
    if not ok: raise Problem(message, status, code)

def password_hash(password, salt):
    return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 200000).hex()

@contextmanager
def transaction():
    db = sqlite3.connect(DB, timeout=15)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    try:
        db.execute('BEGIN IMMEDIATE')
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally: db.close()

def init():
    with transaction() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE,nickname TEXT,salt TEXT,password TEXT,role TEXT,store_id TEXT);
        CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,user_id TEXT,expires REAL);
        CREATE TABLE IF NOT EXISTS deals(id TEXT PRIMARY KEY,store_id TEXT,store TEXT,title TEXT,category TEXT,region TEXT,address TEXT,image TEXT,original INTEGER,price INTEGER,stock INTEGER,start REAL,end REAL,description TEXT,allergens TEXT,stopped INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS holds(id TEXT PRIMARY KEY,user_id TEXT,deal_id TEXT,qty INTEGER,expires REAL,status TEXT);
        CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY,user_id TEXT,deal_id TEXT,hold_id TEXT UNIQUE,qty INTEGER,total INTEGER,title TEXT,store TEXT,start REAL,end REAL,status TEXT,code TEXT UNIQUE,token TEXT UNIQUE,version INTEGER DEFAULT 1);
        CREATE TABLE IF NOT EXISTS requests(user_id TEXT,operation TEXT,key TEXT,body TEXT,result TEXT,PRIMARY KEY(user_id,operation,key));
        CREATE TABLE IF NOT EXISTS favorites(user_id TEXT,store_id TEXT,PRIMARY KEY(user_id,store_id));
        CREATE TABLE IF NOT EXISTS reviews(order_id TEXT PRIMARY KEY,user_id TEXT,rating INTEGER,content TEXT);
        CREATE TABLE IF NOT EXISTS notifications(id TEXT PRIMARY KEY,user_id TEXT,order_id TEXT,title TEXT,body TEXT,created REAL,read INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS preferences(user_id TEXT PRIMARY KEY,body TEXT);
        CREATE TABLE IF NOT EXISTS attempts(user_id TEXT,time REAL);
        CREATE TABLE IF NOT EXISTS reset_tokens(token TEXT PRIMARY KEY,user_id TEXT,expires REAL,used INTEGER DEFAULT 0);
        ''')
        for email,nick,role,store in [('customer@demo.kr','득템러','customer',None),('store@demo.kr','온기 베이커리 사장님','merchant','s1'),('other@demo.kr','다른 고객','customer',None)]:
            salt=secrets.token_hex(16)
            db.execute('INSERT OR IGNORE INTO users VALUES(?,?,?,?,?,?,?)',(email,email,nick,salt,password_hash('Demo1234!',salt),role,store))
        # Add a fresh demo day when every prior offer has ended; never rewrite old orders.
        if db.execute('SELECT COUNT(*) FROM deals WHERE end>?',(time.time(),)).fetchone()[0] == 0:
            day=datetime.now(KST).replace(hour=19,minute=0,second=0,microsecond=0)
            if day.timestamp()+3600 <= time.time(): day += timedelta(days=1)
            seeds=[('d1','s1','온기 베이커리','오늘의 빵 세트','베이커리',12000,6000,5,'photo-1710220986601-f0b5568673c6','크루아상 2개, 소금빵 2개, 오늘 구운 식빵 반 개로 구성된 세트입니다.','밀, 우유, 계란'),('d2','s2','한끼의 온도','정성 가득 한끼 도시락','도시락',14000,6900,8,'photo-1553163147-622ab57be1c7','갓 지은 밥과 제철 채소, 닭고기 반찬으로 준비한 든든한 한 끼입니다.','대두, 밀, 계란'),('d3','s3','그린 테이블','하루 채소 샐러드','샐러드',11000,4900,3,'photo-1512621776951-a57141f2eefd','신선한 잎채소와 방울토마토, 견과류, 드레싱으로 구성됩니다.','견과류, 대두')]
            for i,s in enumerate(seeds):
                id,store_id,store,title,cat,original,price,stock,img,desc,allergens=s
                if db.execute('SELECT 1 FROM deals WHERE id=?',(id,)).fetchone():
                    id=id+'-'+day.strftime('%Y%m%d')+'-'+secrets.token_hex(3)
                db.execute('INSERT INTO deals VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)',(id,store_id,store,title,cat,'성수동',f'서울 성동구 성수이로 {20+i*12}',f'https://images.unsplash.com/{img}?w=900&q=85&fit=crop',original,price,stock,day.timestamp()+i*900,day.timestamp()+3600+i*900,desc,allergens))

def user(db, token):
    r=db.execute('SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token=? AND s.expires>?',(token,time.time())).fetchone()
    require(r is not None,'로그인이 필요해요.',401,'UNAUTHENTICATED')
    return dict(r)

def public_user(u): return {k:u[k] for k in ('id','email','nickname','role','store_id')}
def session(db,u):
    token=secrets.token_urlsafe(32)
    db.execute('INSERT INTO sessions VALUES(?,?,?)',(token,u['id'],time.time()+7*86400))
    return {'token':token,'user':public_user(u)}
def notify(db,uid,oid,title,body):
    db.execute('INSERT INTO notifications VALUES(?,?,?,?,?,?,0)',(str(uuid.uuid4()),uid,oid,title,body,time.time()))
def expire(db):
    db.execute("UPDATE holds SET status='expired' WHERE status='active' AND expires<=?",(time.time(),))
    rows=db.execute("SELECT * FROM orders WHERE status='waiting_pickup' AND end<=?",(time.time(),)).fetchall()
    for r in rows:
        db.execute("UPDATE orders SET status='no_show',version=version+1 WHERE id=?",(r['id'],))
        notify(db,r['user_id'],r['id'],'픽업 시간이 종료됐어요','예약 상품이 미수령 처리됐어요. 내역을 확인해 주세요.')

def available(db,d):
    confirmed=db.execute("SELECT COALESCE(SUM(qty),0) FROM orders WHERE deal_id=? AND status!='cancelled'",(d['id'],)).fetchone()[0]
    active=db.execute("SELECT COALESCE(SUM(qty),0) FROM holds WHERE deal_id=? AND status='active' AND expires>?",(d['id'],time.time())).fetchone()[0]
    return max(0,d['stock']-confirmed-active),confirmed,active

def deal_json(db,d):
    r=dict(d); r['available'],r['confirmed'],r['held']=available(db,r)
    r['discount']=(r['original']-r['price'])*100//r['original']; return r

def order_json(db,r,u):
    r=dict(r)
    require(r['user_id']==u['id'],'예약에 접근할 수 없어요.',404)
    r['reviewed']=db.execute('SELECT 1 FROM reviews WHERE order_id=?',(r['id'],)).fetchone() is not None
    r['canCancel']=r['status']=='waiting_pickup' and time.time()<r['start']-1800
    if r['status']!='waiting_pickup': r['token']='';r['code']=''
    return r

def idempotent(db,u,op,b,fn):
    key=b.get('requestKey','')
    require(isinstance(key,str) and 1<=len(key)<=128,'요청 키가 필요해요.')
    body=json.dumps(b,sort_keys=True)
    old=db.execute('SELECT * FROM requests WHERE user_id=? AND operation=? AND key=?',(u['id'],op,key)).fetchone()
    if old:
        require(old['body']==body,'같은 요청 키를 다른 요청에 사용할 수 없어요.',409,'REQUEST_KEY_REUSED')
        return json.loads(old['result'])
    result=fn()
    db.execute('INSERT INTO requests VALUES(?,?,?,?,?)',(u['id'],op,key,body,json.dumps(result)))
    return result

def handle(method,path,b,token=''):
    parsed=urlsplit(path); p=parsed.path; q=parse_qs(parsed.query)
    with transaction() as db:
        expire(db)
        if p=='/health': return {'status':'ok','serverTime':time.time()}
        if p=='/deals' and method=='GET':
            rows=db.execute('SELECT * FROM deals WHERE end>? AND stopped=0 ORDER BY end',(time.time(),)).fetchall()
            return {'items':[deal_json(db,r) for r in rows]}
        if p.startswith('/deals/') and method=='GET':
            r=db.execute('SELECT * FROM deals WHERE id=?',(p.split('/')[-1],)).fetchone();require(r is not None,'상품이 없어요.',404)
            return deal_json(db,r)
        if p=='/auth/login':
            r=db.execute('SELECT * FROM users WHERE email=?',(str(b.get('email','')).lower().strip(),)).fetchone()
            require(r is not None and hmac.compare_digest(r['password'],password_hash(str(b.get('password','')),r['salt'])),'이메일 또는 비밀번호를 확인해 주세요.',401)
            return session(db,dict(r))
        if p=='/auth/signup':
            email=str(b.get('email','')).lower().strip();pw=str(b.get('password',''));nick=str(b.get('nickname','')).strip()
            require('@' in email and '.' in email.split('@')[-1] and len(email)<=254,'이메일 형식을 확인해 주세요.')
            require(8<=len(pw)<=64 and 2<=len(nick)<=20,'닉네임 2~20자, 비밀번호 8~64자를 입력해 주세요.')
            require(b.get('terms') is True and b.get('privacy') is True,'필수 약관에 동의해 주세요.')
            require(not db.execute('SELECT 1 FROM users WHERE email=?',(email,)).fetchone(),'이미 가입된 이메일이에요.',409)
            uid=str(uuid.uuid4());salt=secrets.token_hex(16)
            db.execute('INSERT INTO users VALUES(?,?,?,?,?,?,NULL)',(uid,email,nick,salt,password_hash(pw,salt),'customer'))
            return session(db,dict(db.execute('SELECT * FROM users WHERE id=?',(uid,)).fetchone()))
        if p=='/auth/password-reset-requests':
            require('@' in str(b.get('email','')),'이메일을 확인해 주세요.')
            # No SMTP adapter configured: never claim that mail was sent.
            raise Problem('메일 발송 서버가 설정되지 않았어요. 데모 계정을 이용해 주세요.',503,'MAIL_NOT_CONFIGURED')
        u=user(db,token)
        if p=='/me': return public_user(u)
        if p=='/auth/logout':
            db.execute('DELETE FROM sessions WHERE token=?',(token,));return {'ok':True}
        if p=='/auth/refresh':
            db.execute('DELETE FROM sessions WHERE token=?',(token,));return session(db,u)
        if p=='/favorites/stores':
            if method=='POST': db.execute('INSERT OR IGNORE INTO favorites VALUES(?,?)',(u['id'],b['storeId']))
            ids=[r[0] for r in db.execute('SELECT store_id FROM favorites WHERE user_id=?',(u['id'],))]
            stores=[]
            for sid in ids:
                latest=db.execute('SELECT * FROM deals WHERE store_id=? ORDER BY end DESC LIMIT 1',(sid,)).fetchone()
                if latest: stores.append(deal_json(db,latest))
            return {'items':ids,'stores':stores}
        if p.startswith('/favorites/stores/') and method=='DELETE':
            db.execute('DELETE FROM favorites WHERE user_id=? AND store_id=?',(u['id'],p.split('/')[-1]));return {'ok':True}
        if p=='/stock-holds':
            def hold():
                d=db.execute('SELECT * FROM deals WHERE id=?',(b.get('dealId'),)).fetchone();qty=b.get('quantity',0)
                require(d is not None,'상품이 없어요.',404)
                require(type(qty)==int and 1<=qty<=5,'수량은 1~5개만 선택해 주세요.')
                require(not d['stopped'] and time.time()<d['end'],'예약이 종료된 상품이에요.',409)
                require(available(db,d)[0]>=qty,'남은 수량이 부족해요. 수량을 다시 선택해 주세요.',409,'STOCK_SHORTAGE')
                id=str(uuid.uuid4());expires=min(time.time()+300,d['end'])
                db.execute('INSERT INTO holds VALUES(?,?,?,?,?,?)',(id,u['id'],d['id'],qty,expires,'active'))
                return {'id':id,'expires':expires,'quantity':qty,'dealId':d['id']}
            return idempotent(db,u,'hold',b,hold)
        if p=='/pickup-orders' and method=='POST':
            def confirm():
                h=db.execute('SELECT * FROM holds WHERE id=? AND user_id=?',(b.get('holdId'),u['id'])).fetchone()
                require(h is not None,'선점을 찾을 수 없어요.',404)
                require(h['status']=='active' and h['expires']>time.time(),'선점이 만료됐어요. 수량을 다시 선택해 주세요.',409,'HOLD_EXPIRED')
                require(b.get('policyAgreed') is True,'픽업 및 취소 정책에 동의해 주세요.')
                mode=b.get('paymentMode','success');require(mode in ('success','failure','delay'),'결제 모드를 확인해 주세요.')
                if mode=='failure':
                    db.execute("UPDATE holds SET status='released' WHERE id=?",(h['id'],))
                    return {'paymentFailed':True,'message':'테스트 결제 실패. 선점 수량이 반환됐어요.'}
                d=db.execute('SELECT * FROM deals WHERE id=?',(h['deal_id'],)).fetchone()
                require(time.time()<d['end'],'픽업 시간이 종료됐어요.',409)
                id=str(uuid.uuid4());code=''
                while not code or db.execute('SELECT 1 FROM orders WHERE code=?',(code,)).fetchone(): code=f'{secrets.randbelow(100000000):08d}'
                db.execute('INSERT INTO orders VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1)',(id,u['id'],d['id'],h['id'],h['qty'],d['price']*h['qty'],d['title'],d['store'],d['start'],d['end'],'waiting_pickup',code,secrets.token_urlsafe(32)))
                db.execute("UPDATE holds SET status='confirmed' WHERE id=?",(h['id'],))
                notify(db,u['id'],id,'픽업 예약이 완료됐어요',d['store']+' · 픽업 시간에 방문해 주세요.')
                for staff in db.execute("SELECT id FROM users WHERE role='merchant' AND store_id=?",(d['store_id'],)).fetchall(): notify(db,staff['id'],id,'새 픽업 예약이 들어왔어요',f"{d['title']} {h['qty']}개 예약이 확정됐어요.")
                return order_json(db,db.execute('SELECT * FROM orders WHERE id=?',(id,)).fetchone(),u)
            return idempotent(db,u,'confirm',b,confirm)
        if p=='/pickup-orders' and method=='GET':
            return {'items':[order_json(db,r,u) for r in db.execute('SELECT * FROM orders WHERE user_id=? ORDER BY rowid DESC',(u['id'],)).fetchall()]}
        if p.startswith('/pickup-orders/'):
            oid=p.split('/')[2];r=db.execute('SELECT * FROM orders WHERE id=?',(oid,)).fetchone();require(r is not None,'예약이 없어요.',404)
            result=order_json(db,r,u)
            if p.endswith('/cancel'):
                def cancel():
                    require(result['canCancel'],'픽업 시작 30분 전까지만 취소할 수 있어요.',409)
                    db.execute("UPDATE orders SET status='cancelled',version=version+1 WHERE id=?",(oid,))
                    notify(db,u['id'],oid,'픽업 예약이 취소됐어요','취소된 예약을 내역에서 확인하세요.')
                    return {'ok':True}
                return idempotent(db,u,'cancel',b,cancel)
            return result
        if p=='/reviews':
            r=db.execute('SELECT * FROM orders WHERE id=? AND user_id=?',(b.get('pickupOrderId'),u['id'])).fetchone()
            require(r is not None and r['status']=='picked_up','수령 완료한 본인 예약만 후기를 남길 수 있어요.',403)
            require(type(b.get('rating'))==int and 1<=b['rating']<=5 and 10<=len(str(b.get('content','')).strip())<=500,'별점과 10~500자 후기를 입력해 주세요.')
            require(not db.execute('SELECT 1 FROM reviews WHERE order_id=?',(r['id'],)).fetchone(),'이미 후기를 작성했어요.',409)
            db.execute('INSERT INTO reviews VALUES(?,?,?,?)',(r['id'],u['id'],b['rating'],b['content'].strip()));return {'ok':True}
        if p=='/notifications':
            return {'items':[dict(r) for r in db.execute('SELECT * FROM notifications WHERE user_id=? AND created>? ORDER BY created DESC LIMIT 100',(u['id'],time.time()-30*86400))]}
        if p.startswith('/notifications/'):
            db.execute('UPDATE notifications SET read=1 WHERE id=? AND user_id=?',(p.split('/')[-1],u['id']));return {'ok':True}
        if p=='/me/notification-preferences':
            if method=='PATCH': db.execute('INSERT INTO preferences VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET body=excluded.body',(u['id'],json.dumps(b)))
            r=db.execute('SELECT body FROM preferences WHERE user_id=?',(u['id'],)).fetchone();return json.loads(r[0]) if r else {'pickup_updates':True,'reminders':True,'completion':True,'merchant_ops':True}
        if p.startswith('/merchant/'):
            require(u['role']=='merchant','매장 운영자만 접근할 수 있어요.',403)
            if p=='/merchant/deals' and method=='GET':
                return {'items':[deal_json(db,r) for r in db.execute('SELECT * FROM deals WHERE store_id=? ORDER BY end DESC',(u['store_id'],))]}
            if p=='/merchant/pickups/verify' or p=='/merchant/pickups/complete':
                require(db.execute('SELECT COUNT(*) FROM attempts WHERE user_id=? AND time>?',(u['id'],time.time()-60)).fetchone()[0]<5,'확인 횟수를 초과했어요. 1분 후 다시 시도해 주세요.',429)
                r=db.execute('SELECT o.*,d.store_id FROM orders o JOIN deals d ON d.id=o.deal_id WHERE o.code=? OR o.token=?',(b.get('numericCode',''),b.get('token',''))).fetchone() if p.endswith('verify') else db.execute('SELECT o.*,d.store_id FROM orders o JOIN deals d ON d.id=o.deal_id WHERE o.id=?',(b.get('pickupOrderId'),)).fetchone()
                # Persist invalid attempts separately from transaction rollback via normal result.
                if r is None or r['store_id']!=u['store_id']:
                    db.execute('INSERT INTO attempts VALUES(?,?)',(u['id'],time.time()))
                    return {'verificationFailed':True,'message':'코드가 잘못되었거나 다른 매장 예약이에요.'}
                if p.endswith('complete'):
                    old=db.execute('SELECT * FROM requests WHERE user_id=? AND operation=? AND key=?',(u['id'],'complete',b.get('requestKey',''))).fetchone()
                    if old:
                        require(old['body']==json.dumps(b,sort_keys=True),'같은 요청 키를 다른 요청에 사용할 수 없어요.',409,'REQUEST_KEY_REUSED')
                        return json.loads(old['result'])
                require(r['status']=='waiting_pickup','이미 처리되었거나 사용할 수 없는 코드예요.',409)
                require(r['start']<=time.time()<r['end'],'픽업 시간에만 수령 확인할 수 있어요.',409)
                if p.endswith('verify'): return {k:r[k] for k in ('id','title','qty','store','start','end','version','code')}
                def complete():
                    require(r['version']==b.get('expectedVersion'),'예약 상태가 변경됐어요. 다시 확인해 주세요.',409)
                    db.execute("UPDATE orders SET status='picked_up',version=version+1 WHERE id=?",(r['id'],))
                    notify(db,r['user_id'],r['id'],'상품 수령이 완료됐어요','맛있게 드세요! 픽업내역에서 후기를 남겨 주세요.')
                    return {'ok':True}
                # Replay before state validation is handled by the route precheck below.
                return idempotent(db,u,'complete',b,complete)
            if p.startswith('/merchant/deals'):
                did=p.split('/')[3] if len(p.split('/'))>3 else None
                old=db.execute('SELECT * FROM deals WHERE id=? AND store_id=?',(did,u['store_id'])).fetchone() if did else None
                if did: require(old is not None,'상품에 접근할 수 없어요.',404)
                if p.endswith('/stop'):
                    db.execute('UPDATE deals SET stopped=1 WHERE id=?',(did,));return {'ok':True}
                title=str(b.get('title','')).strip();desc=str(b.get('description','')).strip();allergens=str(b.get('allergens','')).strip()
                original=b.get('original',0);price=b.get('price',0);stock=b.get('stock',0);start=b.get('start',0);end=b.get('end',0)
                require(2<=len(title)<=50 and 10<=len(desc)<=500 and bool(allergens),'상품명 2~50자, 구성 10~500자, 알레르기를 입력해 주세요.')
                require(all(type(v)==int for v in (original,price,stock)) and original>=price>=1 and 1<=stock<=999,'가격과 총수량을 확인해 주세요.')
                require(isinstance(start,(int,float)) and isinstance(end,(int,float)) and start<end and end>time.time(),'픽업 시간을 확인해 주세요.')
                if old:
                    _,confirmed,held=available(db,old)
                    require(stock>=confirmed+held,'확정·선점 수량보다 총수량을 줄일 수 없어요.',409)
                    if confirmed or held:
                        require(all(b.get(k)==old[k] for k in ('title','description','allergens','price','original','start','end')),'예약 또는 선점이 있으면 구성·가격·시간을 변경할 수 없어요.',409)
                    db.execute('UPDATE deals SET title=?,description=?,allergens=?,original=?,price=?,stock=?,start=?,end=? WHERE id=?',(title,desc,allergens,original,price,stock,start,end,did))
                else:
                    did=str(uuid.uuid4())
                    db.execute('INSERT INTO deals VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)',(did,u['store_id'],'온기 베이커리',title,'베이커리','성수동','서울 성동구 성수이로 20','https://images.unsplash.com/photo-1710220986601-f0b5568673c6?w=900',original,price,stock,start,end,desc,allergens))
                return deal_json(db,db.execute('SELECT * FROM deals WHERE id=?',(did,)).fetchone())
        raise Problem('요청한 기능을 찾을 수 없어요.',404)

class Handler(BaseHTTPRequestHandler):
    def do_GET(self): self.respond()
    def do_POST(self): self.respond()
    def do_PATCH(self): self.respond()
    def do_DELETE(self): self.respond()
    def log_message(self, *_): pass  # Do not log codes, tokens, passwords or URL query strings.
    def respond(self):
        try:
            size=int(self.headers.get('Content-Length',0));require(size<=32768,'요청이 너무 커요.',413)
            b=json.loads(self.rfile.read(size)) if size else {}
            require(isinstance(b,dict),'요청 형식이 올바르지 않아요.')
            result=handle(self.command,self.path,b,self.headers.get('Authorization','').removeprefix('Bearer '))
            status=200
        except Problem as e: result={'message':e.message,'code':e.code};status=e.status
        except (ValueError,TypeError,KeyError): result={'message':'입력 형식을 확인해 주세요.','code':'VALIDATION_ERROR'};status=400
        except Exception:
            result={'message':'서버 처리에 실패했어요. 다시 확인해 주세요.','code':'INTERNAL_ERROR'};status=500
        payload=json.dumps(result,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)

if __name__=='__main__':
    init()
    print('Evening Deals API listening on port '+os.environ.get('PORT','8080'),flush=True)
    ThreadingHTTPServer((os.environ.get('HOST','127.0.0.1'),int(os.environ.get('PORT','8080'))),Handler).serve_forever()
