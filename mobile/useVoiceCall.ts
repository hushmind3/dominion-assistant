import {useEffect,useRef,useState} from 'react';
import {AppState} from 'react-native';
import {ExpoSpeechRecognitionModule as Recognition,useSpeechRecognitionEvent} from 'expo-speech-recognition';
import * as Speech from 'expo-speech';
import {voiceOptions,type VoicePreference} from './VoicePicker';
type Phase='idle'|'listening'|'thinking'|'speaking'|'paused';
type Options={language:string;voice:VoicePreference;offlineDictation:boolean;onText:(text:string)=>Promise<string|undefined>;onDraft:(text:string)=>void;onError:(text:string)=>void};
export function useVoiceCall(options:Options){
 const latest=useRef(options);latest.current=options;
 const [active,setActive]=useState(false),[muted,setMuted]=useState(false),[phase,setPhase]=useState<Phase>('idle'),[elapsed,setElapsed]=useState(0),[dictating,setDictating]=useState(false),[partial,setPartial]=useState('');
 const running=useRef(false),mutedRef=useRef(false),phaseRef=useRef<Phase>('idle'),owner=useRef<'call'|'dictation'|null>(null),final=useRef(''),generation=useRef(0),timer=useRef<ReturnType<typeof setTimeout>|null>(null),speechGeneration=useRef(0),foreground=useRef(true),started=useRef(0),failures=useRef(0),starting=useRef(false);
 const state=(p:Phase)=>{phaseRef.current=p;setPhase(p)};
 const clear=()=>{if(timer.current)clearTimeout(timer.current);timer.current=null};
 const stop=()=>{generation.current++;speechGeneration.current++;running.current=false;starting.current=false;clear();final.current='';setPartial('');setActive(false);mutedRef.current=false;setMuted(false);state('idle');if(owner.current==='call'){try{Recognition.abort()}catch{owner.current=null}}void Speech.stop()};
 const schedule=(ms=250)=>{clear();if(!running.current||mutedRef.current||!foreground.current||phaseRef.current==='thinking'||phaseRef.current==='speaking')return;timer.current=setTimeout(()=>{timer.current=null;listen()},ms)};
 const listen=()=>{if(!running.current||mutedRef.current||!foreground.current||phaseRef.current==='thinking'||phaseRef.current==='speaking')return;if(owner.current){schedule(300);return}final.current='';setPartial('');owner.current='call';state('listening');try{Recognition.start({lang:latest.current.language,interimResults:true,continuous:false,maxAlternatives:1,requiresOnDeviceRecognition:true})}catch(e){owner.current=null;state('paused');latest.current.onError((e as Error).message)}};
 const submit=async(text:string)=>{
  if(!running.current||phaseRef.current==='thinking'||!text.trim())return false;
  const wasSpeaking=phaseRef.current==='speaking';if(wasSpeaking)speechGeneration.current++;
  const id=generation.current;clear();final.current='';setPartial('');state('thinking');
  if(owner.current==='call')try{Recognition.abort()}catch{owner.current=null}
  try{
   if(wasSpeaking)await Speech.stop();if(!running.current||generation.current!==id)return true;
   const answer=await latest.current.onText(text.trim());
   if(!running.current||generation.current!==id)return true;
   if(!answer){state('paused');schedule();return true}
   const sid=++speechGeneration.current;state('speaking');
   const done=()=>{if(!running.current||id!==generation.current||sid!==speechGeneration.current)return;state('paused');schedule()};
   const chunks=answer.match(/[^。.!?\n]+[。.!?\n]*|[。.!?\n]+/g)||[answer];
   const queue=chunks.flatMap(x=>{const parts:string[]=[];for(let i=0;i<x.length;i+=1000)parts.push(x.slice(i,i+1000));return parts}).filter(x=>x.trim());
   const next=()=>{if(!running.current||id!==generation.current||sid!==speechGeneration.current)return;const part=queue.shift();if(!part){done();return}try{Speech.speak(part,{...voiceOptions(latest.current.voice,latest.current.language),onDone:next,onStopped:done,onError:()=>{latest.current.onError('목소리를 재생하지 못했습니다.');done()}})}catch{done()}};
   next();return true;
  }catch(e){if(running.current&&generation.current===id){state('paused');latest.current.onError((e as Error).message);schedule()}return true}
 };
 const start=async()=>{if(running.current||starting.current||owner.current)return;starting.current=true;const g=++generation.current;try{if(!(await Recognition.requestPermissionsAsync()).granted)throw Error('마이크 권한을 허용하세요.');if(!Recognition.isRecognitionAvailable()||!Recognition.supportsOnDeviceRecognition())throw Error('기기 내 음성 인식이 지원되지 않습니다. 휴대폰 음성 인식 설정을 확인하세요.');await Speech.stop();if(g!==generation.current||!foreground.current)return;running.current=true;mutedRef.current=false;setMuted(false);setActive(true);started.current=Date.now();setElapsed(0);failures.current=0;state('paused');schedule(100)}catch(e){latest.current.onError((e as Error).message)}finally{starting.current=false}};
 const toggleMute=()=>{mutedRef.current=!mutedRef.current;setMuted(mutedRef.current);if(mutedRef.current){clear();final.current='';if(owner.current==='call')try{Recognition.abort()}catch{owner.current=null}if(phaseRef.current==='listening')state('paused')}else{failures.current=0;schedule()}};
 const interrupt=()=>{if(!running.current||phaseRef.current!=='speaking')return;speechGeneration.current++;void Speech.stop();state('paused');schedule()};
 const dictate=async()=>{if(running.current||starting.current)return;if(owner.current==='dictation'){Recognition.stop();return}if(owner.current)return;try{await Speech.stop();if(!(await Recognition.requestPermissionsAsync()).granted)throw Error('마이크 권한을 허용하세요.');if(!Recognition.isRecognitionAvailable())throw Error('음성 인식을 사용할 수 없습니다.');if(latest.current.offlineDictation&&!Recognition.supportsOnDeviceRecognition())throw Error('기기 내 음성 인식을 지원하지 않습니다.');if(running.current||!foreground.current)return;owner.current='dictation';Recognition.start({lang:latest.current.language,interimResults:true,continuous:false,maxAlternatives:1,requiresOnDeviceRecognition:latest.current.offlineDictation})}catch(e){owner.current=null;latest.current.onError((e as Error).message)}};
 useSpeechRecognitionEvent('start',()=>{if(owner.current==='dictation')setDictating(true)});
 useSpeechRecognitionEvent('result',e=>{const text=e.results[0]?.transcript||'';if(!foreground.current||!text)return;if(owner.current==='dictation')latest.current.onDraft(text);else if(owner.current==='call'&&running.current&&!mutedRef.current&&phaseRef.current==='listening'){setPartial(text);if(e.isFinal)final.current=text}});
 useSpeechRecognitionEvent('error',e=>{if(owner.current==='dictation'){setDictating(false);if(e.error!=='aborted')latest.current.onError(e.message)}else if(owner.current==='call'&&running.current&&e.error!=='aborted'&&!['no-speech','speech-timeout'].includes(e.error)){failures.current=3;state('paused');latest.current.onError(e.message)}});
 useSpeechRecognitionEvent('end',()=>{const mode=owner.current;owner.current=null;if(mode==='dictation'){setDictating(false);return}if(mode!=='call'||!running.current)return;const text=final.current;final.current='';setPartial('');if(phaseRef.current==='thinking'||phaseRef.current==='speaking')return;if(text&&!mutedRef.current){failures.current=0;void submit(text)}else{state('paused');if(!mutedRef.current&&failures.current<3)schedule(450)}});
 useEffect(()=>{if(!active)return;const t=setInterval(()=>setElapsed(Math.floor((Date.now()-started.current)/1000)),1000);return()=>clearInterval(t)},[active]);
 useEffect(()=>{const sub=AppState.addEventListener('change',v=>{foreground.current=v==='active';if(!foreground.current){stop();if(owner.current==='dictation')try{Recognition.abort()}catch{owner.current=null}}});return()=>{sub.remove();running.current=false;generation.current++;speechGeneration.current++;clear();if(owner.current)try{Recognition.abort()}catch{}void Speech.stop()}},[]);
 return {active,muted,phase,elapsed,partial,dictating,start,stop,toggleMute,interrupt,dictate,submit};
}
