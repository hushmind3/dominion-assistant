import * as Picker from 'expo-image-picker';
import {ImageManipulator,SaveFormat} from 'expo-image-manipulator';
import {Alert} from 'react-native';
import {validAvatarPhoto} from './assistantIdentity';
let picking=false;
export async function chooseAvatarPhoto(camera:boolean):Promise<string|null>{
 if(picking)return null;
 picking=true;
 try{
  if(camera&&!(await Picker.requestCameraPermissionsAsync()).granted)throw Error('카메라 권한을 허용하세요.');
  const options:Picker.ImagePickerOptions={mediaTypes:['images'],allowsEditing:true,aspect:[1,1],quality:.85,exif:false};
  const result=camera?await Picker.launchCameraAsync(options):await Picker.launchImageLibraryAsync(options);
  if(result.canceled)return null;
  const asset=result.assets[0];
  const context=ImageManipulator.manipulate(asset.uri);
  context.resize({width:256,height:256});
  const image=await context.renderAsync();
  const out=await image.saveAsync({format:SaveFormat.JPEG,compress:.8,base64:true});
  const data='data:image/jpeg;base64,'+(out.base64||'');
  if(!validAvatarPhoto(data))throw Error('프로필 사진을 저장할 수 없습니다. 다른 사진을 선택하세요.');
  return data;
 }finally{picking=false}
}
export function avatarPhotoActions(onPick:(camera:boolean)=>void){
 Alert.alert('프로필 사진 변경','',[{text:'취소',style:'cancel'},{text:'사진첩',onPress:()=>onPick(false)},{text:'카메라',onPress:()=>onPick(true)}]);
}
