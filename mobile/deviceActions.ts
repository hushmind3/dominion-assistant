import {Platform} from 'react-native';
import * as Notifications from 'expo-notifications';
import * as Contacts from 'expo-contacts/legacy';
import * as Calendar from 'expo-calendar/legacy';

const CHANNEL='assistant-reminders';
Notifications.setNotificationHandler({handleNotification:async()=>({shouldShowBanner:true,shouldShowList:true,shouldPlaySound:true,shouldSetBadge:false})});
export async function notificationPermission(){
 if(Platform.OS==='android')await Notifications.setNotificationChannelAsync(CHANNEL,{name:'비서 알림',importance:Notifications.AndroidImportance.DEFAULT});
 let p=await Notifications.getPermissionsAsync();if(!p.granted)p=await Notifications.requestPermissionsAsync();
 if(!p.granted)throw Error('휴대폰 알림 권한을 허용하세요.');
}
export async function scheduleReminder(title:string,date:Date){
 await notificationPermission();
 return Notifications.scheduleNotificationAsync({content:{title:'비서 알림',body:title,data:{assistantReminder:true,scheduledAt:date.toISOString()}},trigger:{type:Notifications.SchedulableTriggerInputTypes.DATE,date,channelId:CHANNEL}});
}
export async function reminders(){return (await Notifications.getAllScheduledNotificationsAsync()).filter(n=>n.content.data?.assistantReminder===true)}
export async function cancelReminder(id:string){await Notifications.cancelScheduledNotificationAsync(id)}
export async function taskNotice(success:boolean){
 await Notifications.scheduleNotificationAsync({content:{title:'나의 AI 비서',body:success?'맡긴 일이 완료되었습니다.':'작업 상태를 확인하세요.'},trigger:null});
}
export async function pickPhoneNumbers(){
 const c=await Contacts.presentContactPickerAsync();
 if(!c)return [];
 return (c.phoneNumbers||[]).map(n=>({label:n.label||'전화',number:n.number||''})).filter(n=>n.number.trim());
}
export async function openCalendarForm(title:string,date:Date){
 const end=new Date(date.getTime()+3600000);
 return Calendar.createEventInCalendarAsync({title,startDate:date,endDate:end});
}
export function localDate(raw:string){
 const m=raw.trim().match(/^(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})$/);
 if(!m)throw Error('YYYY-MM-DD HH:mm 형식으로 입력하세요.');
 const [y,mo,d,h,min]=m.slice(1).map(Number),date=new Date(y,mo-1,d,h,min);
 if(date.getFullYear()!==y||date.getMonth()!==mo-1||date.getDate()!==d||date.getHours()!==h||date.getMinutes()!==min||date.getTime()<=Date.now())throw Error('미래의 올바른 날짜와 시간을 입력하세요.');
 return date;
}
