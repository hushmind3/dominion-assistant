import * as Notifications from 'expo-notifications';
import * as SecureStore from 'expo-secure-store';
import {notificationPermission} from './deviceActions';
import {cloud,type CloudAccount} from './cloud';
const KEY='assistant-push-v1';
type Registration={accountId:string;token:string};
export async function pushRegistration(a:CloudAccount){
 const raw=await SecureStore.getItemAsync(KEY);if(!raw)return null;
 const r=JSON.parse(raw) as Registration;return r.accountId===a.accountId?r:null;
}
export async function enablePush(a:CloudAccount){
 await notificationPermission();
 const projectId=process.env.EXPO_PUBLIC_EAS_PROJECT_ID;
 if(!projectId)throw Error('Expo 프로젝트 ID 및 FCM/APNs 설정이 필요합니다.');
 const token=(await Notifications.getExpoPushTokenAsync({projectId})).data;
 await cloud('/v1/assistant/push/register',{token},a.token);
 await SecureStore.setItemAsync(KEY,JSON.stringify({accountId:a.accountId,token}));
}
export async function disablePush(a:CloudAccount){
 const r=await pushRegistration(a);if(r)await cloud('/v1/assistant/push/unregister',{token:r.token},a.token);
 await SecureStore.deleteItemAsync(KEY);
}
export function observePush(fn:()=>void){
 const a=Notifications.addNotificationReceivedListener(n=>{if(n.request.content.data?.assistantMessage)fn()});
 const b=Notifications.addNotificationResponseReceivedListener(n=>{if(n.notification.request.content.data?.assistantMessage)fn()});
 return()=>{a.remove();b.remove()}
}
