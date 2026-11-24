import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeInbox,drawerGesture,drawerWidth,canListen,sheetSnap} from '../interactionPolicy.ts';
test('inbox replaces pending task, preserves final reply and deduplicates',()=>{
 const pending={id:'pc-answer:d:r',role:'assistant',text:'작업 중',status:'pending',deviceId:'d',time:1};
 const notice={id:'task:r',deviceId:'d',text:'완료',time:2};
 const merged=mergeInbox([pending],[notice,notice]);
 assert.equal(merged.length,1);assert.equal(merged[0].text,'완료');assert.equal(merged[0].status,undefined);
 assert.equal(mergeInbox(merged,[notice]),merged);
 const final=[{...pending,text:'상세 결과',status:undefined}];assert.equal(mergeInbox(final,[notice]),final);
});
test('invalid inbox and same request IDs on distinct devices',()=>{
 const x=mergeInbox([],[null,{id:'task:r',deviceId:'a',text:'A',time:1},{id:'task:r',deviceId:'b',text:'B',time:2},{id:'x',deviceId:'b',text:'bad',time:'x'}]);
 assert.equal(x.length,2);assert.notEqual(x[0].id,x[1].id);
});
test('drawer accepts edge horizontal swipe only and never during call',()=>{
 assert.equal(drawerGesture(false,false,1,22,80,10),true);
 assert.equal(drawerGesture(false,false,1,100,80,10),false);
 assert.equal(drawerGesture(false,false,1,22,20,80),false);
 assert.equal(drawerGesture(false,true,1,22,80,10),false);
 assert.equal(drawerGesture(true,false,1,100,-80,10),true);
 assert.equal(drawerGesture(false,false,2,22,80,10),false);
});
test('sheet snap is independent of mic state',()=>{
 assert.equal(sheetSnap(.1),0);assert.equal(sheetSnap(.55),.5);assert.equal(sheetSnap(.9),1);
 assert.equal(sheetSnap(.4,-3),1);
 assert.equal(canListen(true,false,true,'paused'),true);
 assert.equal(canListen(true,true,true,'paused'),false);
 assert.equal(canListen(true,false,true,'speaking'),false);
 assert.equal(canListen(true,false,false,'paused'),false);
 assert.equal(canListen(false,false,true,'paused'),false);
});

import {assistantName,avatarFor,AVATARS} from '../assistantIdentity.ts';
test('drawer preserves a visible return area and accepts reverse swipe',()=>{for(const w of [320,360,400,768]){assert.ok(drawerWidth(w)<=w*.68);assert.ok(w-drawerWidth(w)>=w*.32-1)}assert.equal(drawerGesture(true,false,1,350,-60,5),true)});
test('personal identity supports old profiles and rejects blank names',()=>{assert.equal(assistantName('  루나  '),'루나');assert.equal(assistantName(' '),'나의 AI 비서');assert.equal(avatarFor('unknown').id,'star');assert.equal(new Set(AVATARS.map(a=>a.id)).size,6)});
