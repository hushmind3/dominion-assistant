package com.dominion.assistant.voice

import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class VoicePairing(val url:String,val token:String,val room:String,val expiresAt:Long) {
 companion object {
  fun parse(x:JSONObject,now:Long=System.currentTimeMillis()):VoicePairing =
   validate(x.getString("url"),x.getString("token"),x.getString("room"),
    x.getLong("expiresAt"),now)
  fun validate(url:String,token:String,room:String,exp:Long,
    now:Long=System.currentTimeMillis()):VoicePairing {
   val u=URI(url)
   require(u.scheme=="wss"&&!u.host.isNullOrBlank()&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.port!=0&&u.host!="localhost"&&!u.host.endsWith(".local")&&u.host!="127.0.0.1"){"Invalid WebRTC URL"}
   require(token.length in 24..8192&&token.split('.').size==3&&token.all{it.isLetterOrDigit()||it in "._-"}){"Invalid LiveKit token"}
   require(Regex("^dominion-[a-f0-9]{32}$").matches(room)){"Invalid room"}
   require(exp*1000>now+5000&&exp*1000<now+600_000){"Pairing expired"}
   return VoicePairing(url,token,room,exp)
  }
 }
}
fun voiceId():String=UUID.randomUUID().toString()
