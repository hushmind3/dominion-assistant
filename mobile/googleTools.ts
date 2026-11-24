import {cloud,type CloudAccount} from './cloud';
export const GOOGLE_TOOLS=['gmail_recent','calendar_upcoming','drive_recent'] as const;
export type GoogleTool=typeof GOOGLE_TOOLS[number];
export const isGoogleTool=(x:unknown):x is GoogleTool=>GOOGLE_TOOLS.includes(x as GoogleTool);
export const toolLabels:Record<GoogleTool,string>={gmail_recent:'최근 받은 메일',calendar_upcoming:'다가오는 일정',drive_recent:'최근 Drive 파일'};
export const serviceFor:Record<GoogleTool,string>={gmail_recent:'gmail',calendar_upcoming:'calendar',drive_recent:'drive'};
export async function googleResult(a:CloudAccount,operation:GoogleTool){
 const r=await cloud('/v1/assistant/google/run',{operation},a.token,60000);
 if(r.operation!==operation||!Array.isArray(r.items))throw Error('Google 조회 결과를 확인할 수 없습니다.');
 const lines=r.items.slice(0,10).map((x:any,i:number)=>operation==='gmail_recent'?[x.subject||'(제목 없음)',x.from,x.date]:operation==='calendar_upcoming'?[x.title||'(제목 없음)',x.start,x.location]:[x.name,x.modified]).map((x:any[],i:number)=>(i+1)+'. '+x.filter(Boolean).map(v=>String(v).slice(0,300)).join(' · '));
 return toolLabels[operation]+'\n'+(lines.length?lines.join('\n'):'조회 결과가 없습니다.');
}
