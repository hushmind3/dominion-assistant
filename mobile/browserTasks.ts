import {cloud,type CloudAccount} from './cloud';
export type BrowserTask={id:string;url:string;goal:string};
export type BrowserStatus='queued'|'running'|'awaiting_human'|'awaiting_approval'|'approved'|'action_inflight'|'completed'|'failed'|'cancelled'|'needs_review';
export type BrowserJob={id:string;site:string;goal:string;status:BrowserStatus;message:string;steps:number;updatedAt:number;approval?:{id:string;label:string;url:string};result?:{summary:string;evidence:string;url:string};pageUrl?:string};
const statuses=['queued','running','awaiting_human','awaiting_approval','approved','action_inflight','completed','failed','cancelled','needs_review'];
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
 return {text,status:(finished?undefined:failed?'failed':'pending') as 'pending'|'failed'|undefined,jobStatus:j.status,browserUpdatedAt:j.updatedAt,browserApproval:j.approval,browserResultUrl:finished?browserUrl(j.result!.url)||undefined:undefined};
}
const base='/v1/assistant/browser/';
export async function listBrowserJobs(a:CloudAccount){
 const r=await cloud(base+'jobs',undefined,a.token);if(!Array.isArray(r.jobs))throw Error('웹 업무 목록 오류');
 return r.jobs.map(parseBrowserJob);
}
export async function browserCommand(a:CloudAccount,task:BrowserTask,action:'start'|'view'|'resume'|'approve'|'cancel',approvalId?:string){
 const r=await cloud(base+action,action==='start'?{...task,confirmed:true}:{id:task.id,...(approvalId?{approvalId}:{})},a.token,60000);
 if(action==='view'){
  const u=browserUrl(r.url);if(!u||new URL(u).hostname!=='live.browser.run')throw Error('인증 화면 주소가 올바르지 않습니다.');
  return {url:u};
 }
 const j=parseBrowserJob(r);if(j.id!==task.id)throw Error('웹 업무 ID 불일치');return {job:j};
}
