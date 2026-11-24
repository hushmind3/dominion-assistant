import React,{useEffect,useRef,useState} from 'react';
import {AppState,FlatList,KeyboardAvoidingView,Platform,PermissionsAndroid,Pressable,SafeAreaView,StyleSheet,Text,TextInput,View,useColorScheme} from 'react-native';
import {ExpoSpeechRecognitionModule,useSpeechRecognitionEvent} from 'expo-speech-recognition';
import * as Speech from 'expo-speech';
import * as SecureStore from 'expo-secure-store';

type Link={url:string;token:string};
type Turn={clientId:string;requestId:string;text:string;kind:'chat'|'task';status:string;createdAt:number;answer?:string;error?:string;toolCount?:number};
const KEY='assistant-mobile-v1';
const active=(s:string)=>['queued','running','awaiting_approval'].includes(s);
const labels:Record<string,string>={queued:'대기 중',running:'실행 중',awaiting_approval:'PC 승인 필요',completed:'완료',failed:'실패',cancelled:'취소됨',exhausted:'한도 도달',interrupted:'중단됨',in_doubt:'확인 필요',needs_review:'검토 필요'};
function endpoint(raw:string){
 const m=raw.trim().match(/^(?:http:\/\/)?(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})\/?$/);
 if(!m)throw Error('PC의 개인 Wi-Fi 주소와 포트를 입력하세요.');
 const [a,b,c,d,p]=m.slice(1).map(Number);
 if([a,b,c,d].some(n=>n>255)||!(a===10||a===172&&b>=16&&b<=31||a===192&&b===168)||p<1||p>65535)throw Error('개인 Wi-Fi 주소만 허용합니다.');
 return 'http://'+[a,b,c,d].join('.')+':'+p;
}
class ApiError extends Error{constructor(public status:number,message:string){super(message)}}
async function api(link:Link,path:string,body?:object){
 const c=new AbortController();const timeout=setTimeout(()=>c.abort(),12000);
 try{
  const r=await fetch(link.url+'/mobile/v1/'+path,{method:body?'POST':'GET',headers:{...(link.token?{Authorization:'Bearer '+link.token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:c.signal,redirect:'error'});
  if(!r.ok){let msg='PC 연결 오류 ('+r.status+')';try{msg=(await r.json()).error||msg}catch{}throw new ApiError(r.status,msg)}
  return await r.json();
 }finally{clearTimeout(timeout)}
}
export default function AssistantChat({onAdvanced,onLegacy}:{onAdvanced:()=>void;onLegacy:()=>void}){
 const dark=useColorScheme()==='dark';
 const c={bg:dark?'#131713':'#F5F6F2',card:dark?'#202720':'#FFFFFF',ink:dark?'#F3F6EF':'#17221A',muted:dark?'#A1B0A2':'#627167',accent:dark?'#A5D5A1':'#35683F',mine:dark?'#2C4733':'#DCEED9',line:dark?'#364039':'#E0E6DE'};
 const [link,setLink]=useState<Link|null>(null),[loaded,setLoaded]=useState(false),[address,setAddress]=useState(''),[code,setCode]=useState(''),[turns,setTurns]=useState<Turn[]>([]),[draft,setDraft]=useState(''),[kind,setKind]=useState<'chat'|'task'>('chat'),[tab,setTab]=useState<'chat'|'tasks'|'settings'>('chat'),[error,setError]=useState(''),[busy,setBusy]=useState(false),[online,setOnline]=useState(false),[listening,setListening]=useState(false),[readAloud,setReadAloud]=useState(false);
 const list=useRef<FlatList<Turn>>(null), generation=useRef(0), current=useRef<Link|null>(null), sending=useRef(false), retry=useRef<{clientId:string;text:string;kind:'chat'|'task'}|null>(null), previous=useRef<Map<string,string>>(new Map()), recording=useRef(false), foreground=useRef(true), autoRead=useRef(false);
 autoRead.current=readAloud;
 const say=(text:string)=>{if(foreground.current&&text){void Speech.stop();Speech.speak(text.slice(0,12000),{language:'ko-KR'})}};
 const install=(l:Link|null)=>{generation.current++;current.current=l;previous.current.clear();setLink(l);setOnline(false)};
 useEffect(()=>{let alive=true;void(async()=>{try{const raw=await SecureStore.getItemAsync(KEY);if(alive&&raw){const l=JSON.parse(raw) as Link;install({url:endpoint(l.url),token:l.token});setAddress(l.url)}}catch{if(alive)setError('저장된 연결 정보를 다시 등록하세요.')}finally{if(alive)setLoaded(true)}})();const sub=AppState.addEventListener('change',s=>{foreground.current=s==='active';if(!foreground.current){recording.current=false;ExpoSpeechRecognitionModule.abort();void Speech.stop()}});return()=>{alive=false;generation.current++;sub.remove();recording.current=false;ExpoSpeechRecognitionModule.abort();void Speech.stop()}},[]);
 const refresh=async(l:Link,g:number)=>{
  try{
   const data=await api(l,'history');if(g!==generation.current)return;
   const next=data.turns as Turn[];if(!Array.isArray(next))throw Error('올바르지 않은 서버 응답');
   for(const t of next){const before=previous.current.get(t.requestId);if(autoRead.current&&before&&active(before)&&t.status==='completed'&&t.answer)say(t.answer)}
   previous.current=new Map(next.map(t=>[t.requestId,t.status]));setTurns(next);setOnline(true);setError('');
  }catch(e){if(g!==generation.current)return;setOnline(false);if(e instanceof ApiError&&e.status===401){install(null);void SecureStore.deleteItemAsync(KEY).catch(()=>{});setTurns([]);setError('PC에서 새 연결 코드를 만들어 주세요.')}else setError((e as Error).message)}
 };
 useEffect(()=>{if(!link)return;let stopped=false,checking=false;const g=generation.current;const poll=async()=>{if(stopped||checking||sending.current||!foreground.current)return;checking=true;try{await refresh(link,g)}finally{checking=false}};void poll();const t=setInterval(()=>void poll(),1800);return()=>{stopped=true;clearInterval(t)}},[link]);
 const pair=async()=>{if(sending.current)return;sending.current=true;setBusy(true);setError('');try{
  if(Platform.OS==='android'&&Number(Platform.Version)>=37){const permission='android.permission.ACCESS_LOCAL_NETWORK' as Parameters<typeof PermissionsAndroid.request>[0];if(await PermissionsAndroid.request(permission)!==PermissionsAndroid.RESULTS.GRANTED)throw Error('로컬 네트워크 권한을 허용하세요.')}
  const url=endpoint(address),r=await api({url,token:''},'pair',{code:code.trim()});
  if(typeof r.token!=='string'||!r.token)throw Error('인증 응답 오류');const l={url,token:r.token};await SecureStore.setItemAsync(KEY,JSON.stringify(l));install(l);setCode('');setTab('chat');
 }catch(e){setError((e as Error).message)}finally{sending.current=false;setBusy(false)}};
 const send=async()=>{const l=current.current,text=draft.trim();if(!l||!text||sending.current)return;
  const old=retry.current;const item=old&&old.text===text&&old.kind===kind?old:{clientId:Date.now().toString(36)+'-'+Math.random().toString(36).slice(2),text,kind};
  retry.current=item;sending.current=true;setBusy(true);setError('');const g=generation.current;
  try{const t=await api(l,'send',item) as Turn;if(g!==generation.current)return;retry.current=null;setDraft(v=>v.trim()===text?'':v);previous.current.set(t.requestId,t.status);setTurns(v=>v.some(x=>x.requestId===t.requestId)?v.map(x=>x.requestId===t.requestId?t:x):[...v,t]);await refresh(l,g)}
  catch(e){if(g===generation.current)setError('전송 확인 실패 · 같은 내용으로 다시 보내면 같은 요청을 확인합니다. '+(e as Error).message)}
  finally{sending.current=false;setBusy(false)}
 };
 const cancel=async(t:Turn)=>{const l=current.current;if(!l)return;const g=generation.current;try{await api(l,'cancel',{requestId:t.requestId});await refresh(l,g)}catch(e){setError((e as Error).message)}};
 const disconnect=async()=>{const l=current.current;if(l){try{await api(l,'disconnect',{})}catch(e){setError('PC 연결 해제 확인 실패. PC에서 인증을 해제하세요.')}}install(null);setTurns([]);retry.current=null;await SecureStore.deleteItemAsync(KEY).catch(()=>{});void Speech.stop()};
 useSpeechRecognitionEvent('start',()=>setListening(true));
 useSpeechRecognitionEvent('end',()=>{recording.current=false;setListening(false)});
 useSpeechRecognitionEvent('error',e=>{recording.current=false;setListening(false);if(e.error!=='aborted')setError(e.message)});
 useSpeechRecognitionEvent('result',e=>{if(!recording.current||!foreground.current)return;const t=e.results[0]?.transcript;if(t)setDraft(t);if(e.isFinal)recording.current=false});
 const mic=async()=>{if(recording.current){ExpoSpeechRecognitionModule.stop();return}try{void Speech.stop();if(!(await ExpoSpeechRecognitionModule.requestPermissionsAsync()).granted)throw Error('마이크 권한을 허용하세요.');if(!ExpoSpeechRecognitionModule.isRecognitionAvailable())throw Error('기기 음성 인식을 사용할 수 없습니다.');recording.current=true;ExpoSpeechRecognitionModule.start({lang:'ko-KR',interimResults:true,continuous:false,maxAlternatives:1})}catch(e){recording.current=false;setError((e as Error).message)}};
 const btn=(label:string,action:()=>void,primary=false,disabled=false)=><Pressable accessibilityRole="button" disabled={disabled} onPress={action} style={[s.button,{backgroundColor:primary?c.accent:c.card,borderColor:c.line,opacity:disabled?.45:1}]}><Text style={{color:primary?(dark?'#152418':'#fff'):c.ink,fontWeight:'600'}}>{label}</Text></Pressable>;
 const shown=tab==='tasks'?turns.filter(t=>t.kind==='task'):turns;
 return <SafeAreaView style={[s.page,{backgroundColor:c.bg}]}><KeyboardAvoidingView style={s.page} behavior={Platform.OS==='ios'?'padding':undefined}>
 <View style={[s.header,{borderColor:c.line}]}><View style={[s.avatar,{backgroundColor:c.mine}]}><Text style={{color:c.accent,fontSize:23}}>✦</Text></View><View style={{flex:1}}><Text style={[s.title,{color:c.ink}]}>나의 AI 비서</Text><Text style={{color:online?c.accent:c.muted,fontSize:12}}>{online?'● 도미니온 연결됨':link?'● 연결 확인 중':'○ 연결 안 됨'}</Text></View>{btn('통화',onAdvanced)}</View>
 {!loaded?<View style={s.empty}><Text style={{color:c.muted}}>연결 정보 확인 중…</Text></View>:!link?<View style={[s.panel,{backgroundColor:c.card}]}><Text style={[s.title,{color:c.ink}]}>비서와 연결하기</Text><Text style={{color:c.muted,lineHeight:21}}>PC 어시스턴트에서 연결 코드를 만들고 개인 Wi-Fi 릴레이 주소를 입력하세요.</Text><TextInput value={address} onChangeText={setAddress} autoCapitalize="none" autoCorrect={false} placeholder="192.168.0.10:8765" placeholderTextColor={c.muted} style={[s.input,{color:c.ink,borderColor:c.line}]}/><TextInput value={code} onChangeText={setCode} secureTextEntry autoCapitalize="none" autoCorrect={false} placeholder="연결 코드" placeholderTextColor={c.muted} style={[s.input,{color:c.ink,borderColor:c.line}]}/>{btn(busy?'연결 중…':'연결하기',()=>void pair(),true,busy||!address.trim()||!code.trim())}{btn('기존 PC 연결 방식',onLegacy)}<Text style={{color:c.muted,fontSize:12}}>신뢰하는 Wi-Fi에서만 사용하세요. 인증은 PC 재시작 또는 24시간 후 만료됩니다.</Text></View>:tab==='settings'?<View style={[s.panel,{backgroundColor:c.card}]}><Text style={[s.title,{color:c.ink}]}>비서 설정</Text><Text style={{color:c.muted}}>{link.url}</Text>{btn(readAloud?'자동 읽기 켜짐':'자동 읽기 꺼짐',()=>{setReadAloud(!readAloud);if(readAloud)void Speech.stop()})}{btn('음성 통화 설정',onAdvanced)}{btn('연결 해제',()=>void disconnect())}</View>:<>
 <FlatList ref={list} data={shown} keyExtractor={t=>t.requestId} contentContainerStyle={s.timeline} keyboardShouldPersistTaps="handled" ListEmptyComponent={<View style={s.empty}><Text style={{color:c.accent,fontSize:46}}>✦</Text><Text style={[s.title,{color:c.ink}]}>{tab==='tasks'?'맡긴 일이 없습니다':'무엇을 도와드릴까요?'}</Text><Text style={{color:c.muted,textAlign:'center'}}>일상 대화는 대화 모드로, 실행할 일은 작업 모드로 보내세요.</Text></View>} renderItem={({item:t})=><View style={{gap:10}}><Text style={{textAlign:'center',color:c.muted,fontSize:11}}>{new Date(t.createdAt).toLocaleString('ko-KR')}</Text><View style={[s.bubble,s.mine,{backgroundColor:c.mine}]}><Text selectable style={[s.message,{color:c.ink}]}>{t.text}</Text></View><View style={[s.bubble,{backgroundColor:c.card,borderColor:c.line,borderWidth:1}]}><Text style={{color:c.accent,fontWeight:'600',fontSize:12}}>{t.kind==='task'?'작업 · ':''}{labels[t.status]||t.status}{t.toolCount?' · 도구 '+t.toolCount+'개':''}</Text>{!!t.answer&&<Text selectable style={[s.message,{color:c.ink}]}>{t.answer}</Text>}{!!t.error&&<Text selectable style={{color:c.muted}}>{t.error}</Text>}{t.status==='awaiting_approval'&&<Text style={{color:c.muted}}>PC 도미니온 작업 화면에서 승인하세요.</Text>}{t.status==='queued'&&btn('대기 취소',()=>void cancel(t))}{!!t.answer&&btn('다시 듣기',()=>say(t.answer!))}</View></View>}/><View style={{paddingHorizontal:12,paddingBottom:8,alignItems:'flex-end'}}>{btn('최근 메시지 ↓',()=>list.current?.scrollToEnd({animated:true}))}</View>
 {tab==='chat'&&<View style={[s.composer,{borderColor:c.line,backgroundColor:c.card}]}><View style={s.row}>{btn(kind==='chat'?'● 대화':'대화',()=>setKind('chat'),kind==='chat')}{btn(kind==='task'?'● 작업':'작업',()=>setKind('task'),kind==='task')}{btn(listening?'인식 중지':'음성 입력',()=>void mic())}</View><View style={s.row}><TextInput multiline value={draft} onChangeText={setDraft} maxLength={6000} placeholder="메시지를 입력하세요" placeholderTextColor={c.muted} style={[s.input,{flex:1,color:c.ink,borderColor:c.line,maxHeight:120}]} />{btn('전송',()=>void send(),true,busy||!draft.trim())}</View></View>}
 </>}
 {!!error&&<Text accessibilityRole="alert" style={{color:dark?'#FFB4A8':'#A43B30',paddingHorizontal:16,paddingVertical:8}}>{error}</Text>}
 {link&&<View style={[s.nav,{borderColor:c.line}]}>{btn('대화',()=>setTab('chat'),tab==='chat')}{btn('맡긴 일',()=>setTab('tasks'),tab==='tasks')}{btn('설정',()=>setTab('settings'),tab==='settings')}</View>}
 </KeyboardAvoidingView></SafeAreaView>
}
const s=StyleSheet.create({page:{flex:1},header:{padding:14,flexDirection:'row',gap:12,alignItems:'center',borderBottomWidth:1},avatar:{width:44,height:44,borderRadius:22,alignItems:'center',justifyContent:'center'},title:{fontWeight:'700',fontSize:20},panel:{margin:16,padding:18,borderRadius:20,gap:14},empty:{padding:30,alignItems:'center',gap:12},timeline:{padding:16,gap:20,flexGrow:1},bubble:{alignSelf:'flex-start',maxWidth:'88%',padding:14,borderRadius:18,gap:8},mine:{alignSelf:'flex-end'},message:{fontSize:16,lineHeight:24},composer:{padding:12,gap:8,borderTopWidth:1},row:{flexDirection:'row',alignItems:'center',gap:7},input:{borderWidth:1,borderRadius:15,padding:12,fontSize:16},button:{paddingHorizontal:12,paddingVertical:10,borderRadius:14,borderWidth:1,alignItems:'center'},nav:{padding:10,flexDirection:'row',justifyContent:'space-around',borderTopWidth:1}});
