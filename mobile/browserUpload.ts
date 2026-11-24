import * as DocumentPicker from 'expo-document-picker';
import {File} from 'expo-file-system';
import type {BrowserUpload} from './browserTasks';
export async function chooseBrowserUpload():Promise<BrowserUpload|null>{
 const r=await DocumentPicker.getDocumentAsync({type:['application/pdf','image/jpeg','image/png'],copyToCacheDirectory:true,multiple:false});
 if(r.canceled)return null;const a=r.assets[0],file=new File(a.uri);
 try{
  const mime=/\.pdf$/i.test(a.name)?'application/pdf':/\.jpe?g$/i.test(a.name)?'image/jpeg':/\.png$/i.test(a.name)?'image/png':'';
  if(!mime||!file.exists||file.size<1||file.size>2*1024*1024)throw Error('2MB 이하의 PDF, JPG, PNG 파일만 첨부할 수 있습니다.');
  const base64=await file.base64();
  if(!base64||base64.length>Math.ceil(2*1024*1024/3)*4)throw Error('파일 크기가 제한을 초과했습니다.');
  return {name:a.name,mime,base64};
 }finally{if(file.exists)try{file.delete()}catch{}}
}
