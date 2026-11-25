import test from 'node:test';
import assert from 'node:assert/strict';
import {reusableBrowserWork} from '../workLibrary.ts';
const saved=(id,goal='사업자 상태 조회',url='https://example.com/start')=>({id,role:'assistant',text:'검증된 결과',time:10,browserTask:{id,url,goal},browserUpdatedAt:100,jobStatus:'completed'});
test('reuse shows the latest verified successful task only once',()=>{
 const rows=[saved('a'),saved('b'),saved('c','다른 업무'),{...saved('f'),status:'failed'},{...saved('p'),jobStatus:'running'}];
 assert.deepEqual(reusableBrowserWork(rows).map(x=>x.id),['c','b']);
 assert.deepEqual(reusableBrowserWork(rows,1).map(x=>x.id),['c']);
});
test('pending jobs, invalid URLs, missing revisions and empty goals do not become templates',()=>{
 const rows=[{...saved('a'),browserUpdatedAt:undefined},saved('b',' ','https://example.com'),saved('c','조회','http://example.com'),saved('d','조회','https://user:pass@example.com'),saved('ok')];
 assert.deepEqual(reusableBrowserWork(rows).map(x=>x.id),['ok']);
});
test('no templates if empty and invalid limits',()=>{
 assert.deepEqual(reusableBrowserWork([]),[]);
 assert.deepEqual(reusableBrowserWork([saved('a')],0),[]);
});
