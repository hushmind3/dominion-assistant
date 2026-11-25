import {cloud,type CloudAccount} from './cloud';
import {workRequest} from './workGateway';
export const GOOGLE_TOOLS=['gmail_recent','calendar_upcoming','drive_recent'] as const;
export type GoogleTool=typeof GOOGLE_TOOLS[number];
export const isGoogleTool=(x:unknown):x is GoogleTool=>GOOGLE_TOOLS.includes(x as GoogleTool);
export const toolLabels:Record<GoogleTool,string>={gmail_recent:'최근 받은 메일',calendar_upcoming:'다가오는 일정',drive_recent:'최근 Drive 파일'};
export const serviceFor:Record<GoogleTool,string>={gmail_recent:'gmail',calendar_upcoming:'calendar',drive_recent:'drive'};
export async function googleResult(a:CloudAccount,operation:GoogleTool){
 const r=await cloud('/v1/assistant/google/run',{operation},a.token,60000);
 if(r.operation!==operation||!Array.isArray(r.items))throw Error('Google 조회 결과를 확인할 수 없습니다.');
 return formatGoogleResult(operation,r.items);
}
export function formatGoogleResult(operation:GoogleTool,items:any[]){
 const lines=items.slice(0,10).map((x:any,i:number)=>operation==='gmail_recent'?[x.subject||'(제목 없음)',x.from,x.date]:operation==='calendar_upcoming'?[x.title||'(제목 없음)',x.start,x.location]:[x.name,x.modified]).map((x:any[],i:number)=>(i+1)+'. '+x.filter(Boolean).map(v=>String(v).slice(0,300)).join(' · '));
 return toolLabels[operation]+'\n'+(lines.length?lines.join('\n'):'조회 결과가 없습니다.');
}

export type GoogleJob={id:string;operation:GoogleTool;status:'queued'|'running'|'awaiting_auth'|'completed'|'failed'|'cancelled';items?:any[];error?:string;createdAt:number;updatedAt:number};
export async function submitGoogleJob(a:CloudAccount,id:string,operation:GoogleTool):Promise<GoogleJob>{
 return workRequest(a,'google','start',{id,operation},'/v1/assistant/google/jobs',{id,operation});
}
export async function googleJobs(a:CloudAccount):Promise<GoogleJob[]>{
 const r=await cloud('/v1/assistant/google/jobs',undefined,a.token);
 if(!Array.isArray(r.jobs))throw Error('Google 작업 상태를 확인할 수 없습니다.');
 return r.jobs.filter((j:any)=>typeof j?.id==='string'&&isGoogleTool(j.operation)&&['queued','running','awaiting_auth','completed','failed','cancelled'].includes(j.status));
}
export async function cancelGoogleJob(a:CloudAccount,id:string){return workRequest(a,'google','cancel',{id},'/v1/assistant/google/jobs/cancel',{id})}
export function googleJobMessage(j:GoogleJob){
 const title=toolLabels[j.operation];
 const text=j.status==='completed'&&Array.isArray(j.items)?formatGoogleResult(j.operation,j.items):j.status==='awaiting_auth'?title+' · Google 연결이 필요합니다. 인증하면 자동으로 이어서 처리합니다.':j.status==='failed'?title+' · '+(j.error||'작업 실패'):j.status==='cancelled'?title+' · 작업 취소됨':title+' · 백그라운드에서 처리 중입니다.';
 return {text,status:(j.status==='completed'&&Array.isArray(j.items)?undefined:j.status==='failed'||j.status==='cancelled'||j.status==='completed'?'failed':'pending') as 'pending'|'failed'|undefined,jobStatus:j.status};
}
