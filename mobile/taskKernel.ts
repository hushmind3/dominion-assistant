import {taskProposal,browserPatch,type BrowserJob,type BrowserTask} from './browserTasks';
import {googleJobMessage,isGoogleTool,toolLabels,type GoogleJob,type GoogleTool} from './googleTools';
import type {Message} from './messengerStore';
import type {WorkUpdate} from './taskReconcile';

/** Existing browser and Google cloud APIs are adapters; the conversation sees
 * one prepared-task and status-update contract. No background worker runs here.
 */
export type WorkPlan={kind:'browser';task:BrowserTask}|{kind:'google';id:string;operation:GoogleTool};

export function planWork(proposal:unknown,id:string):WorkPlan|null{
 if(!id||typeof id!=='string'||!proposal||typeof proposal!=='object')return null;
 const browser=taskProposal(proposal,id);
 if(browser)return {kind:'browser',task:browser};
 const action=(proposal as Record<string,unknown>).action;
 return isGoogleTool(action)?{kind:'google',id,operation:action}:null;
}
export function preparedWorkMessage(plan:WorkPlan,time:number):Message{
 if(plan.kind==='browser')return {id:'browser-job:'+plan.task.id,role:'assistant',text:'웹 업무를 준비했습니다.\n'+plan.task.url+'\n'+plan.task.goal,status:'pending',time,localOnly:true,browserTask:plan.task,jobStatus:'awaiting_start'};
 return {id:'google-job:'+plan.id,role:'assistant',text:toolLabels[plan.operation]+' · 작업 요청 중',time,status:'pending',localOnly:true,googleJob:{id:plan.id,operation:plan.operation},jobStatus:'submitting'};
}
export function browserWorkUpdate(job:BrowserJob):WorkUpdate{
 return {kind:'browser',id:job.id,updatedAt:job.updatedAt,patch:browserPatch(job)};
}
export function googleWorkUpdate(job:GoogleJob):WorkUpdate{
 return {kind:'google',id:job.id,updatedAt:job.updatedAt,patch:googleJobMessage(job)};
}
/** A reusable task template, not an automatic replay of authenticated clicks.
 * A fresh job ID and explicit start confirmation are required every time.
 */
export function repeatBrowserWork(message:Message,id:string,time:number):Message|null{
 if(!message.browserTask||message.jobStatus!=='completed')return null;
 const plan=planWork({action:'browser_task',url:message.browserTask.url,goal:message.browserTask.goal},id);
 return plan?.kind==='browser'?preparedWorkMessage(plan,time):null;
}
