import type {Profile} from './ProfilePanel';
import type {CallPreferences} from './CallPreferences';
import type {VoicePreference} from './VoicePicker';
import {File,Paths} from 'expo-file-system';
import {digestStringAsync,CryptoDigestAlgorithm} from 'expo-crypto';
export type Message={id:string;role:'user'|'assistant';text:string;time:number;status?:'pending'|'failed';deviceId?:string;requestId?:string;attachment?:string};
export type Journal={messages:Message[];model:string;deviceId?:string;profile?:Profile;offlineRecognition?:boolean;callPreferences?:CallPreferences;voice?:VoicePreference};
const empty=():Journal=>({messages:[],model:'dominion/free-auto'});
async function file(account:string){return new File(Paths.document,'assistant-'+await digestStringAsync(CryptoDigestAlgorithm.SHA256,account)+'.json')}
export async function loadJournal(account:string):Promise<Journal>{
 const f=await file(account);if(!f.exists)return empty();
 const j=JSON.parse(await f.text()) as Journal;if(!Array.isArray(j.messages))throw Error('대화 기록을 읽을 수 없습니다.');
 return {...j,messages:j.messages.slice(-200).map(m=>m.status==='pending'?{...m,status:'failed'}:m)};
}
export async function saveJournal(account:string,j:Journal){
 const f=await file(account),data=JSON.stringify({...j,messages:j.messages.slice(-200).map(m=>({...m,text:m.text.slice(0,16000)}))});
 if(!f.exists)f.create();
 f.write(data);
}
export async function deleteJournal(account:string){const f=await file(account);if(f.exists)f.delete()}
