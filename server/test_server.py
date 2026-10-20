import unittest, tempfile, os, time, concurrent.futures
import server as api

class ContractTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();api.DB=os.path.join(self.temp.name,'test.db');api.init()
        # Deterministic relative windows: test results do not depend on Korean wall-clock hour.
        with api.transaction() as db: db.execute('UPDATE deals SET start=?,end=?',(time.time()+3600,time.time()+7200))
        self.customer=self.login('customer@demo.kr');self.other=self.login('other@demo.kr');self.staff=self.login('store@demo.kr')
    def tearDown(self): self.temp.cleanup()
    def login(self,email): return api.handle('POST','/auth/login',{'email':email,'password':'Demo1234!'})['token']
    def call(self,p,b=None,t=None,m='POST'): return api.handle(m,p,b or {},t or self.customer)
    def hold(self,key='h',qty=1,deal='d1',token=None): return self.call('/stock-holds',{'dealId':deal,'quantity':qty,'requestKey':key},token)
    def confirm(self,h,key='c',mode='success'): return self.call('/pickup-orders',{'holdId':h['id'],'policyAgreed':True,'paymentMode':mode,'requestKey':key})
    def in_window(self):
        with api.transaction() as db:
            db.execute('UPDATE deals SET start=?,end=?',(time.time()-60,time.time()+3600));db.execute('UPDATE orders SET start=?,end=?',(time.time()-60,time.time()+3600))
    def test_new_demo_day_preserves_old_orders(self):
        o=self.confirm(self.hold())
        with api.transaction() as db: db.execute('UPDATE deals SET end=?',(time.time()-1,))
        api.init()
        self.assertEqual(len(self.call('/deals',m='GET')['items']),3)
        self.assertEqual(self.call('/pickup-orders/'+o['id'],m='GET')['id'],o['id'])
        api.init()
        self.assertEqual(len(self.call('/deals',m='GET')['items']),3)

    def test_favorite_store_survives_ended_catalog(self):
        self.call('/favorites/stores',{'storeId':'s1'})
        with api.transaction() as db: db.execute('UPDATE deals SET end=?',(time.time()-1,))
        result=self.call('/favorites/stores',m='GET')
        self.assertIn('s1',result['items'])
        self.assertEqual(result['stores'][0]['store_id'],'s1')
        self.assertEqual(self.call('/deals',m='GET')['items'],[])

    def test_http_reservation_contract(self):
        import threading,json,urllib.request,urllib.error
        http=api.ThreadingHTTPServer(('127.0.0.1',0),api.Handler)
        thread=threading.Thread(target=http.serve_forever,daemon=True);thread.start()
        base=f'http://127.0.0.1:{http.server_port}'
        def request(path,body=None,token=''):
            r=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
            with urllib.request.urlopen(r,timeout=5) as response:return json.load(response)
        try:
            self.assertEqual(request('/health')['status'],'ok')
            self.assertEqual(len(request('/deals')['items']),3)
            token=request('/auth/login',{'email':'customer@demo.kr','password':'Demo1234!'})['token']
            h=request('/stock-holds',{'dealId':'d1','quantity':2,'requestKey':'http-hold'},token)
            b={'holdId':h['id'],'policyAgreed':True,'paymentMode':'success','requestKey':'http-confirm'}
            o=request('/pickup-orders',b,token)
            self.assertEqual(request('/pickup-orders',b,token)['id'],o['id'])
            self.assertEqual(o['total'],12000)
            self.assertEqual(len(o['code']),8)
            self.assertEqual(request('/deals/d1')['available'],3)
            request('/pickup-orders/'+o['id']+'/cancel',{'requestKey':'http-cancel'},token)
            self.assertEqual(request('/deals/d1')['available'],5)
            with self.assertRaises(urllib.error.HTTPError) as ctx: request('/pickup-orders')
            self.assertEqual(ctx.exception.code,401)
        finally:http.shutdown();http.server_close();thread.join()

    def test_last_stock_concurrency(self):
        with api.transaction() as db: db.execute("UPDATE deals SET stock=1 WHERE id='d1'")
        def reserve(args):
            try: self.hold(args[0],token=args[1]);return True
            except api.Problem as e: self.assertEqual(e.code,'STOCK_SHORTAGE');return False
        with concurrent.futures.ThreadPoolExecutor(2) as ex: results=list(ex.map(reserve,[('one',self.customer),('two',self.other)]))
        self.assertEqual(sum(results),1)
    def test_confirmation_replay_and_body_mismatch(self):
        h=self.hold(qty=2);o=self.confirm(h)
        self.assertEqual(self.confirm(h)['id'],o['id'])
        with self.assertRaises(api.Problem) as ctx: self.confirm(h,mode='failure')
        self.assertEqual(ctx.exception.code,'REQUEST_KEY_REUSED')
        self.assertEqual(len(self.call('/pickup-orders',m='GET')['items']),1)
        with api.transaction() as db: self.assertEqual(db.execute('SELECT COUNT(*) FROM notifications WHERE user_id=?',('customer@demo.kr',)).fetchone()[0],1)
    def test_expired_hold_and_payment_failure_release(self):
        h=self.hold()
        with api.transaction() as db: db.execute('UPDATE holds SET expires=?',(time.time()-1,))
        with self.assertRaises(api.Problem) as ctx: self.confirm(h)
        self.assertEqual(ctx.exception.code,'HOLD_EXPIRED')
        h=self.hold('second',qty=5)
        self.assertTrue(self.confirm(h,mode='failure')['paymentFailed'])
        self.assertEqual(self.call('/deals/d1',m='GET')['available'],5)
    def test_ownership_guest_and_merchant(self):
        o=self.confirm(self.hold())
        with self.assertRaises(api.Problem): api.handle('POST','/stock-holds',{'dealId':'d1','quantity':1,'requestKey':'guest'})
        with self.assertRaises(api.Problem): self.call('/pickup-orders/'+o['id'],t=self.other,m='GET')
        with self.assertRaises(api.Problem): self.call('/merchant/deals',m='GET')
        self.in_window()
        with api.transaction() as db: db.execute("UPDATE users SET store_id='s2' WHERE id='store@demo.kr'")
        self.assertTrue(self.call('/merchant/pickups/verify',{'numericCode':o['code']},self.staff)['verificationFailed'])
    def test_verify_readonly_complete_once_and_review(self):
        o=self.confirm(self.hold(qty=2));self.in_window()
        verified=self.call('/merchant/pickups/verify',{'numericCode':o['code']},self.staff)
        self.assertEqual(self.call('/pickup-orders/'+o['id'],m='GET')['status'],'waiting_pickup')
        b={'pickupOrderId':o['id'],'expectedVersion':verified['version'],'requestKey':'finish'}
        self.call('/merchant/pickups/complete',b,self.staff)
        self.assertTrue(self.call('/merchant/pickups/complete',b,self.staff)['ok'])
        with self.assertRaises(api.Problem): self.call('/merchant/pickups/verify',{'numericCode':o['code']},self.staff)
        finished=self.call('/pickup-orders/'+o['id'],m='GET');self.assertEqual(finished['status'],'picked_up');self.assertEqual(finished['token'],'')
        review={'pickupOrderId':o['id'],'rating':5,'content':'빵이 맛있고 구성이 정말 알찼어요.'}
        self.call('/reviews',review)
        with self.assertRaises(api.Problem): self.call('/reviews',review)
    def test_cancel_cutoff_stock_and_no_show(self):
        o=self.confirm(self.hold(qty=2));self.call('/pickup-orders/'+o['id']+'/cancel',{'requestKey':'cancel'})
        self.assertEqual(self.call('/deals/d1',m='GET')['available'],5)
        o=self.confirm(self.hold('h2'),key='c2');self.in_window()
        with self.assertRaises(api.Problem): self.call('/pickup-orders/'+o['id']+'/cancel',{'requestKey':'late'})
        with api.transaction() as db: db.execute('UPDATE orders SET end=? WHERE id=?',(time.time()-1,o['id']))
        self.assertEqual(self.call('/pickup-orders/'+o['id'],m='GET')['status'],'no_show')
    def test_window_boundaries_and_rate_limit(self):
        o=self.confirm(self.hold())
        with self.assertRaises(api.Problem): self.call('/merchant/pickups/verify',{'numericCode':o['code']},self.staff)
        for _ in range(5): self.call('/merchant/pickups/verify',{'numericCode':'bad'},self.staff)
        with self.assertRaises(api.Problem) as ctx:self.call('/merchant/pickups/verify',{'numericCode':'bad'},self.staff)
        self.assertEqual(ctx.exception.status,429)
    def test_signup_never_grants_merchant(self):
        r=self.call('/auth/signup',{'email':'new@example.com','nickname':'새고객','password':'GoodPass123','terms':True,'privacy':True,'role':'merchant'})
        self.assertEqual(r['user']['role'],'customer')
        with self.assertRaises(api.Problem): self.call('/merchant/deals',t=r['token'],m='GET')
    def test_persistence_and_edit_constraints(self):
        o=self.confirm(self.hold());api.init()
        self.assertEqual(self.call('/pickup-orders/'+o['id'],m='GET')['id'],o['id'])
        d=self.call('/deals/d1',m='GET');d['price']=1
        with self.assertRaises(api.Problem):self.call('/merchant/deals/d1',d,self.staff,m='PATCH')

if __name__=='__main__': unittest.main(verbosity=2)
