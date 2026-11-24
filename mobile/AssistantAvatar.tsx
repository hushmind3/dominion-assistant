import React from 'react';
import {View,Text} from 'react-native';
import {avatarFor} from './assistantIdentity';
export default function AssistantAvatar({id,size=44}:{id?:string;size?:number}){
 const a=avatarFor(id);
 return <View style={{width:size,height:size,borderRadius:size/2,alignItems:'center',justifyContent:'center',backgroundColor:a.color}}><Text style={{color:'#FFFFFF',fontSize:size*.52,lineHeight:size*.64,fontWeight:'600',textAlign:'center'}}>{a.glyph}</Text></View>
}
