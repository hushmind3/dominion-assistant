import React,{useState} from 'react';
import {Image,Pressable,Text,View,useColorScheme} from 'react-native';
export default function CloudImage({load}:{load:()=>Promise<{mime:string;base64:string}>}){
 const [uri,setUri]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');const dark=useColorScheme()==='dark';
 const preview=async()=>{if(busy)return;setBusy(true);setError('');try{const v=await load();if(!['image/jpeg','image/png','image/webp'].includes(v.mime)||typeof v.base64!=='string'||v.base64.length>800000)throw Error('사진 응답 오류');setUri('data:'+v.mime+';base64,'+v.base64)}catch(e){setError((e as Error).message)}finally{setBusy(false)}};
 return <View style={{gap:4}}>{uri?<Image accessibilityLabel="첨부 사진" source={{uri}} style={{width:200,height:150,borderRadius:12}} resizeMode="contain"/>:<Pressable accessibilityRole="button" disabled={busy} onPress={()=>void preview()} style={{padding:10}}><Text style={{color:dark?'#A5D5A1':'#35683F'}}>{busy?'사진 불러오는 중…':'사진 보기'}</Text></Pressable>}{!!error&&<Text style={{color:dark?'#FFB4A8':'#A43B30'}}>{error}</Text>}</View>
}
