package com.dominion.assistant.data

import org.json.JSONObject

/** Pure data-only server-driven UI. Never interprets scripts, remote URLs or commands. */
sealed interface WorkSection {
 val id:String
 val title:String
 val description:String
 data class Actions(
  override val id:String,override val title:String,override val description:String,
  val operations:List<Operation>
 ):WorkSection
 data class Form(
  override val id:String,override val title:String,override val description:String,
  val urlLabel:String,val goalLabel:String,val submitLabel:String
 ):WorkSection
}
data class Operation(val operation:String,val label:String)

object WorkUiSchema {
 private val operations=setOf("gmail_recent","calendar_upcoming","drive_recent")
 private val ids=Regex("^[a-z][a-z0-9-]{1,50}$")
 private fun label(s:String,max:Int):String? =
  s.trim().takeIf{it.isNotEmpty()&&it.length<=max&&!it.any(Char::isISOControl)}
 fun parse(body:JSONObject):List<WorkSection>? {
  if(body.optInt("version",-1)!=1)return null
  val sections=body.optJSONArray("sections")?:return null
  if(sections.length()>6)return null
  return (0 until sections.length()).mapNotNull{index->
   val section=sections.optJSONObject(index)?:return@mapNotNull null
   val id=section.optString("id");if(!ids.matches(id))return@mapNotNull null
   val title=label(section.optString("title"),100)?:return@mapNotNull null
   val desc=label(section.optString("description"),300)?:return@mapNotNull null
   when(section.optString("type")){
    "actions"->{
     val actions=section.optJSONArray("actions")?:return@mapNotNull null
     if(actions.length()>5)return@mapNotNull null
     val list=(0 until actions.length()).mapNotNull{i->
      val v=actions.optJSONObject(i)?:return@mapNotNull null
      val operation=v.optString("operation")
      val actionLabel=label(v.optString("label"),90)?:return@mapNotNull null
      if(v.optString("kind")!="google" || operation !in operations ||
       v.optString("id")!=operation)return@mapNotNull null
      Operation(operation,actionLabel)
     }
     if(list.isEmpty())null else WorkSection.Actions(id,title,desc,list.distinctBy{it.operation})
    }
    "form"->{
     val fields=section.optJSONArray("fields")?:return@mapNotNull null
     if(fields.length()!=2)return@mapNotNull null
     val url=fields.optJSONObject(0)?:return@mapNotNull null
     val goal=fields.optJSONObject(1)?:return@mapNotNull null
     if(url.optString("id")!="url"||url.optString("kind")!="https_url"||
      goal.optString("id")!="goal"||goal.optString("kind")!="multiline"||
      goal.optInt("maxLength")!=4000)return@mapNotNull null
     val submit=section.optJSONObject("submit")?:return@mapNotNull null
     if(submit.optString("kind")!="browser")return@mapNotNull null
     val urlLabel=label(url.optString("label"),90)?:return@mapNotNull null
     val goalLabel=label(goal.optString("label"),90)?:return@mapNotNull null
     val submitLabel=label(submit.optString("label"),90)?:return@mapNotNull null
     WorkSection.Form(id,title,desc,urlLabel,goalLabel,submitLabel)
    }
    else->null
   }
  }.distinctBy{it.id}
 }
}
