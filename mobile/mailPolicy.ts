import type {Message,Journal} from './messengerStore';
export type MailFilters={mode:'all'|'important'|'matched';sender:string;keyword:string};
export type MailAlert={id:string;from:string;subject:string;date:string;category:string;important:boolean;unread:boolean;time:number};
export const DEFAULT_MAIL_FILTERS:MailFilters={mode:'important',sender:'',keyword:''};
export function mailProposal(x:unknown):MailFilters|null{
 if(!x||typeof x!=='object')return null;const v=x as Record<string,unknown>;
 if(v.action!=='mail_watch'||!['all','important','matched'].includes(String(v.mode)))return null;
 const mode=v.mode as MailFilters['mode'],sender=typeof v.sender==='string'?v.sender.trim().slice(0,160):'',keyword=typeof v.keyword==='string'?v.keyword.trim().slice(0,100):'';
 if(mode==='matched'&&!sender&&!keyword)return null;
 return {mode,sender:mode==='matched'?sender:'',keyword:mode==='matched'?keyword:''};
}
export function parseMailAlerts(x:unknown):MailAlert[]{
 if(!Array.isArray(x))return [];
 return x.slice(-100).filter((m):m is MailAlert=>!!m&&typeof m.id==='string'&&/^[A-Za-z0-9_-]{1,200}$/.test(m.id)&&typeof m.from==='string'&&typeof m.subject==='string'&&typeof m.category==='string'&&typeof m.date==='string'&&Number.isFinite(m.time));
}
export function mergeMailAlerts(j:Journal,rows:MailAlert[]):Journal{
 const seen=new Set(j.mailSeen||[]),messages:Message[]=[];
 for(const m of rows){
  const id='mail-alert:'+m.id;if(seen.has(m.id))continue;seen.add(m.id);
  if(j.messages.some(x=>x.id===id))continue;
  messages.push({id,role:'assistant',text:'📬 새 메일 · '+m.category+(m.important?' · 중요':'')+'\n'+m.subject+'\n보낸 사람: '+m.from+(m.date?'\n'+m.date:''),time:m.time,localOnly:true,mailId:m.id});
 }
 if(!messages.length&&seen.size===(j.mailSeen||[]).length)return j;
 return {...j,messages:[...j.messages,...messages].sort((a,b)=>a.time-b.time),mailSeen:[...seen].slice(-300)};
}
