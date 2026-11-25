import type {Message} from './messengerStore';

/** A local, derived catalog of previously completed browser work.
 * No independent credential cache, server sync or automatic click replay.
 * History stays partitioned by the account-local journal that owns it.
 */
export function reusableBrowserWork(messages:Message[],limit=8):Message[]{
 if(!Array.isArray(messages)||!Number.isInteger(limit)||limit<1||limit>100)return [];
 const seen=new Set<string>(),result:Message[]=[];
 for(let index=messages.length-1;index>=0;index--){
  const m=messages[index],task=m.browserTask;
  if(!task||m.jobStatus!=='completed'||m.status!==undefined||!Number.isFinite(m.browserUpdatedAt)||typeof task.goal!=='string'||!task.goal.trim())continue;
  try{
   const url=new URL(task.url);
   if(url.protocol!=='https:'||url.username||url.password)continue;
   const key=url.href+'\n'+task.goal.trim().replace(/\s+/g,' ').toLocaleLowerCase();
   if(seen.has(key))continue;
   seen.add(key);result.push(m);
   if(result.length>=limit)break;
  }catch{/* malformed historical task; never suggest it */}
 }
 return result;
}
