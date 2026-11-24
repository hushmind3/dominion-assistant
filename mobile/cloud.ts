import {Linking} from 'react-native';
import {randomUUID,getRandomBytesAsync,digestStringAsync,CryptoDigestAlgorithm,CryptoEncoding} from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
export const CLOUD='https://dominion-cloud.dominion-hushmind.workers.dev';
const SESSION='dominion-cloud-account-v1';
export type CloudAccount={token:string;accountId:string};
export type Device={id:string;name:string;online:boolean;last_seen:number};
export type Flight={id:string;appToken:string;url:string;expires_in:number};
export class CloudApiError extends Error{constructor(public status:number,message:string){super(message)}}
export async function cloud(path:string,body?:object,token?:string,timeout=15000){
 const ctrl=new AbortController();const t=setTimeout(()=>ctrl.abort(),timeout);
 try{
  const r=await fetch(CLOUD+path,{method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:ctrl.signal,redirect:'error'});
  const x=await r.json();if(!r.ok)throw new CloudApiError(r.status,x.error||'Cloud connection failed');return x;
 }finally{clearTimeout(t)}
}
const base64url=(s:string)=>s.replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
function encodeBytes(bytes:Uint8Array){const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';let out='',n=0,bits=0;for(const b of bytes){n=(n<<8)|b;bits+=8;while(bits>=6){bits-=6;out+=alphabet[(n>>>bits)&63]}}if(bits)out+=alphabet[(n<<(6-bits))&63];return out}
export async function loginStart():Promise<Flight>{
 const appToken='dapp_'+encodeBytes(await getRandomBytesAsync(32));
 const token_hash=base64url(await digestStringAsync(CryptoDigestAlgorithm.SHA256,appToken,{encoding:CryptoEncoding.BASE64}));
 const r=await cloud('/v1/apps/start',{app_name:'DOMINION Assistant',token_hash,capability:'assistant_remote'});
 return {id:r.id,appToken,url:r.verification_uri,expires_in:r.expires_in};
}
export async function openLogin(f:Flight){if(!f.url.startsWith(CLOUD+'/connect?code='))throw Error('Invalid login URL');await Linking.openURL(f.url)}
export async function loginStatus(f:Flight):Promise<CloudAccount|null>{
 const x=await cloud('/v1/apps/status',undefined,f.appToken);
 if(x.status==='expired'||x.status==='revoked')throw Error('Google 인증이 만료되었거나 해제되었습니다.');
 if(x.status!=='approved')return null;
 const a=await cloud('/v1/me',undefined,f.appToken);
 if(typeof a.account_id!=='string'||!a.account_id.startsWith('google:'))throw Error('Invalid account');
 return {token:f.appToken,accountId:a.account_id};
}
export async function loadAccount():Promise<CloudAccount|null>{
 const raw=await SecureStore.getItemAsync(SESSION);if(!raw)return null;
 const a=JSON.parse(raw) as CloudAccount;
 if(typeof a.token!=='string'||!/^dapp_[A-Za-z0-9_-]{43}$/.test(a.token)||!a.accountId?.startsWith('google:'))return null;
 return a;
}
export async function saveAccount(a:CloudAccount){await SecureStore.setItemAsync(SESSION,JSON.stringify(a))}
export async function clearAccount(){closeRelay();await SecureStore.deleteItemAsync(SESSION)}
export async function logout(a:CloudAccount){try{await cloud('/v1/apps/disconnect',{},a.token)}finally{await clearAccount()}}
export async function revokeDevice(a:CloudAccount,d:Device){await cloud('/v1/assistant/revoke',{deviceId:d.id},a.token)}
export async function devices(a:CloudAccount):Promise<Device[]>{const x=await cloud('/v1/assistant/devices',undefined,a.token);return x.devices}
type RelayEvent={type:string;deviceId?:string;devices?:Device[];status?:number};
type Pending={resolve:(value:any)=>void;reject:(error:Error)=>void;timer:ReturnType<typeof setTimeout>};
type SocketCtor=new(url:string,protocols?:string|string[],options?:{headers:Record<string,string>})=>WebSocket;
class RelaySocket{
 ws:WebSocket|null=null;listeners=new Set<(event:RelayEvent)=>void>();pending=new Map<string,Pending>();
 ready=false;closed=false;delay=1000;retry:ReturnType<typeof setTimeout>|null=null;idle:ReturnType<typeof setTimeout>|null=null;
 snapshot:Device[]|null=null;
 constructor(readonly token:string){}
 emit(event:RelayEvent){for(const fn of this.listeners){try{fn(event)}catch{}}}
 subscribe(fn:(event:RelayEvent)=>void){this.listeners.add(fn);if(this.idle)clearTimeout(this.idle);this.idle=null;
  if(this.ready){fn({type:'ready'});if(this.snapshot)fn({type:'devices',devices:this.snapshot})}else this.connect();
  return()=>{this.listeners.delete(fn);this.maybeClose()};
 }
 maybeClose(){if(this.listeners.size||this.pending.size||this.idle)return;this.idle=setTimeout(()=>{if(!this.listeners.size&&!this.pending.size)this.close()},1000)}
 connect(){
  if(this.ws||this.closed||this.retry)return;
  const ws=new (WebSocket as unknown as SocketCtor)(CLOUD.replace('https:','wss:')+'/v1/assistant/socket',[],{headers:{Authorization:'Bearer '+this.token}});
  this.ws=ws;
  ws.onmessage=e=>{
   if(this.ws!==ws||typeof e.data!=='string')return;
   let x:any;try{x=JSON.parse(e.data)}catch{return}
   if(x.type==='ready'){this.ready=true;this.delay=1000;this.emit({type:'ready'})}
   else if(x.type==='devices'&&Array.isArray(x.devices)){this.snapshot=x.devices;this.emit(x)}
   else if(x.type==='changed')this.emit(x)
   else if(x.type==='result'){
    const p=this.pending.get(x.id);if(!p)return;this.pending.delete(x.id);clearTimeout(p.timer);
    if(x.status>=200&&x.status<300)p.resolve(x.data);else p.reject(new CloudApiError(x.status,x.data?.error||'PC request failed'));this.maybeClose()
   }
  };
  ws.onerror=()=>{try{ws.close()}catch{}};
  ws.onclose=e=>{
   if(this.ws!==ws)return;
   this.ws=null;this.ready=false;this.snapshot=null;
   for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new CloudApiError(503,'연결 중단 · 실행 여부는 작업 기록에서 확인하세요.'))}
   this.pending.clear();this.emit({type:'offline'});
   if(e.code===1008){this.emit({type:'unauthorized',status:401});this.close();return}
   if(!this.closed&&this.listeners.size){
    this.retry=setTimeout(()=>{this.retry=null;this.connect()},this.delay+Math.random()*500);this.delay=Math.min(120000,this.delay*2)
   }else this.maybeClose()
  };
 }
 async waitReady(){
  if(this.ready)return;
  await new Promise<void>((resolve,reject)=>{
   let off=()=>{};const timer=setTimeout(()=>{off();reject(new CloudApiError(503,'Cloud 연결을 확인하세요.'))},12000);
   off=this.subscribe(e=>{if(e.type==='ready'){clearTimeout(timer);off();resolve()}else if(e.type==='unauthorized'){clearTimeout(timer);off();reject(new CloudApiError(401,'Google 계정으로 다시 로그인하세요.'))}});
  });
 }
 async rpc(deviceId:string,route:string,body?:object){
  const id=randomUUID(),raw=JSON.stringify({type:'rpc',id,deviceId,route,body:body??null});
  if(route==='attachment'&&raw.length>800000)throw Error('현재 클라우드 첨부는 약 600KB까지 지원합니다.');
  await this.waitReady();
  if(!this.ws||!this.ready)throw new CloudApiError(503,'Cloud 연결 중단');
  if(this.pending.size>=8)throw new CloudApiError(429,'이전 작업이 너무 많습니다.');
  return new Promise<any>((resolve,reject)=>{
   const timer=setTimeout(()=>{this.pending.delete(id);this.maybeClose();reject(Error('PC 응답 확인 시간이 초과되었습니다. 작업 기록을 먼저 확인하세요.'))},route==='attachment'?115000:45000);
   this.pending.set(id,{resolve,reject,timer});
   try{this.ws!.send(raw)}catch(e){clearTimeout(timer);this.pending.delete(id);reject(e);this.maybeClose()}
  });
 }
 close(){
  this.closed=true;if(this.retry)clearTimeout(this.retry);if(this.idle)clearTimeout(this.idle);
  this.retry=null;this.idle=null;const ws=this.ws;this.ws=null;this.ready=false;this.snapshot=null;
  for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new CloudApiError(503,'연결 종료'))}this.pending.clear();
  try{ws?.close()}catch{}
 }
}
let active:RelaySocket|null=null;
function socket(token:string){if(active?.token!==token||active.closed){active?.close();active=new RelaySocket(token)}return active}
export function subscribeRelay(token:string,fn:(event:RelayEvent)=>void){return socket(token).subscribe(fn)}
export function closeRelay(){active?.close();active=null}
export async function relay(token:string,deviceId:string,route:string,body?:object){return socket(token).rpc(deviceId,route,body)}
