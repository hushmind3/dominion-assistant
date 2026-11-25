import type {Message} from './messengerStore';

export type WorkKind='browser'|'google';
export type WorkUpdate={kind:WorkKind;id:string;updatedAt:number;patch:Partial<Message>};

const terminal=new Set(['completed','failed','cancelled','needs_review']);
function sameValue(a:unknown,b:unknown):boolean{
 return a===b||!!a&&!!b&&typeof a==='object'&&typeof b==='object'&&JSON.stringify(a)===JSON.stringify(b);
}

/** Reconcile durable cloud job results into existing chat records.
 * The server's job ID, kind and update time must match; stale polls never roll
 * a completed result back to running. Unchanged snapshots preserve identity.
 */
export function mergeWorkUpdates(current:Message[],updates:WorkUpdate[]):Message[]{
 let next=current;
 for(const u of updates){
  if(!u||!['browser','google'].includes(u.kind)||typeof u.id!=='string'||!u.id||!Number.isFinite(u.updatedAt)||u.updatedAt<0||!u.patch||typeof u.patch!=='object')continue;
  const index=next.findIndex(m=>u.kind==='browser'?m.browserTask?.id===u.id:m.googleJob?.id===u.id);
  if(index<0)continue;
  const old=next[index],previous=u.kind==='browser'?old.browserUpdatedAt:old.googleUpdatedAt;
  if(typeof previous==='number'&&Number.isFinite(previous)&&u.updatedAt<previous)continue;
  const status=u.patch.jobStatus;
  if(typeof status==='string'&&terminal.has(old.jobStatus||'')&&!terminal.has(status)&&typeof previous==='number'&&u.updatedAt<=previous)continue;
  const patch:Partial<Message>=u.kind==='browser'?{...u.patch,browserUpdatedAt:u.updatedAt}:{...u.patch,googleUpdatedAt:u.updatedAt};
  if(!Object.keys(patch).some(k=>!sameValue(old[k as keyof Message],patch[k as keyof Message])))continue;
  if(next===current)next=[...current];
  next[index]={...old,...patch};
 }
 return next;
}
