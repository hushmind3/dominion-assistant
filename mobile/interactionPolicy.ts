import type {Message} from './messengerStore';
export function mergeInbox(current:Message[], incoming:unknown):Message[]{
 if(!Array.isArray(incoming))return current;
 const out=[...current];let changed=false;
 for(const m of incoming){
  if(!m||typeof m.id!=='string'||typeof m.deviceId!=='string'||typeof m.text!=='string'||!Number.isFinite(m.time))continue;
  const task=m.id.startsWith('task:'),id=task?'pc-answer:'+m.deviceId+':'+m.id.slice(5):'notice:'+m.deviceId+':'+m.id;
  const i=out.findIndex(x=>x.id===id);
  const entry:Message={id,role:'assistant',text:m.text,time:m.time,deviceId:m.deviceId,...(task?{requestId:m.id.slice(5)}:{})};
  if(i<0){out.push(entry);changed=true}
  else if(out[i].status==='pending'){out[i]={...out[i],...entry,status:undefined};changed=true}
 }
 return changed?out.sort((a,b)=>a.time-b.time).slice(-200):current;
}
export function drawerWidth(screenWidth:number){return Math.min(280,Math.max(0,screenWidth)*.68)}
export function drawerGesture(open:boolean,disabled:boolean,touches:number,x:number,dx:number,dy:number){
 if(disabled||touches!==1||Math.abs(dx)<12||Math.abs(dx)<Math.abs(dy)*1.5)return false;
 return open?dx<0:x<=56&&dx>0;
}
export function canListen(active:boolean,muted:boolean,foreground:boolean,phase:string){
 return active&&!muted&&foreground&&phase!=='thinking'&&phase!=='speaking';
}
export function sheetSnap(ratio:number,velocity=0){
 const projected=Math.max(0,Math.min(1,ratio-velocity*.12));
 return [0,.5,1].reduce((a,b)=>Math.abs(a-projected)<=Math.abs(b-projected)?a:b);
}
