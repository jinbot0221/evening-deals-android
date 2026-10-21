import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,collection,getDoc,getDocs,setDoc,updateDoc,query,where,limit,runTransaction,writeBatch,serverTimestamp,Timestamp} from 'firebase/firestore';
let env, counter=0;
const ts=ms=>Timestamp.fromMillis(ms);
const uid='customer', other='other', staff='staff', wrongStaff='wrong-staff';
const ctx=id=>env.authenticatedContext(id,{email:id+'@example.com',firebase:{sign_in_provider:'password'}}).firestore();
const ref=(db,path)=>doc(db,path);
const dealData=()=>({store_id:'s1',store:'온기 베이커리',title:'오늘의 빵 세트',category:'베이커리',region:'성수동',address:'서울 성동구 성수이로 20',original:12000,price:6000,stock:5,start:ts(Date.now()+3600000),end:ts(Date.now()+7200000),description:'크루아상과 소금빵을 포함하는 구성입니다.',allergens:'밀, 우유, 계란',stopped:false,locked:false,lockHoldId:''});
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-evening-rules',firestore:{host:'127.0.0.1',port:8085,rules:await readFile('firebase/firestore.rules','utf8')}});});
after(async()=>{await env?.cleanup();});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async c=>{
 const db=c.firestore();await setDoc(ref(db,'deals/d1'),dealData());
 await setDoc(ref(db,'merchants/staff'),{store_id:'s1'});await setDoc(ref(db,'merchants/wrong-staff'),{store_id:'s2'});
});});
async function admin(fn){return env.withSecurityRulesDisabled(c=>fn(c.firestore()));}
async function hold(db,{key='h-'+(++counter),slots=['0'],quantity=slots.length,user=uid,expires=ts(Date.now()+240000),omitSlots=false}={}) {
 const h={user_id:user,deal_id:'d1',quantity,slots,expires,status:'active',requestKey:key,createdAt:serverTimestamp()};
 await runTransaction(db,async tx=>{
  const d=await tx.get(ref(db,'deals/d1'));const old=await tx.get(ref(db,'holds/'+key));
  const inventory=await Promise.all(slots.map(s=>tx.get(ref(db,'deals/d1/slots/'+s))));
  if(old.exists()){assert.equal(old.data().quantity,quantity);return;}
  if(inventory.some(s=>s.exists() && (s.data().status==='reserved'||s.data().status==='held'&&s.data().expires.toMillis()>Date.now())))throw new Error('STOCK_SHORTAGE');
  if(!d.data().locked)tx.update(d.ref,{locked:true,lockHoldId:key});
  tx.set(ref(db,'holds/'+key),h);
  if(!omitSlots)slots.forEach(s=>tx.set(ref(db,'deals/d1/slots/'+s),{index:Number(s),holdId:key,status:'held',expires}));
 });return key;
}
async function confirm(db,id,{key='confirm-'+id,overrides={},omitSlots=false}={}){
 const code=String(++counter).padStart(8,'0');
 await runTransaction(db,async tx=>{
  const old=await tx.get(ref(db,'orders/'+id));if(old.exists()){assert.equal(old.data().confirmKey,key);return;}
  const h=(await tx.get(ref(db,'holds/'+id))).data();const d=(await tx.get(ref(db,'deals/d1'))).data();
  const slots=await Promise.all(h.slots.map(s=>tx.get(ref(db,'deals/d1/slots/'+s))));
  const o={user_id:h.user_id,deal_id:'d1',store_id:'s1',holdId:id,qty:h.quantity,total:d.price*h.quantity,title:d.title,store:d.store,start:d.start,end:d.end,status:'waiting_pickup',code,token:'a'.repeat(64),version:1,createdAt:serverTimestamp(),confirmKey:key,paymentMode:'success',...overrides};
  tx.set(ref(db,'orders/'+id),o);tx.update(ref(db,'holds/'+id),{status:'confirmed'});
  if(!omitSlots)slots.forEach(s=>tx.update(s.ref,{status:'reserved'}));
  tx.set(ref(db,'pickupCodes/'+code),{orderId:id,user_id:h.user_id,store_id:'s1'});
  tx.set(ref(db,`operations/${h.user_id}_confirm_${key}`),{user_id:h.user_id,orderId:id,requestKey:key,createdAt:serverTimestamp()});
 });return (await getDoc(ref(db,'orders/'+id))).data();
}
async function cancel(db,id,{omitSlot=false}={}){
 const o=(await getDoc(ref(db,'orders/'+id))).data();const h=(await getDoc(ref(db,'holds/'+id))).data();
 const batch=writeBatch(db);batch.update(ref(db,'orders/'+id),{status:'cancelled',version:o.version+1});
 if(!omitSlot)h.slots.forEach(s=>batch.set(ref(db,'deals/d1/slots/'+s),{index:Number(s),holdId:'',status:'free',expires:ts(0)}));
 return batch.commit();
}
test('guest catalog is bounded; guest/private data and self-appointed merchant are denied',async()=>{
 const guest=env.unauthenticatedContext().firestore();await assertSucceeds(getDocs(query(collection(guest,'deals'),limit(20))));
 await assertFails(getDocs(collection(guest,'deals')));await assertFails(getDoc(ref(guest,'orders/private')));
 await assertFails(setDoc(ref(ctx(uid),'merchants/'+uid),{store_id:'s1'}));
 await assertFails(setDoc(ref(ctx(uid),'users/'+uid),{nickname:'고객',email:uid+'@example.com',terms:true,privacy:true,role:'merchant'}));
});
test('atomic 5-unit hold is valid within Firestore access-call limits',async()=>{
 const db=ctx(uid);const id=await assertSucceeds(hold(db,{slots:['0','1','2','3','4']}));
 const o=await assertSucceeds(confirm(db,id));assert.equal(o.qty,5);assert.equal(o.total,30000);
});
test('last slot concurrent requests yield exactly one reservation',async()=>{
 await admin(db=>updateDoc(ref(db,'deals/d1'),{stock:1}));
 const results=await Promise.allSettled([hold(ctx(uid),{key:'last-one'}),hold(ctx(other),{key:'last-two',user:other})]);
 assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 const slots=await getDocs(query(collection(env.unauthenticatedContext().firestore(),'deals/d1/slots'),limit(99)));assert.equal(slots.size,1);
});
test('hold cannot omit slot writes, forge another owner, exceed quantity or duplicate slots',async()=>{
 const db=ctx(uid);
 await assertFails(hold(db,{omitSlots:true}));await assertFails(hold(db,{user:other}));
 await assertFails(hold(db,{slots:['0','0'],quantity:2}));
 await assertFails(hold(db,{slots:['0','1','2','3','4','5'],quantity:6}));
});
test('cannot lock a product without a valid atomic reservation',async()=>{
 await assertFails(updateDoc(ref(ctx(uid),'deals/d1'),{locked:true,lockHoldId:'nonexistent'}));
});
test('expired slots can be reclaimed without cron, and old hold cannot confirm',async()=>{
 const db=ctx(uid);const id=await hold(db);
 await admin(async a=>{await updateDoc(ref(a,'holds/'+id),{expires:ts(Date.now()-1000)});await updateDoc(ref(a,'deals/d1/slots/0'),{expires:ts(Date.now()-1000)});});
 await assertFails(confirm(db,id));const replacement=await assertSucceeds(hold(ctx(other),{user:other}));
 assert.equal((await getDoc(ref(env.unauthenticatedContext().firestore(),'deals/d1/slots/0'))).data().holdId,replacement);
});
test('confirmation rejects forged price and partial inventory transitions',async()=>{
 const db=ctx(uid);let id=await hold(db);await assertFails(confirm(db,id,{overrides:{total:1}}));
 await assertFails(confirm(db,id,{omitSlots:true}));
 await assertSucceeds(confirm(db,id));
 await assertFails(updateDoc(ref(db,'orders/'+id),{qty:5,total:1}));
});
test('same confirmation key is idempotent and immutable operations prevent reuse',async()=>{
 const db=ctx(uid);const id=await hold(db);const first=await confirm(db,id);const again=await confirm(db,id);
 assert.equal(first.code,again.code);
 const second=await hold(db,{slots:['1']});await assertFails(confirm(db,second,{key:'confirm-'+id}));
});
test('cancel requires every reserved slot returned and obeys server cancellation cutoff',async()=>{
 const db=ctx(uid);const id=await hold(db,{slots:['0','1']});await confirm(db,id);
 await assertFails(cancel(db,id,{omitSlot:true}));await assertSucceeds(cancel(db,id));
 await assertSucceeds(hold(ctx(other),{slots:['0','1'],user:other}));
});
test('pickup is merchant-only, time-bound, versioned and cannot be repeated',async()=>{
 await admin(db=>updateDoc(ref(db,'deals/d1'),{start:ts(Date.now()-60000)}));
 const db=ctx(uid);const id=await hold(db);const o=await confirm(db,id);
 await assertFails(getDoc(ref(ctx(other),'orders/'+id)));await assertFails(getDoc(ref(ctx(wrongStaff),'pickupCodes/'+o.code)));
 await assertSucceeds(getDoc(ref(ctx(staff),'pickupCodes/'+o.code)));
 await assertFails(updateDoc(ref(db,'orders/'+id),{status:'picked_up',version:2}));
 await assertFails(updateDoc(ref(ctx(wrongStaff),'orders/'+id),{status:'picked_up',version:2}));
 await assertFails(updateDoc(ref(ctx(staff),'orders/'+id),{status:'picked_up',version:7}));
 await assertSucceeds(updateDoc(ref(ctx(staff),'orders/'+id),{status:'picked_up',version:2}));
 await assertFails(updateDoc(ref(ctx(staff),'orders/'+id),{status:'picked_up',version:3}));
});
test('out-of-window pickup/cancel cannot be bypassed by changing the device timestamp',async()=>{
 const db=ctx(uid);const id=await hold(db);await confirm(db,id);
 await assertFails(updateDoc(ref(ctx(staff),'orders/'+id),{status:'picked_up',version:2}));
 await admin(a=>updateDoc(ref(a,'orders/'+id),{start:ts(Date.now()-1000),end:ts(Date.now()+60000)}));
 await assertFails(cancel(db,id));
 await assertFails(setDoc(ref(db,'clocks/'+uid),{time:ts(Date.now()+86400000)}));
 await assertSucceeds(setDoc(ref(db,'clocks/'+uid),{time:serverTimestamp()}));
});
test('failed payment can release a hold, but cannot free a different user slot',async()=>{
 const db=ctx(uid);const id=await hold(db);const batch=writeBatch(db);
 batch.update(ref(db,'holds/'+id),{status:'released'});batch.set(ref(db,'deals/d1/slots/0'),{index:0,holdId:'',status:'free',expires:ts(0)});await assertSucceeds(batch.commit());
 const id2=await hold(ctx(other),{user:other});
 await assertFails(setDoc(ref(db,'deals/d1/slots/0'),{index:0,holdId:'',status:'free',expires:ts(0)}));
 assert.equal((await getDoc(ref(env.unauthenticatedContext().firestore(),'deals/d1/slots/0'))).data().holdId,id2);
});
test('locked product content/price/stock cannot be changed, stop-sale remains possible',async()=>{
 await hold(ctx(uid));await assertFails(updateDoc(ref(ctx(staff),'deals/d1'),{price:1}));
 await assertFails(updateDoc(ref(ctx(staff),'deals/d1'),{stock:6}));await assertSucceeds(updateDoc(ref(ctx(staff),'deals/d1'),{stopped:true}));
 await assertFails(hold(ctx(other),{user:other,slots:['1']}));
});
test('review is one per own completed order; private profile and preferences stay scoped',async()=>{
 await admin(db=>updateDoc(ref(db,'deals/d1'),{start:ts(Date.now()-60000)}));
 const db=ctx(uid);const id=await hold(db);await confirm(db,id);
 const review={user_id:uid,rating:5,content:'구성이 알차고 음식도 정말 맛있었어요.',createdAt:serverTimestamp()};
 await assertFails(setDoc(ref(db,'reviews/'+id),review));await updateDoc(ref(ctx(staff),'orders/'+id),{status:'picked_up',version:2});
 await assertSucceeds(setDoc(ref(db,'reviews/'+id),review));await assertFails(setDoc(ref(db,'reviews/'+id),review));
 await assertFails(getDoc(ref(ctx(other),'reviews/'+id)));
 await assertSucceeds(setDoc(ref(db,'users/'+uid),{nickname:'득템러',email:uid+'@example.com',terms:true,privacy:true}));
 await assertFails(getDoc(ref(ctx(other),'users/'+uid)));
});
