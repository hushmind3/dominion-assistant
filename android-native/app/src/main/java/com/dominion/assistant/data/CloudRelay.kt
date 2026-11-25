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
  require(action=="start"||action=="stop")
  require(UUID.fromString(voiceId).toString()==voiceId)
  return request(account,deviceId,"voice/"+action,
    JSONObject().put("voiceId",voiceId).apply{if(action=="start")put("confirmed",true)})
 }

 /**
  * The PC authorizes each request. No retries: a connection failure may happen
  * after execution. Callers should refresh durable history before resubmitting.
  */
 suspend fun request(account:Account,deviceId:String,route:String,body:JSONObject?=null):JSONObject {
  require(UUID.fromString(deviceId).toString()==deviceId)
  require(route in setOf("history","send","cancel","phone","profile","attachment","voice/start","voice/stop"))
  require((route=="history"||route=="phone")==(body==null))
  if(route=="send") {
   val text=body!!.optString("text")
   require(body.optString("kind")=="task"&&text.isNotBlank()&&
    text.toByteArray(Charsets.UTF_8).size<=6000)
   val aid=body.optString("attachmentId","")
   require(aid.isEmpty()||Regex("^[a-f0-9]{64}$").matches(aid))
   require(UUID.fromString(body.getString("clientId")).toString()==body.getString("clientId"))
  }
  if(route=="cancel")require(UUID.fromString(body!!.getString("requestId")).toString()==body.getString("requestId"))
  if(route=="attachment"){
   val value=body!!
   require(value.optString("name").length in 1..160 &&
    value.optString("mime").length in 1..120 &&
    value.optString("base64").length in 1..750000 &&
    value.optString("focus").toByteArray(Charsets.UTF_8).size<=6000)
  }
  if(route=="profile"){
   require(body!!.toString().toByteArray(Charsets.UTF_8).size<=10000)
  }
  val id=UUID.randomUUID().toString()
  val payload=JSONObject().put("type","rpc").put("id",id).put("deviceId",deviceId)
   .put("route",route).put("body",body?:JSONObject.NULL)
  require(payload.toString().toByteArray(Charsets.UTF_8).size <= 800_000) {
   "Cloud 중계 메시지 크기 제한을 초과했습니다."
  }
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
