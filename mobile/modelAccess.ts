import * as SecureStore from 'expo-secure-store';
import {digestStringAsync,CryptoDigestAlgorithm} from 'expo-crypto';
import {cloud,type CloudAccount} from './cloud';
export type ChatMessage={role:'system'|'user'|'assistant';content:string};
export type PersonalModel={endpoint:string;model:string;key:string};
const keyFor=async(id:string)=>'assistant-model-'+await digestStringAsync(CryptoDigestAlgorithm.SHA256,id);
export function validateModel(p:PersonalModel){
 const url=new URL(p.endpoint.trim());
 if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname.replace(/\/$/,'').endsWith('/chat/completions')===false)throw Error('HTTPS 전체 chat/completions 주소를 입력하세요.');
 if(!p.model.trim()||p.model.length>200||p.key.length>4096)throw Error('모델 ID와 API 키를 확인하세요.');
 return {endpoint:url.href,model:p.model.trim(),key:p.key.trim()};
}
export async function loadPersonal(id:string){const raw=await SecureStore.getItemAsync(await keyFor(id));return raw?validateModel(JSON.parse(raw)):null}
export async function savePersonal(id:string,p:PersonalModel|null){const k=await keyFor(id);if(p)await SecureStore.setItemAsync(k,JSON.stringify(validateModel(p)));else await SecureStore.deleteItemAsync(k)}
export async function complete(a:CloudAccount,model:string,messages:ChatMessage[],personal?:PersonalModel|null){
 if(!personal)return cloud('/v1/chat/completions',{model,messages,stream:false,max_tokens:2048},a.token,90000);
 const p=validateModel(personal),controller=new AbortController(),timer=setTimeout(()=>controller.abort(),90000);
 try{
  // No DOMINION bearer or account identifier is sent to the external provider.
  const r=await fetch(p.endpoint,{method:'POST',redirect:'error',signal:controller.signal,headers:{'content-type':'application/json',...(p.key?{authorization:'Bearer '+p.key}:{})},body:JSON.stringify({model:p.model,messages,stream:false,max_tokens:2048})});
  if(!r.ok)throw Error('개인 API 응답 오류 ('+r.status+')');return await r.json()
 }finally{clearTimeout(timer)}
}
