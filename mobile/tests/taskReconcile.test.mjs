import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeWorkUpdates} from '../taskReconcile.ts';

const browser=(status='pending')=>({id:'browser-job:b1',role:'assistant',text:'준비',time:10,status,jobStatus:'running',browserTask:{id:'b1',url:'https://example.com/',goal:'조회'},browserUpdatedAt:10});
const google=()=>({id:'google-job:g1',role:'assistant',text:'대기',time:11,status:'pending',jobStatus:'queued',googleJob:{id:'g1',operation:'gmail_recent'}});
test('different tool kinds reconcile in the original messages, preserving order',()=>{
 const current=[browser(),google()];
 const next=mergeWorkUpdates(current,[{kind:'google',id:'g1',updatedAt:20,patch:{text:'완료',status:undefined,jobStatus:'completed'}},{kind:'browser',id:'b1',updatedAt:30,patch:{text:'증거',status:undefined,jobStatus:'completed'}}]);
 assert.deepEqual(next.map(m=>m.text),['증거','완료']);
 assert.equal(next[0].browserUpdatedAt,30);
 assert.equal(next[1].googleUpdatedAt,20);
 assert.equal(current[0].text,'준비');
});
test('stale polls cannot regress completed browser and Google jobs',()=>{
 const complete=[{...browser(undefined),text:'실제 결과',jobStatus:'completed',browserUpdatedAt:200},{...google(),text:'메일 2건',status:undefined,jobStatus:'completed',googleUpdatedAt:200}];
 const updates=[{kind:'browser',id:'b1',updatedAt:100,patch:{text:'처리 중',status:'pending',jobStatus:'running'}},{kind:'google',id:'g1',updatedAt:200,patch:{text:'요청 중',status:'pending',jobStatus:'running'}}];
 assert.equal(mergeWorkUpdates(complete,updates),complete);
});
test('equal snapshots and equivalent document arrays never rewrite SQLite records',()=>{
 const original=[{...browser(),browserDocuments:[{id:'doc123456',name:'page.pdf'}]}];
 const next=mergeWorkUpdates(original,[{kind:'browser',id:'b1',updatedAt:10,patch:{text:'준비',status:'pending',jobStatus:'running',browserDocuments:[{id:'doc123456',name:'page.pdf'}]}}]);
 assert.equal(next,original);
});
test('wrong job ID, nonfinite timestamp, unrelated kind and invalid records are ignored',()=>{
 const current=[browser(),google()];
 assert.equal(mergeWorkUpdates(current,[{kind:'google',id:'b1',updatedAt:10,patch:{text:'oops'}},{kind:'browser',id:'g1',updatedAt:10,patch:{text:'oops'}},{kind:'browser',id:'b1',updatedAt:NaN,patch:{text:'oops'}},{kind:'browser',id:'b1',updatedAt:-1,patch:{text:'oops'}}]),current);
});
test('unknown submission can recover from confirmed server state',()=>{
 const original=[{...google(),text:'접수 불확실',status:'failed',jobStatus:'submit_unknown'}];
 const next=mergeWorkUpdates(original,[{kind:'google',id:'g1',updatedAt:1,patch:{text:'실행 중',status:'pending',jobStatus:'running'}}]);
 assert.equal(next[0].status,'pending');
 assert.equal(next[0].googleUpdatedAt,1);
});
