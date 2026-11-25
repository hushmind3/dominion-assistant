import {cloud,CloudApiError,type CloudAccount} from './cloud';

// The Cloud work gateway is additive. Older deployments retain the exact
// previous Google/Browser endpoints until the companion Cloud PR is deployed.
let legacyOnly=false;
export function unknownWorkRoute(error:unknown){
 return error instanceof CloudApiError&&(error.status===404||error.status===403&&error.message==='Permission denied or unknown route');
}
export async function workRequest(a:CloudAccount,kind:'google'|'browser',action:'start'|'cancel'|'view'|'resume'|'approve',params:Record<string,unknown>,legacyPath:string,legacyBody:Record<string,unknown>,timeout=60000){
 if(legacyOnly)return cloud(legacyPath,legacyBody,a.token,timeout);
 try{
  return await cloud('/v1/assistant/work/'+(action==='start'?'start':'action'),{kind,...params,...(action==='start'?{}:{action})},a.token,timeout);
 }catch(error){
  if(!unknownWorkRoute(error))throw error;
  legacyOnly=true;
  return cloud(legacyPath,legacyBody,a.token,timeout);
 }
}
