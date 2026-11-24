import React,{useEffect,useMemo,useRef,useState,type ReactNode} from 'react';
import {Animated,BackHandler,Easing,Keyboard,PanResponder,Pressable,StyleSheet,useWindowDimensions,View} from 'react-native';
type Props={open:boolean;onChange:(open:boolean)=>void;disabled:boolean;children:ReactNode;menu:ReactNode;background:string};
const clamp=(n:number)=>Math.max(0,Math.min(1,n));
// Drawer progress is independent from navigation and call state.
export default function SwipeDrawer({open,onChange,disabled,children,menu,background}:Props){
 const {width:screenWidth}=useWindowDimensions(),width=Math.min(320,screenWidth*.84);
 const progress=useRef(new Animated.Value(0)).current;
 const [visible,setVisible]=useState(false);
 const props=useRef({open,onChange,disabled,width});props.current={open,onChange,disabled,width};
 const position=useRef(0),origin=useRef(0),dragging=useRef(false),serial=useRef(0);
 const animate=(target:boolean)=>{
  const id=++serial.current;progress.stopAnimation();if(target)setVisible(true);
  Animated.timing(progress,{toValue:target?1:0,duration:220,easing:Easing.out(Easing.cubic),useNativeDriver:true}).start(({finished})=>{if(finished&&serial.current===id&&!target)setVisible(false)});
 };
 const settle=(target:boolean)=>{dragging.current=false;const next=target&&!props.current.disabled;if(next)Keyboard.dismiss();props.current.onChange(next);animate(next)};
 useEffect(()=>{const id=progress.addListener(({value})=>{position.current=value});return()=>{progress.removeListener(id);progress.stopAnimation()}},[progress]);
 useEffect(()=>{if(!dragging.current)animate(open&&!disabled);if(open&&!disabled)Keyboard.dismiss();if(disabled&&open)onChange(false)},[open,disabled]);
 useEffect(()=>{if(!open||disabled)return;const sub=BackHandler.addEventListener('hardwareBackPress',()=>{settle(false);return true});return()=>sub.remove()},[open,disabled]);
 const pan=useMemo(()=>PanResponder.create({
  onMoveShouldSetPanResponderCapture:(_,g)=>{
   const p=props.current;if(p.disabled||g.numberActiveTouches!==1||Math.abs(g.dx)<12||Math.abs(g.dx)<Math.abs(g.dy)*1.5)return false;
   return p.open?g.dx<0:g.x0<=56&&g.dx>0;
  },
  onPanResponderGrant:()=>{++serial.current;dragging.current=true;progress.stopAnimation();origin.current=position.current;setVisible(true);Keyboard.dismiss()},
  onPanResponderMove:(_,g)=>progress.setValue(clamp(origin.current+g.dx/Math.max(1,props.current.width))),
  onPanResponderRelease:(_,g)=>settle(clamp(origin.current+g.dx/Math.max(1,props.current.width)+g.vx*.12)>=.5),
  onPanResponderTerminate:()=>settle(props.current.open),
  onPanResponderTerminationRequest:()=>true,
 }),[]);
 const translateX=progress.interpolate({inputRange:[0,1],outputRange:[-width,0]});
 return <View style={s.root} {...pan.panHandlers}>
  <View style={s.root} pointerEvents={visible?'none':'auto'} accessibilityElementsHidden={open} importantForAccessibility={open?'no-hide-descendants':'auto'}>{children}</View>
  {visible&&!disabled&&<View style={StyleSheet.absoluteFill} pointerEvents="box-none">
   <Animated.View style={[StyleSheet.absoluteFill,{backgroundColor:'#000',opacity:progress.interpolate({inputRange:[0,1],outputRange:[0,.38]})}]}><Pressable style={s.root} accessibilityRole="button" accessibilityLabel="메뉴 닫기" onPress={()=>settle(false)}/></Animated.View>
   <Animated.View accessibilityViewIsModal={open} style={[s.drawer,{width,backgroundColor:background,transform:[{translateX}]}]}>{menu}</Animated.View>
  </View>}
 </View>
}
const s=StyleSheet.create({root:{flex:1},drawer:{position:'absolute',left:0,top:0,bottom:0,elevation:12,shadowColor:'#000',shadowOffset:{width:4,height:0},shadowOpacity:.2,shadowRadius:16}});
