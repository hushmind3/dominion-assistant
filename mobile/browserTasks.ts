import {cloud,type CloudAccount} from './cloud';
export type BrowserTask={id:string;url:string;goal:string;remember?:boolean};
export type BrowserDocument={id:string;name:string;kind:'original'|'page';mime:'application/pdf';size:number;sha256:string;source:string;expiresAt:number};
export type BrowserProfile={site:string;expiresAt:number};
export type BrowserStatus='queued'|'running'|'awaiting_human'|'awaiting_approval'|'awaiting_file'|'approved'|'action_inflight'|'completed'|'failed'|'cancelled'|'needs_review';
export type BrowserJob={id:string;site:string;goal:string;status:BrowserStatus;message:string;steps:number;updatedAt:number;approval?:{id:string;label:string;url:string;preview?:string;accept?:string};result?:{summary:string;evidence:string;url:string};pageUrl?:string;documents?:BrowserDocument[];remember?:boolean};
const statuses=['queued','running','awaiting_human','awaiting_approval','awaiting_file','approved','action_inflight','completed','failed','cancelled','needs_review'];
export function browserUrl(raw:unknown){
 if(typeof raw!=='string'||raw.length>2048)return null;
 try{const u=new URL(raw);if(u.protocol!=='https:'||u.username||u.password||u.port||!u.hostname.includes('.')||/^\d[\d.]*$/.test(u.hostname)||u.hostname.includes(':')||/\.(?:local|internal|invalid|test|lan|onion|arpa)$/.test(u.hostname))return null;return u.href}catch{return null}
}
export function taskProposal(x:unknown,id:string):BrowserTask|null{
 if(!x||typeof x!=='object')return null;
 const p=x as Record<string,unknown>,url=browserUrl(p.url);
 if(p.action!=='browser_task'||!url||typeof p.goal!=='string'||!p.goal.trim()||p.goal.length>4000)return null;
 return {id,url,goal:p.goal.trim()};
}
export function parseBrowserJob(x:unknown):BrowserJob{
 const j=x as BrowserJob;
 if(!j||typeof j.id!=='string'||typeof j.goal!=='string'||typeof j.site!=='string'||typeof j.message!=='string'||!statuses.includes(j.status)||!Number.isFinite(j.updatedAt))throw Error('웹 업무 상태를 확인할 수 없습니다.');
 return j;
}
export function browserPatch(j:BrowserJob){
 const finished=j.status==='completed'&&!!j.result;
 const failed=['failed','cancelled','needs_review'].includes(j.status)||j.status==='completed'&&!j.result;
 const text=finished?j.result!.summary+'\n\n확인된 화면 문구: '+j.result!.evidence:j.message;
 return {text,status:(finished?undefined:failed?'failed':'pending') as 'pending'|'failed'|undefined,jobStatus:j.status,browserUpdatedAt:j.updatedAt,browserApproval:j.approval,browserResultUrl:finished?browserUrl(j.result!.url)||undefined:undefined,browserDocuments:Array.isArray(j.documents)?j.documents.filter(isBrowserDocument):[]};
}
const base='/v1/assistant/browser/';
export async function listBrowserJobs(a:CloudAccount):Promise<BrowserJob[]>{
 const r=await cloud(base+'jobs',undefined,a.token);if(!Array.isArray(r.jobs))throw Error('웹 업무 목록 오류');
 return (r.jobs as unknown[]).map(parseBrowserJob);
}
export async function browserCommand(a:CloudAccount,task:BrowserTask,action:'start'|'view'|'resume'|'approve'|'cancel',approvalId?:string){
 const r=await cloud(base+action,action==='start'?{...task,confirmed:true}:{id:task.id,...(approvalId?{approvalId}:{})},a.token,60000);
 if(action==='view'){
  const u=browserUrl(r.url);if(!u||new URL(u).hostname!=='live.browser.run')throw Error('인증 화면 주소가 올바르지 않습니다.');
  return {url:u};
 }
 const j=parseBrowserJob(r);if(j.id!==task.id)throw Error('웹 업무 ID 불일치');return {job:j};
}

export function isBrowserDocument(x:unknown):x is BrowserDocument{
 const d=x as BrowserDocument;
 return !!d&&/^[a-zA-Z0-9_-]{8,80}$/.test(d.id)&&typeof d.name==='string'&&['original','page'].includes(d.kind)&&d.mime==='application/pdf'&&Number.isInteger(d.size)&&d.size>0&&d.size<=2*1024*1024&&/^[a-f0-9]{64}$/.test(d.sha256)&&Number.isFinite(d.expiresAt);
}
export async function browserProfiles(a:CloudAccount):Promise<BrowserProfile[]>{
 const r=await cloud(base+'profiles',undefined,a.token);
 if(!Array.isArray(r.profiles))throw Error('저장된 로그인 목록을 확인하지 못했습니다.');
 return (r.profiles as BrowserProfile[]).filter(x=>typeof x?.site==='string'&&!!browserUrl('https://'+x.site)&&Number.isFinite(x.expiresAt)&&x.expiresAt>Date.now());
}
export async function forgetBrowserProfile(a:CloudAccount,site:string){await cloud(base+'forget',{site},a.token)}
export async function fetchBrowserDocument(a:CloudAccount,task:BrowserTask,d:BrowserDocument){
 const r=await cloud(base+'document',{id:task.id,documentId:d.id},a.token,60000);
 if(!isBrowserDocument(r)||r.id!==d.id||r.sha256!==d.sha256||r.size!==d.size||typeof (r as any).base64!=='string')throw Error('PDF 정보가 일치하지 않습니다.');
 const data=(r as BrowserDocument&{base64:string}).base64;
 if(data.length>Math.ceil(2*1024*1024/3)*4||!/^[A-Za-z0-9+/]+={0,2}$/.test(data))throw Error('지원하지 않는 PDF 데이터');
 return data;
}

export type BrowserUpload={name:string;mime:string;base64:string};
export async function uploadBrowserFile(a:CloudAccount,task:BrowserTask,approvalId:string,file:BrowserUpload){
 const r=await cloud(base+'upload',{id:task.id,approvalId,confirmed:true,file},a.token,60000);
 const job=parseBrowserJob(r);if(job.id!==task.id)throw Error('웹 업무 ID 불일치');return job;
}
