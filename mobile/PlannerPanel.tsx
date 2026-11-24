import React,{useEffect,useState} from 'react';
import {Alert,Pressable,ScrollView,StyleSheet,Text,TextInput,View,useColorScheme} from 'react-native';
import {cancelReminder,localDate,openCalendarForm,reminders,scheduleReminder} from './deviceActions';
type Reminder=Awaited<ReturnType<typeof reminders>>[number];
export default function PlannerPanel(){
 const dark=useColorScheme()==='dark',ink=dark?'#F3F6EF':'#17221A',muted=dark?'#A1B0A2':'#627167',card=dark?'#202720':'#FFFFFF',line=dark?'#364039':'#E0E6DE',accent=dark?'#A5D5A1':'#35683F';
 const [title,setTitle]=useState(''),[when,setWhen]=useState(''),[items,setItems]=useState<Reminder[]>([]),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('');
 const load=async()=>{try{setItems(await reminders())}catch(e){setError((e as Error).message)}};
 useEffect(()=>{void load()},[]);
 const action=async(calendar:boolean)=>{if(busy)return;setBusy(true);setError('');setMessage('');try{
  const text=title.trim();if(!text||text.length>150)throw Error('일정 제목을 입력하세요.');const date=localDate(when);
  if(calendar){await openCalendarForm(text,date);setMessage('시스템 캘린더에서 저장 여부를 확인하세요.')}
  else{await scheduleReminder(text,date);setMessage('기기에 알림을 예약했습니다.');setTitle('');setWhen('');await load()}
 }catch(e){setError((e as Error).message)}finally{setBusy(false)}};
 const remove=(id:string)=>Alert.alert('알림 취소','이 알림을 취소하시겠습니까?',[{text:'닫기',style:'cancel'},{text:'취소하기',onPress:()=>void(async()=>{try{await cancelReminder(id);await load()}catch(e){setError((e as Error).message)}})()}]);
 const button=(label:string,fn:()=>void,primary=false)=><Pressable accessibilityRole="button" disabled={busy} onPress={fn} style={[s.button,{borderColor:line,backgroundColor:primary?accent:card,opacity:busy?.5:1}]}><Text style={{color:primary?(dark?'#152418':'white'):ink,fontWeight:'600'}}>{label}</Text></Pressable>;
 return <ScrollView contentContainerStyle={s.body} keyboardShouldPersistTaps="handled"><Text style={[s.title,{color:ink}]}>일정과 알림</Text><Text style={{color:muted}}>휴대폰에 직접 일정을 추가하거나 알림을 예약할 수 있습니다.</Text><View style={[s.card,{backgroundColor:card}]}><TextInput value={title} onChangeText={setTitle} maxLength={150} placeholder="예: 내일 병원 예약" placeholderTextColor={muted} style={[s.input,{color:ink,borderColor:line}]}/><TextInput value={when} onChangeText={setWhen} placeholder="YYYY-MM-DD HH:mm" placeholderTextColor={muted} style={[s.input,{color:ink,borderColor:line}]}/><Text style={{color:muted,fontSize:12}}>휴대폰 현지 시간 기준</Text>{button('알림 예약',()=>void action(false),true)}{button('시스템 캘린더에 추가',()=>void action(true))}</View>{!!message&&<Text style={{color:accent}}>{message}</Text>}{!!error&&<Text accessibilityRole="alert" style={{color:dark?'#FFB4A8':'#A43B30'}}>{error}</Text>}<Text style={[s.title,{color:ink}]}>예약된 기기 알림</Text>{items.length===0&&<Text style={{color:muted}}>예약된 알림이 없습니다.</Text>}{items.map(n=><View key={n.identifier} style={[s.card,{backgroundColor:card}]}><Text style={{color:ink,fontWeight:'600'}}>{n.content.body}</Text><Text style={{color:muted}}>{typeof n.content.data?.scheduledAt==='string'?new Date(n.content.data.scheduledAt).toLocaleString('ko-KR'):''}</Text>{button('예약 취소',()=>remove(n.identifier))}</View>)}</ScrollView>
}
const s=StyleSheet.create({body:{padding:16,gap:14},title:{fontSize:20,fontWeight:'700'},card:{padding:16,borderRadius:18,gap:12},input:{padding:12,borderWidth:1,borderRadius:12,fontSize:16},button:{padding:13,borderRadius:13,borderWidth:1,alignItems:'center'}});
