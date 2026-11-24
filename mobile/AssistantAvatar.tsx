import React,{useState} from 'react';
import {View,Text,Image} from 'react-native';
import {avatarFor,validAvatarPhoto} from './assistantIdentity';
export default function AssistantAvatar({id,photo,size=44}:{id?:string;photo?:string;size?:number}){
 const a=avatarFor(id),[failed,setFailed]=useState<string>();
 if(validAvatarPhoto(photo)&&failed!==photo)return <Image accessibilityLabel="비서 프로필 사진" source={{uri:photo}} onError={()=>setFailed(photo)} resizeMode="cover" style={{width:size,height:size,borderRadius:size/2,backgroundColor:a.color}}/>;
 return <View style={{width:size,height:size,borderRadius:size/2,alignItems:'center',justifyContent:'center',backgroundColor:a.color}}><Text style={{color:'#FFFFFF',fontSize:size*.52,lineHeight:size*.64,fontWeight:'600',textAlign:'center'}}>{a.glyph}</Text></View>
}
