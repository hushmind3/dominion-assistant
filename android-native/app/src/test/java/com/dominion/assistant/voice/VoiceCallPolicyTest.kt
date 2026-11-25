package com.dominion.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCallPolicyTest {
 private val now=1_800_000_000_000L
 private val token="aaa.bbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccc"
 private val room="dominion-"+"a".repeat(32)
 @Test fun acceptsMatchingShortLivedPairing(){
  val p=VoicePairing.validate("wss://rtc.example.com",token,room,now/1000+120,now)
  assertEquals("wss://rtc.example.com",p.url)
  assertEquals(room,p.room)
 }
 @Test fun rejectsInsecureAndLocalConnection(){
  for(url in listOf("http://rtc.example.com","ws://rtc.example.com",
   "wss://localhost","wss://127.0.0.1","wss://user:pw@rtc.example.com",
   "wss://rtc.example.com:8765")){
   try { VoicePairing.validate(url,token,room,now/1000+120,now); fail(url) }
   catch(_:IllegalArgumentException){}
  }
 }
 @Test fun rejectsInvalidRoomAndToken(){
  for(value in listOf("dominion-abc","fake-"+"a".repeat(32))){
   try { VoicePairing.validate("wss://rtc.example.com",token,value,now/1000+120,now); fail(value) }
   catch(_:IllegalArgumentException){}
  }
  try { VoicePairing.validate("wss://rtc.example.com","secret",room,now/1000+120,now);fail() }
  catch(_:IllegalArgumentException){}
 }
 @Test fun rejectsExpiredOrUnreasonablePairings(){
  for(exp in listOf(now/1000-1,now/1000+3,now/1000+9999)){
   try { VoicePairing.validate("wss://rtc.example.com",token,room,exp,now);fail() }
   catch(_:IllegalArgumentException){}
  }
 }
 @Test fun voiceIdsAreFreshValidUuids(){
  val a=voiceId();val b=voiceId()
  assertNotEquals(a,b)
  assertEquals(a,java.util.UUID.fromString(a).toString())
 }
}
