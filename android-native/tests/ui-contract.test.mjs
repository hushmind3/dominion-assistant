import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const oldUi=readFileSync(new URL('../../mobile/Messenger.tsx',import.meta.url),'utf8');
const native=readFileSync(new URL('../app/src/main/java/com/dominion/assistant/MainActivity.kt',import.meta.url),'utf8');
const voice=readFileSync(new URL('../app/src/main/java/com/dominion/assistant/VoiceConversationSheet.kt',import.meta.url),'utf8');

test('native primary palette preserves Expo dark UI tokens',()=>{
  for(const [legacy,token] of [['bg','Forest'],['card','Panel'],['accent','Accent'],['muted','Secondary']]){
    const color=oldUi.split(legacy+":dark?'#")[1]?.slice(0,6);
    assert.match(color||'',/^[0-9A-Fa-f]{6}$/,'missing Expo dark color: '+legacy);
    assert.ok(native.includes('private val '+token+' = Color(0xFF'+color.toUpperCase()+')'),
      'native '+token+' differs from Expo '+legacy);
  }
});

test('header displays editable assistant identity rather than product name',()=>{
  assert.ok(native.includes('Text(vm.profile.name,'));
  assert.ok(!native.includes('Text("도미니온",fontWeight'));
});

test('same chat is available while native voice is in progress',()=>{
  assert.ok(native.includes('VoiceConversationSheet('));
  assert.ok(native.includes('inVoiceSheet=true'));
  assert.ok(voice.includes('fraction < .25f'));
  assert.ok(voice.includes('fraction < .75f'));
});
