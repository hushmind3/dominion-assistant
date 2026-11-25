package com.dominion.assistant.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One authenticated RPC; no automatic replay of uncertain side effects. */
class CloudRelay {
 suspend fun voiceCommand(account:Account,deviceId:String,action:String,voiceId:String):JSONObject {
  require(UUID.fromString(deviceId).toString()==deviceId&&UUID.fromString(voiceId).toString()==voiceId)
  require(action=="start"||action=="stop")
  val id=UUID.randomUUID().toString()
  val payload=JSONObject().put("type","rpc").put("id",id).put("deviceId",deviceId)
   .put("route","voice/"+action).put("body",JSONObject().put("voiceId",voiceId)
    .apply{if(action=="start")put("confirmed",true)})
  val result=CompletableDeferred<JSONObject>()
  val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS)
   .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
  val request=Request.Builder().url(CloudApi.ORIGIN.replace("https://","wss://")+"/v1/assistant/socket")
   .header("Authorization","Bearer "+account.token).build()
  val socket=client.newWebSocket(request,object:WebSocketListener(){
   private var sent=false
   override fun onMessage(ws:WebSocket,text:String){
    try{
     val message=JSONObject(text)
     if(!sent&&message.optString("type")=="ready"){
      sent=true
      if(!ws.send(payload.toString()))result.completeExceptionally(IllegalStateException("Relay send failed"))
     }else if(message.optString("type")=="result"&&message.optString("id")==id){
      val status=message.getInt("status")
      if(status in 200..299)result.complete(message.optJSONObject("data")?:JSONObject())
      else result.completeExceptionally(CloudError(status,message.optJSONObject("data")?.optString("error","PC request failed")?:"PC request failed"))
     }
    }catch(_:Exception){}
   }
   override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){result.completeExceptionally(t)}
   override fun onClosed(ws:WebSocket,code:Int,reason:String){result.completeExceptionally(IllegalStateException("PC relay closed"))}
  })
  return try{withTimeout(55_000){result.await()}}
  finally{socket.close(1000,"done");client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
 }
}
