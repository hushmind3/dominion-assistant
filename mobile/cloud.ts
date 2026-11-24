import {Linking} from 'react-native';
import * as SecureStore from 'expo-secure-store';
export const CLOUD='https://dominion-cloud.dominion-hushmind.workers.dev';
const SESSION='dominion-cloud-account-v1';
export type CloudAccount={token:string;accountId:string};
export type Device={id:string;name:string;online:boolean;last_seen:number};
export type Flight={id:string;poll_token:string;url:string;expires_in:number};
export class CloudApiError extends Error{constructor(public status:number,message:string){super(message)}}
export async function cloud(path:string,body?:object,token?:string,timeout=15000){
 const ctrl=new AbortController();const t=setTimeout(()=>ctrl.abort(),timeout);
 try{
  const r=await fetch(CLOUD+path,{method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:ctrl.signal,redirect:'error'});
  const x=await r.json();if(!r.ok)throw new CloudApiError(r.status,x.error||'Cloud connection failed');return x;
 }finally{clearTimeout(t)}
}
export async function loginStart():Promise<Flight>{return cloud('/v1/assistant/auth/start',{})}
export async function openLogin(f:Flight){if(!f.url.startsWith(CLOUD+'/v1/assistant/auth/open?'))throw Error('Invalid login URL');await Linking.openURL(f.url)}
export async function loginStatus(f:Flight):Promise<CloudAccount|null>{
 const x=await cloud('/v1/assistant/auth/status',{id:f.id,poll_token:f.poll_token});
 if(x.status!=='done')return null;
 if(typeof x.token!=='string'||typeof x.account_id!=='string'||!x.account_id.startsWith('google:'))throw Error('Invalid account');
 const a={token:x.token,accountId:x.account_id};await SecureStore.setItemAsync(SESSION,JSON.stringify(a));return a;
}
export async function loadAccount():Promise<CloudAccount|null>{
 const raw=await SecureStore.getItemAsync(SESSION);if(!raw)return null;
 const a=JSON.parse(raw) as CloudAccount;
 if(typeof a.token!=='string'||!a.accountId?.startsWith('google:'))return null;
 return a;
}
export async function clearAccount(){await SecureStore.deleteItemAsync(SESSION)}
export async function devices(a:CloudAccount):Promise<Device[]>{const x=await cloud('/v1/assistant/devices',undefined,a.token);return x.devices}
const sleep=(ms:number)=>new Promise<void>(resolve=>setTimeout(resolve,ms));
export async function relay(token:string,deviceId:string,route:string,body?:object){
 // A lost POST response is retried only with the same relay ID.
 const id=crypto.randomUUID(),payload={id,deviceId,route,body:body??null};
 if(route==='attachment'&&JSON.stringify(payload).length>800000)throw Error('현재 클라우드 첨부는 약 600KB까지 지원합니다.');
 let result;
 try{result=await cloud('/v1/assistant/rpc',payload,token)}catch(e){if(e instanceof CloudApiError)throw e;result=await cloud('/v1/assistant/rpc',payload,token)}
 if(result.id!==id)throw Error('Invalid relay response');
 const until=Date.now()+(route==='attachment'?115000:40000);
 while(Date.now()<until){
  const x=await cloud('/v1/assistant/rpc/status',{id},token);
  if(x.state==='done'){if(x.status<200||x.status>=300)throw new CloudApiError(x.status,x.data?.error||'PC request failed');return x.data}
  await sleep(1700);
 }
 throw Error('PC 응답 확인 시간이 초과되었습니다. 전화나 작업은 기록을 먼저 확인하세요.');
}
