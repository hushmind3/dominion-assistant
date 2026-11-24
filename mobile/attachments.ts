import * as ImagePicker from 'expo-image-picker';
import * as DocumentPicker from 'expo-document-picker';
import {File} from 'expo-file-system';
export type DraftAttachment={name:string;mime:string;uri:string;base64:string};
export type Attachment={id:string;name:string;mime:string;size:number;method:'text'|'vision'};
const MAX=4*1024*1024;
function check(base64:string){if(!base64||base64.length>Math.ceil(MAX/3)*4)throw Error('첨부파일은 4MB 이하만 가능합니다.');return base64}
export async function choosePhoto(camera=false):Promise<DraftAttachment|null>{
 if(camera&&!(await ImagePicker.requestCameraPermissionsAsync()).granted)throw Error('카메라 권한을 허용하세요.');
 const options:ImagePicker.ImagePickerOptions={mediaTypes:['images'],quality:.7,base64:true,exif:false};
 const result=camera?await ImagePicker.launchCameraAsync(options):await ImagePicker.launchImageLibraryAsync(options);
 if(result.canceled)return null;
 const a=result.assets[0];
 return {name:(a.fileName||'photo').replace(/\.[^.]+$/,'')+'.jpg',mime:'image/jpeg',uri:a.uri,base64:check(a.base64||'')};
}
export async function chooseDocument():Promise<DraftAttachment|null>{
 const r=await DocumentPicker.getDocumentAsync({type:['text/*','application/pdf','application/json','application/vnd.openxmlformats-officedocument.wordprocessingml.document'],copyToCacheDirectory:true,multiple:false});
 if(r.canceled)return null;
 const a=r.assets[0],file=new File(a.uri);
 if((a.size||file.size||0)>MAX)throw Error('첨부파일은 4MB 이하만 가능합니다.');
 return {name:a.name,mime:a.mimeType||'application/octet-stream',uri:a.uri,base64:check(await file.base64())};
}
