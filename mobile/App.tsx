import React,{useEffect,useRef,useState} from 'react';
import {AppState, SafeAreaView, View, Text, TextInput, Pressable, StyleSheet, useColorScheme, Platform, PermissionsAndroid, ScrollView, KeyboardAvoidingView} from 'react-native';
import {AudioSession,registerGlobals} from '@livekit/react-native';
import {Room,RoomEvent,ConnectionState} from 'livekit-client';
import PersonalChat from './PersonalChat';
import AssistantChat,{type VoiceSession} from './AssistantChat';
import Messenger from './Messenger';

registerGlobals();
type Pairing={url:string;token:string;room:string;expiresAt:number};
function parse(raw:string):Pairing{
  const p=JSON.parse(raw);
  if(!p||typeof p.url!=='string'||!p.url.startsWith('wss://')||
    typeof p.token!=='string'||p.token.split('.').length!==3||
    typeof p.room!=='string'||!/^dominion-[a-f0-9]{32}$/.test(p.room)||
    !Number.isFinite(p.expiresAt)||p.expiresAt<=Date.now()/1000)throw new Error('연결 정보가 올바르지 않거나 만료되었습니다.');
  return p;
}
export default function App(){
 const [mode,setMode]=useState<'messenger'|'assistant'|'voice'|'legacy'>('messenger');
 const [session,setSession]=useState<VoiceSession|null>(null);
 return <View style={{flex:1}}>
  <View style={{flex:1,display:mode==='messenger'?'flex':'none'}}><Messenger onCall={s=>{setSession(s);setMode('voice')}} onLegacy={()=>setMode('assistant')}/></View>
  {mode==='assistant'&&<View style={{flex:1}}><AssistantChat onAdvanced={s=>{setSession(s||null);setMode('voice')}} onLegacy={()=>setMode('legacy')}/><Pressable onPress={()=>setMode('messenger')} style={{padding:12,alignItems:'center'}}><Text style={{color:'#3976d7'}}>비서 메신저로 돌아가기</Text></Pressable></View>}
  {mode==='voice'&&<LiveKitApp session={session} onEnd={()=>{setSession(null);setMode('messenger')}}/>}
  {mode==='legacy'&&<View style={{flex:1}}><PersonalChat onAdvanced={()=>{setSession(null);setMode('voice')}}/><Pressable onPress={()=>setMode('messenger')} style={{padding:12,alignItems:'center'}}><Text style={{color:'#3976d7'}}>비서 메신저로 돌아가기</Text></Pressable></View>}
 </View>;
}
function LiveKitApp({session,onEnd}:{session:VoiceSession|null;onEnd:()=>void}){
  const dark=useColorScheme()==='dark',ink=dark?'#f1f4fa':'#182233';
  const [peerReady,setPeerReady]=useState(false);
  const [raw,setRaw]=useState(session?.pairing||''),[status,setStatus]=useState('연결 대기'),[error,setError]=useState(''),[mic,setMic]=useState(false),[connected,setConnected]=useState(false),[busy,setBusy]=useState(false);
  const roomRef=useRef<Room|null>(null),generation=useRef(0),inFlight=useRef(false);
  const stopRef=useRef(session?.stop);
  const [started,setStarted]=useState<number|null>(null),[elapsed,setElapsed]=useState(0);
  const [speaker,setSpeaker]=useState(false);
  useEffect(()=>{if(started===null)return;const timer=setInterval(()=>setElapsed(Math.floor((Date.now()-started)/1000)),1000);return()=>clearInterval(timer)},[started]);
  async function disconnect(){
    generation.current++;
    const room=roomRef.current;roomRef.current=null;
    setConnected(false);setMic(false);setPeerReady(false);setStarted(null);setStatus('통화 종료');
    if(room){try{await room.disconnect()}catch{}}
    try{await AudioSession.stopAudioSession()}catch{}
    const stop=stopRef.current;stopRef.current=undefined;if(stop)try{await stop()}catch{setError('PC에서 음성 종료 상태를 확인하세요.')}
  }
  useEffect(()=>{
    const sub=AppState.addEventListener('change',state=>{if(state==='background')void disconnect()});
    return()=>{sub.remove();void disconnect()};
  },[]);
  async function connect(){
    if(inFlight.current||roomRef.current)return;
    inFlight.current=true;setBusy(true);setError('');
    const id=++generation.current;
    try{
      const pair=parse(raw.trim());
      setStatus('음성 연결 중');
      await AudioSession.startAudioSession();
      if(id!==generation.current){await AudioSession.stopAudioSession();return}
      const room=new Room();
      const syncPeers=()=>{if(roomRef.current===room){const ready=[...room.remoteParticipants.values()].some(p=>p.identity.startsWith('dominion-bot-'));setPeerReady(ready);if(ready)setStarted(v=>v??Date.now())}};
      room.on(RoomEvent.ParticipantConnected,syncPeers);
      room.on(RoomEvent.ParticipantDisconnected,syncPeers);
      roomRef.current=room;
      room.on(RoomEvent.ConnectionStateChanged,state=>{
        if(roomRef.current!==room)return;
        setConnected(state===ConnectionState.Connected);
        setStatus(state===ConnectionState.Connected?'연결됨 · 마이크를 켜세요':state===ConnectionState.Reconnecting?'재연결 중':'연결 상태: '+state);
      });
      room.on(RoomEvent.Disconnected,()=>{if(roomRef.current===room)void disconnect()});
      await room.connect(pair.url,pair.token);
      if(id!==generation.current||roomRef.current!==room){await room.disconnect();return}
      if(room.name!==pair.room)throw new Error('연결 방이 일치하지 않습니다.');
      syncPeers();setConnected(true);setStatus('연결됨');setRaw('');
      if(session){if(Platform.OS==='android'&&await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO)!==PermissionsAndroid.RESULTS.GRANTED)throw Error('마이크 권한 필요');if(id!==generation.current||roomRef.current!==room)return;await room.localParticipant.setMicrophoneEnabled(true);if(roomRef.current===room)setMic(true)}
    }catch(e){if(id===generation.current){await disconnect();setError('연결 실패. 접속 정보, 네트워크, PC 음성 비서 상태를 확인하세요.');}}
    finally{inFlight.current=false;setBusy(false)}
  }
  async function toggle(){
    const room=roomRef.current;if(!room||inFlight.current)return;
    inFlight.current=true;setBusy(true);
    try{if(!mic&&Platform.OS==='android'&&await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO)!==PermissionsAndroid.RESULTS.GRANTED){setError('마이크 권한을 허용해야 음성 대화가 가능합니다.');return;}await room.localParticipant.setMicrophoneEnabled(!mic);if(roomRef.current===room){setMic(!mic);setStatus(mic?'마이크 꺼짐':'음성 대화 중');}}catch{setError('마이크 권한 또는 연결을 확인하세요.')}
    finally{inFlight.current=false;setBusy(false)}
  }
  useEffect(()=>{if(session)void connect()},[]);
  async function routeAudio(){try{const outputs=await AudioSession.getAudioOutputs();const target=speaker?'earpiece':'speaker';if(outputs.includes(target)){await AudioSession.selectAudioOutput(target);setSpeaker(!speaker)}else if(Platform.OS==='ios'){await AudioSession.showAudioRoutePicker()}else setError('사용 가능한 오디오 출력 장치를 확인하세요.')}catch{setError('오디오 출력을 변경할 수 없습니다.')}}
  if(session)return <SafeAreaView style={[styles.page,{backgroundColor:'#14261D'}]}><View style={styles.callPage}><Text style={{color:'#B8D2C0',fontSize:14}}>DOMINION 인터넷 전화</Text><View style={{flex:1,justifyContent:'center',alignItems:'center',gap:18}}><View style={styles.callAvatar}><Text style={{fontSize:52,color:'#B5E4BE'}}>✦</Text></View><Text style={{fontSize:30,fontWeight:'700',color:'#fff'}}>나의 AI 비서</Text><Text accessibilityLiveRegion="polite" style={{color:'#B8D2C0',fontSize:17}}>{peerReady&&connected?String(Math.floor(elapsed/60)).padStart(2,'0')+':'+String(elapsed%60).padStart(2,'0'):connected?'AI 연결 대기 중…':status}</Text>{!!error&&<Text style={{color:'#FFB9AF',textAlign:'center'}}>{error}</Text>}</View><View style={{flexDirection:'row',gap:28,justifyContent:'center'}}><Pressable accessibilityRole="button" disabled={!connected||busy} onPress={()=>void toggle()} style={styles.callControl}><Text style={{fontSize:25,color:'#fff'}}>{mic?'🎙':'⊘'}</Text><Text style={{color:'#fff'}}>{mic?'음소거':'음소거 해제'}</Text></Pressable><Pressable accessibilityRole="button" disabled={!connected} onPress={()=>void routeAudio()} style={styles.callControl}><Text style={{fontSize:25,color:'#fff'}}>◖))</Text><Text style={{color:'#fff'}}>{speaker?'스피커 켜짐':'오디오 출력'}</Text></Pressable></View><Pressable accessibilityLabel="통화 종료" accessibilityRole="button" onPress={()=>void disconnect().finally(onEnd)} style={styles.hangup}><Text style={{color:'#fff',fontWeight:'700',fontSize:18}}>통화 종료</Text></Pressable></View></SafeAreaView>;
  return <SafeAreaView style={[styles.page,{backgroundColor:dark?'#111827':'#eef2f8'}]}><KeyboardAvoidingView style={{flex:1}} behavior={Platform.OS==='ios'?'padding':undefined}><ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.body}>
    <Text style={[styles.title,{color:ink}]}>DOMINION Voice</Text><Text style={[styles.sub,{color:ink}]}>PC의 도미니온과 연결하는 개인 음성 비서 · 0.2.1</Text>
    <View style={[styles.card,{backgroundColor:dark?'#202b3c':'white'}]}>
    <Text accessibilityLiveRegion="polite" style={[styles.status,{color:ink}]}>{status}</Text>
    {!connected&&<><Text style={{color:ink}}>{session?'음성 연결 준비 완료. 연결을 누르세요.':'PC 음성 비서에서 만든 임시 연결 정보를 붙여넣으세요.'}</Text>{!session&&<TextInput accessibilityLabel="임시 연결 정보" value={raw} onChangeText={setRaw} autoCapitalize="none" autoCorrect={false} secureTextEntry editable={!busy} placeholder="연결 정보" placeholderTextColor="#8792a2" style={[styles.input,{color:ink}]}/>}<Pressable accessibilityRole="button" disabled={!raw.trim()||busy} onPress={()=>void connect()} style={[styles.button,{opacity:!raw.trim()||busy ? 0.5 : 1}]}><Text style={styles.buttonText}>연결</Text></Pressable></>}
    {connected&&<Text accessibilityLiveRegion="polite" style={{color:ink}}>{peerReady?'PC 음성 비서가 연결되었습니다.':'PC 음성 비서 입장을 기다리는 중입니다.'}</Text>}
    {connected&&<Pressable accessibilityRole="button" disabled={busy} onPress={()=>void toggle()} style={[styles.button,{backgroundColor:mic?'#bd4141':'#285bca'}]}><Text style={styles.buttonText}>{mic?'마이크 끄기':'마이크 켜기'}</Text></Pressable>}
    {(connected||busy)&&<Pressable accessibilityRole="button" onPress={()=>void disconnect()} style={styles.secondary}><Text style={{color:ink}}>연결 종료</Text></Pressable>}
    {!!error&&<Text accessibilityRole="alert" style={{color:'#e46b60'}}>{error}</Text>}
    </View><Pressable onPress={()=>void disconnect().finally(onEnd)} style={styles.secondary}><Text style={{color:ink}}>비서 메신저로 돌아가기</Text></Pressable><Text style={[styles.sub,{color:ink}]}>연결 정보는 앱에 저장하지 않습니다. 앱을 백그라운드로 이동하면 음성 연결을 종료합니다.</Text>
    </ScrollView></KeyboardAvoidingView></SafeAreaView>
}
const styles=StyleSheet.create({callPage:{flex:1,padding:28,alignItems:'center',gap:24},callAvatar:{width:112,height:112,borderRadius:56,backgroundColor:'#304D3A',alignItems:'center',justifyContent:'center'},callControl:{backgroundColor:'#304D3A',borderRadius:25,padding:17,minWidth:100,alignItems:'center',gap:8},hangup:{backgroundColor:'#C84141',borderRadius:32,paddingHorizontal:36,paddingVertical:18,marginBottom:22},page:{flex:1},body:{padding:24,gap:16},title:{fontSize:30,fontWeight:'700'},sub:{fontSize:14,lineHeight:22},card:{borderRadius:20,padding:20,gap:14},status:{fontSize:20,fontWeight:'600'},input:{borderWidth:1,borderColor:'#8994a6',borderRadius:12,padding:14},button:{padding:16,backgroundColor:'#285bca',borderRadius:12,alignItems:'center'},buttonText:{color:'#fff',fontWeight:'700',fontSize:16},secondary:{padding:14,alignItems:'center'}});
