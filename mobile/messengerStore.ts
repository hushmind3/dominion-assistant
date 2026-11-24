import type {Profile} from './ProfilePanel';
import type {CallPreferences} from './CallPreferences';
import type {VoicePreference} from './VoicePicker';
import type {Memory} from './memoryPolicy';
import {File,Paths} from 'expo-file-system';
import {digestStringAsync,CryptoDigestAlgorithm} from 'expo-crypto';
import {openDatabaseAsync,type SQLiteDatabase} from 'expo-sqlite';
export type Message={id:string;role:'user'|'assistant';text:string;time:number;status?:'pending'|'failed';deviceId?:string;requestId?:string;attachment?:string;localOnly?:boolean};
export type Journal={messages:Message[];model:string;deviceId?:string;profile?:Profile;offlineRecognition?:boolean;callPreferences?:CallPreferences;voice?:VoicePreference;memory?:Memory[];memoryEnabled?:boolean};
const empty=():Journal=>({messages:[],model:'dominion/free-auto'});
const opened=new Map<string,Promise<SQLiteDatabase>>();
async function db(account:string){
 let pending=opened.get(account);if(!pending){pending=(async()=>{
 const hash=await digestStringAsync(CryptoDigestAlgorithm.SHA256,account);
 const d=await openDatabaseAsync('assistant-'+hash+'.db');
 await d.execAsync('PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1),data TEXT NOT NULL); CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY,time INTEGER NOT NULL,data TEXT NOT NULL); CREATE INDEX IF NOT EXISTS message_time ON messages(time,id);');
 const exists=await d.getFirstAsync<{id:number}>('SELECT id FROM settings WHERE id=1');
 if(!exists){
  const old=new File(Paths.document,'assistant-'+hash+'.json');
  const j=old.exists?JSON.parse(await old.text()) as Journal:empty();
  if(!Array.isArray(j.messages))throw Error('기존 대화 기록을 읽을 수 없습니다.');
  await write(d,j);
  if(old.exists)try{old.delete()}catch{/* migrated copy remains in sandbox; deletion also removes it */}
 }return d;
 })();opened.set(account,pending);pending.catch(()=>opened.delete(account))}
 return pending;
}
async function write(d:SQLiteDatabase,j:Journal,previous?:Journal){
 const {messages,...settings}=j;
 // Write only changed records; deleting chat is explicit, not rolling retention.
 const before=new Map((previous?.messages||[]).map(m=>[m.id,m]));
 const after=new Set(messages.map(m=>m.id));
 await d.withExclusiveTransactionAsync(async tx=>{
  await tx.runAsync('INSERT INTO settings(id,data) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data',JSON.stringify(settings));
  if(!messages.length)await tx.runAsync('DELETE FROM messages');
  else {
   for(const m of messages)if(before.get(m.id)!==m)await tx.runAsync('INSERT INTO messages(id,time,data) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET time=excluded.time,data=excluded.data',m.id,m.time,JSON.stringify(m));
   for(const id of before.keys())if(!after.has(id))await tx.runAsync('DELETE FROM messages WHERE id=?',id);
  }
 });
}
export async function loadJournal(account:string):Promise<Journal>{
 const d=await db(account),s=await d.getFirstAsync<{data:string}>('SELECT data FROM settings WHERE id=1');
 const rows=await d.getAllAsync<{data:string}>('SELECT data FROM messages ORDER BY time,id');
 return {...empty(),...(s?JSON.parse(s.data):{}),messages:rows.map(r=>JSON.parse(r.data) as Message).map(m=>m.status==='pending'&&!m.deviceId?{...m,status:'failed'}:m)};
}
export async function saveJournal(account:string,j:Journal,previous?:Journal){await write(await db(account),j,previous)}
export async function deleteJournal(account:string){await write(await db(account),empty());const hash=await digestStringAsync(CryptoDigestAlgorithm.SHA256,account);const old=new File(Paths.document,'assistant-'+hash+'.json');if(old.exists)old.delete()}
