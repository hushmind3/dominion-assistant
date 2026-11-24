import {cloud,type CloudAccount} from './cloud';
import {parseMailAlerts,type MailFilters,type MailAlert} from './mailPolicy';
export type MailWatchStatus={enabled:boolean;state:'active'|'disabled'|'reauthorize'|'needs_resync';filters:MailFilters;lastCheck?:number|null;nextCheck?:number;error?:string|null};
const base='/v1/assistant/mail/';
export async function mailStatus(a:CloudAccount):Promise<MailWatchStatus>{return cloud(base+'status',undefined,a.token)}
export async function mailAlerts(a:CloudAccount):Promise<MailAlert[]>{const r=await cloud(base+'alerts',undefined,a.token);return parseMailAlerts(r.alerts)}
export async function startMailWatch(a:CloudAccount,filters:MailFilters){await cloud(base+'start',{...filters,confirmed:true},a.token,45000)}
export async function stopMailWatch(a:CloudAccount){await cloud(base+'stop',{},a.token)}
export async function checkMailWatch(a:CloudAccount){await cloud(base+'check',{},a.token,45000)}
