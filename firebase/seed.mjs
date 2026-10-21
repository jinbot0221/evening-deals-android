/** Local emulators only. Never seed shared production projects or put Admin keys in Android. */
import {initializeApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore,Timestamp} from 'firebase-admin/firestore';
const projectId=process.env.GCLOUD_PROJECT || 'demo-evening-deals';
if(!projectId.startsWith('demo-')) throw new Error('This seed is restricted to demo-* emulator projects.');
process.env.FIRESTORE_EMULATOR_HOST ||= '127.0.0.1:8085';
process.env.FIREBASE_AUTH_EMULATOR_HOST ||= '127.0.0.1:9099';
for(const name of ['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST']) {
 if(!/^(127\.0\.0\.1|localhost):\d+$/.test(process.env[name]))throw new Error('Seed requires local emulator hosts.');
}
initializeApp({projectId});const auth=getAuth(),db=getFirestore();
for(const [email,nickname,store] of [['customer@demo.kr','득템러',null],['store@demo.kr','온기 베이커리','s1'],['other@demo.kr','다른 고객',null]]) {
 let u;try {u=await auth.getUserByEmail(email);}catch(e){if(e.code!=='auth/user-not-found')throw e;u=await auth.createUser({email,password:'Demo1234!',displayName:nickname,emailVerified:true});}
 const profile=db.doc('users/'+u.uid);if(!(await profile.get()).exists)await profile.set({nickname,email,terms:true,privacy:true});
 if(store)await db.doc('merchants/'+u.uid).set({store_id:store});
}
const current=Date.now();const korean=new Date(current+9*3600000);let start=Date.UTC(korean.getUTCFullYear(),korean.getUTCMonth(),korean.getUTCDate(),10);
if(start+3600000<=current)start+=86400000;
const seeds=[['d1','s1','온기 베이커리','오늘의 빵 세트','베이커리',12000,6000,5,'크루아상 2개, 소금빵 2개, 오늘 구운 식빵 반 개입니다.','밀, 우유, 계란'],['d2','s2','한끼의 온도','정성 가득 한끼 도시락','도시락',14000,6900,8,'갓 지은 밥과 제철 채소, 닭고기 반찬으로 구성합니다.','대두, 밀, 계란'],['d3','s3','그린 테이블','하루 채소 샐러드','샐러드',11000,4900,3,'신선한 잎채소와 토마토, 견과류, 드레싱 구성입니다.','견과류, 대두']];
for(const [i,s] of seeds.entries()) {
 const [base,store_id,store,title,category,original,price,stock,description,allergens]=s;
 let ref=db.doc('deals/'+base);const previous=await ref.get();
 if(previous.exists) {
  if(previous.data().end.toMillis()>current)continue;
  ref=db.doc('deals/'+base+'-'+new Date(start).toISOString().slice(0,10));
  if((await ref.get()).exists)continue;
 }
 await ref.create({store_id,store,title,category,region:'성수동',address:`서울 성동구 성수이로 ${20+i*12}`,original,price,stock,start:Timestamp.fromMillis(start+i*900000),end:Timestamp.fromMillis(start+3600000+i*900000),description,allergens,stopped:false,locked:false,lockHoldId:''});
}
console.log('Local Auth/Firestore demo seed ready; existing accounts, reservations and inventory preserved.');
