package com.dominion.assistant.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkUiSchemaTest {
 @Test fun acceptsKnownButtonsAndForm() {
  val ui=JSONObject("""
   {"version":1,"sections":[
    {"id":"google-read","type":"actions","title":"조회","description":"안내",
     "actions":[{"id":"gmail_recent","kind":"google","operation":"gmail_recent","label":"메일"}]},
    {"id":"browser-work","type":"form","title":"웹 업무","description":"안내",
     "fields":[{"id":"url","kind":"https_url","label":"주소"},
               {"id":"goal","kind":"multiline","label":"목표","maxLength":4000}],
     "submit":{"kind":"browser","label":"확인"}}
   ]}
  """.trimIndent())
  val result=WorkUiSchema.parse(ui)!!
  assertEquals(2,result.size)
  assertEquals("gmail_recent",(result[0] as WorkSection.Actions).operations[0].operation)
  assertEquals("확인",(result[1] as WorkSection.Form).submitLabel)
 }
 @Test fun rejectsUnknownRemoteCommands() {
  val json=JSONObject("""
   {"version":1,"sections":[{"id":"attack","type":"actions","title":"도구",
    "description":"안내","actions":[{"id":"run_js","kind":"script",
    "operation":"eval","label":"실행"}]}]}
  """.trimIndent())
  assertTrue(WorkUiSchema.parse(json)!!.isEmpty())
 }
 @Test fun rejectsInvalidVersionAndTooManySections() {
  assertNull(WorkUiSchema.parse(JSONObject("""{"version":2,"sections":[]}""")))
  val array=org.json.JSONArray()
  repeat(7){array.put(JSONObject().put("id","panel-"+it))}
  assertNull(WorkUiSchema.parse(JSONObject().put("version",1).put("sections",array)))
 }
 @Test fun ignoresOversizedOrUnknownSections() {
  val doc=JSONObject().put("version",1).put("sections",
   org.json.JSONArray().put(JSONObject().put("id","remote-script")
    .put("type","script").put("title","제어").put("description","임의 실행")))
  assertTrue(WorkUiSchema.parse(doc)!!.isEmpty())
 }
}
