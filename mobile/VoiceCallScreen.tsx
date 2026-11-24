import React,{useMemo,useRef,useState,type ReactNode} from 'react';
import {View,Text,Pressable,PanResponder,StyleSheet,Keyboard} from 'react-native';
import type {useVoiceCall} from './useVoiceCall';
import {sheetSnap} from './interactionPolicy';
import AssistantAvatar from './AssistantAvatar';
type Call=ReturnType<typeof useVoiceCall>;
export const duration=(s:number)=>String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');
const label=(call:Call)=>call.phase==='thinking'?'생각하고 있어요':call.phase==='speaking'?'말하고 있어요':call.muted?'마이크 꺼짐':call.phase==='listening'?'듣고 있어요':'잠시 대기 중';
const clamp=(v:number)=>Math.max(0,Math.min(1,v));
export default function VoiceCallScreen({call,name,avatar,children,error}:{call:Call;name:string;avatar?:string;children:ReactNode;error:string}){
 const [ratio,setRatio]=useState(0),[height,setHeight]=useState(0);
 const ratioRef=useRef(0),heightRef=useRef(0),origin=useRef(0);
 const change=(value:number)=>{const v=clamp(value);ratioRef.current=v;setRatio(v)};
 const snap=(value:number)=>{change(value);if(value===0)Keyboard.dismiss()};
 const cycle=()=>snap(ratioRef.current<.25?.5:ratioRef.current<.75?1:0);
 const pan=useMemo(()=>PanResponder.create({
  onMoveShouldSetPanResponder:(_,g)=>Math.abs(g.dy)>5&&Math.abs(g.dy)>Math.abs(g.dx),
  onPanResponderGrant:()=>{origin.current=ratioRef.current},
  onPanResponderMove:(_,g)=>change(origin.current-g.dy/Math.max(120,heightRef.current-38)),
  onPanResponderRelease:(_,g)=>{snap(sheetSnap(ratioRef.current,g.vy))},
  onPanResponderTerminate:()=>snap(sheetSnap(ratioRef.current)),
 }),[]);
 const control=(title:string,glyph:string,action:()=>void,selected=false,disabled=false,red=false)=><Pressable accessibilityRole="button" accessibilityLabel={title} accessibilityState={{disabled,selected}} disabled={disabled} onPress={action} style={[s.control,{backgroundColor:red?'#C84141':selected?'#B5E4BE':'#304D3A',opacity:disabled?.45:1}]}><Text style={{fontSize:22,color:selected?'#14261D':'#fff'}}>{glyph}</Text><Text numberOfLines={1} style={{fontSize:11,fontWeight:'600',color:selected?'#14261D':'#fff'}}>{title}</Text></Pressable>;
 return <View style={s.page}>
  <View style={s.top}><AssistantAvatar id={avatar} size={40}/><View style={{flex:1,minWidth:0}}><Text numberOfLines={1} style={s.name}>{name}</Text><Text accessibilityLiveRegion="polite" style={s.dim}>{duration(call.elapsed)} · {label(call)}</Text></View><Pressable accessibilityRole="button" accessibilityLabel="채팅 크기 변경" onPress={cycle} style={s.toggle}><Text style={{color:'#B5E4BE',fontSize:20}}>⌨</Text></Pressable></View>
  <View style={s.row}>{control(call.muted?'마이크 켜기':'음소거',call.muted?'⊘':'🎙',call.toggleMute,call.muted)}{control('말 멈추기','■',call.interrupt,false,call.phase!=='speaking')}{control(ratio<.25?'채팅 열기':ratio<.75?'채팅 확대':'채팅 접기',ratio<.75?'⌃':'⌄',cycle)}{control('통화 종료','☎',call.stop,false,false,true)}</View>
  {!!error&&<Text numberOfLines={2} accessibilityRole="alert" style={{color:'#FFB9AF',paddingHorizontal:12,paddingBottom:6,fontSize:12}}>{error}</Text>}
  <View style={{flex:1,minHeight:0}} onLayout={e=>{heightRef.current=e.nativeEvent.layout.height;setHeight(e.nativeEvent.layout.height)}}>
   <View style={{height:Math.max(0,height-38)*(1-ratio),overflow:'hidden',justifyContent:'center',alignItems:'center',gap:10,paddingHorizontal:20}}>
    {ratio<.94&&<><AssistantAvatar id={avatar} size={ratio>.25?60:104}/><Text style={s.status}>{label(call)}</Text>{!!call.partial&&<Text numberOfLines={3} style={s.transcript}>{call.partial}</Text>}</>}
   </View>
   <View {...pan.panHandlers} style={s.handle}>
    <Pressable accessibilityRole="adjustable" accessibilityLabel="채팅창 높이" accessibilityValue={{min:0,max:100,now:Math.round(ratio*100)}} accessibilityActions={[{name:'increment',label:'채팅 확대'},{name:'decrement',label:'채팅 축소'}]} onAccessibilityAction={e=>snap(e.nativeEvent.actionName==='increment'?ratioRef.current<.25?.5:1:ratioRef.current>.75?.5:0)} onPress={cycle} style={{flex:1,alignItems:'center',justifyContent:'center',gap:4}}>
     <View style={{width:38,height:4,borderRadius:3,backgroundColor:'#82968A'}}/><Text style={{fontSize:11,color:'#B8D2C0'}}>{ratio<.05?'위로 밀어 채팅 열기':'위아래로 밀어 크기 조절'}</Text>
    </Pressable>
   </View>
   <View style={{flex:1,minHeight:0,display:ratio>.01?'flex':'none',overflow:'hidden',borderTopLeftRadius:18,borderTopRightRadius:18}}>{children}</View>
  </View>
 </View>
}
const s=StyleSheet.create({page:{flex:1,backgroundColor:'#14261D'},top:{flexDirection:'row',alignItems:'center',paddingHorizontal:16,paddingTop:12,paddingBottom:8,gap:10},miniAvatar:{width:40,height:40,borderRadius:20,backgroundColor:'#304D3A',alignItems:'center',justifyContent:'center'},name:{color:'#fff',fontSize:17,fontWeight:'700'},dim:{color:'#B8D2C0',fontSize:12},toggle:{padding:10},row:{flexDirection:'row',gap:8,paddingHorizontal:12,paddingBottom:8},control:{flex:1,minWidth:0,minHeight:58,borderRadius:15,alignItems:'center',justifyContent:'center',gap:3,paddingHorizontal:3},avatar:{borderRadius:100,backgroundColor:'#304D3A',alignItems:'center',justifyContent:'center'},status:{color:'#EFF5F0',fontSize:17,fontWeight:'600'},transcript:{color:'#B8D2C0',textAlign:'center',fontSize:14},handle:{height:38,backgroundColor:'#23392B'}});
