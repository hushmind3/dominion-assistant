import type {Message} from './messengerStore';
export type Memory={id:string;key:string;text:string;time:number;sourceId:string};
const safe=(m:Message)=>!m.status&&!m.localOnly&&!m.deviceId&&!m.attachment&&!!m.text.trim()&&!sensitive(m.text);
export function sensitive(text:string){return /비밀번호|패스워드|인증번호|인증 코드|일회용|주민등록번호|계좌번호|카드번호|password|passcode|api.?key|access.?token|refresh.?token|bearer\s|\b\d{6}[- ]?[1-4]\d{6}\b|\b(?:\d[ -]?){13,19}\b/i.test(text)}
// Conservative extraction: record the user's own statement, never model-generated claims.
export function learn(mem:Memory[],message:Message):Memory[]{
 if(message.role!=='user'||!safe(message))return mem;
 const t=message.text.trim();if(t.length>500||/[?？]|\b(?:if|maybe)\b|가정|예를 들어|일지도|인가요|겠냐|어떻게|뭐가|왜 |할까|인 경우/.test(t))return mem;
 let key='';
 const pref=t.match(/^(?:앞으로\s+)?(?:나는|저는|난|내가|제가)\s+(.{1,120}?)(?:을|를|이|가)?\s*(좋아해(?:요)?|싫어해(?:요)?|선호해(?:요)?|안 먹어(?:요)?|못 먹어(?:요)?)\s*[.!]?$/);
 if(pref)key='preference:'+pref[1].trim().replace(/[을를이가]$/,'');
 else if(/^(?:앞으로\s+)?(?:기억해|기억해줘|기억해 줘|기억해라|기억하세요|기억해 주세요)\s*[:： ]/.test(t))key='explicit:'+t.toLowerCase();
 else {const fact=t.match(/^(?:내|제)\s*(비서 이름|호칭|직업|거주 지역|생일)(?:은|는)\s+(.+)$/);if(fact)key='fact:'+fact[1]}
 if(!key)return mem;
 return [...mem.filter(m=>m.key!==key),{id:message.id,key,text:t,time:message.time,sourceId:message.id}];
}
export function terms(s:string):Set<string>{
 const out=new Set<string>();for(const word of s.toLowerCase().match(/[가-힣a-z0-9]{2,}/g)||[]){
  if(/^[가-힣]+$/.test(word)){for(let i=0;i<word.length-1;i++)out.add(word.slice(i,i+2))}else out.add(word);
 }return out;
}
const score=(q:Set<string>,text:string)=>{let n=0;for(const t of terms(text))if(q.has(t))n++;return n};
export function context(messages:Message[],memory:Memory[],query:string,excluded:string,limit=12000){
 const q=terms(query),recent=messages.filter(m=>m.id!==excluded&&safe(m)).slice(-12);
 const recentIds=new Set(recent.map(m=>m.id));
 const picked=messages.filter(m=>m.role==='user'&&m.id!==excluded&&!recentIds.has(m.id)&&safe(m)).map(m=>({m,n:score(q,m.text)})).filter(x=>x.n>0).sort((a,b)=>b.n-a.n||b.m.time-a.m.time).slice(0,8);
 const facts=memory.filter(m=>!sensitive(m.text)).map(m=>({m,n:score(q,m.text)})).filter(x=>x.n>0||x.m.key.startsWith('explicit:')).sort((a,b)=>b.n-a.n||b.m.time-a.m.time).slice(0,8);
 let left=limit;const take=(s:string,n:number)=>{const v=s.slice(0,Math.min(n,Math.max(0,left)));left-=v.length;return v};
 const reserve=picked.length||facts.length?Math.min(4000,Math.floor(limit/3)):0;left=limit-reserve;
 const recentText=recent.slice().reverse().map(m=>({role:m.role,content:take(m.text,1600)})).filter(m=>m.content).reverse();left+=reserve;
 const recalled=[...facts.map(x=>({type:'user_memory',text:take(x.m.text,400)})),...picked.map(x=>({type:'past_user_message',text:take(x.m.text,500)}))].filter(x=>x.text);
 return {recent:recentText,recalled};
}
