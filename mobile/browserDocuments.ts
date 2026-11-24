import {File,Paths} from 'expo-file-system';
import {digest,CryptoDigestAlgorithm,randomUUID} from 'expo-crypto';
import * as Sharing from 'expo-sharing';
import type {CloudAccount} from './cloud';
import {fetchBrowserDocument,type BrowserTask,type BrowserDocument} from './browserTasks';
export async function shareBrowserDocument(a:CloudAccount,task:BrowserTask,doc:BrowserDocument,stillCurrent:()=>boolean){
 if(doc.expiresAt<=Date.now())throw Error('파일 보관 기간이 지났습니다.');
 if(!await Sharing.isAvailableAsync())throw Error('이 기기에서 파일 공유를 사용할 수 없습니다.');
 const data=await fetchBrowserDocument(a,task,doc);
 if(!stillCurrent())return;
 const binary=atob(data),bytes=new Uint8Array(binary.length);
 for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
 if(bytes.length!==doc.size||binary.slice(0,5)!=='%PDF-')throw Error('실제 PDF 파일인지 확인하지 못했습니다.');
 const hash=Array.from(new Uint8Array(await digest(CryptoDigestAlgorithm.SHA256,bytes)),n=>n.toString(16).padStart(2,'0')).join('');
 if(hash!==doc.sha256)throw Error('PDF 무결성 검증에 실패했습니다.');
 if(!stillCurrent())return;
 const file=new File(Paths.cache,(doc.kind==='original'?'original-':'page-')+randomUUID()+'.pdf');
 try{
  file.create();await file.write(bytes);
  if(stillCurrent())await Sharing.shareAsync(file.uri,{mimeType:'application/pdf',UTI:'com.adobe.pdf',dialogTitle:doc.kind==='original'?'원본 PDF 저장 또는 공유':'화면 PDF 저장 또는 공유'});
 }finally{if(file.exists)try{file.delete()}catch{}}
}
