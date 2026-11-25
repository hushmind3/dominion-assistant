import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const oldUi=readFileSync(new URL('../../mobile/Messenger.tsx',import.meta.url),'utf8');
const native=readFileSync(new URL('../app/src/main/java/com/dominion/assistant/MainActivity.kt',import.meta.url),'utf8');
const voice=readFileSync(new URL('../app/src/main/java/com/dominion/assistant/VoiceConversationSheet.kt',import.meta.url),'utf8');

test('native primary palette preserves Expo dark UI tokens',()=>{
  for(const [legacy,token] of [['bg','Forest'],['card','Panel'],['accent','Accent'],['muted','Secondary']]){
    const old=oldUi.match(new RegExp(legacy+":dark\\?'#([0-9a-fA-F]{6})'"));
    assert.ok(old,'missing Expo dark color: '+legacy);
    assert.ok(native.includes('private val '+token+' = Color(0xFF'+old[1].toUpperCase()+')'),
      'native '+token+' differs from Expo '+legacy);
  }
});

test('header displays editable assistant identity rather than product name',()=>{
  assert.match(native,/Text\\(vm\\.profile\\.name,/);
  assert.doesNotMatch(native,/Text\\("도미니온",fontWeight/);
});

test('same chat is available while native voice is in progress',()=>{
  assert.match(native,/VoiceConversationSheet\\(/);
  assert.match(native,/inVoiceSheet=true/);
  assert.match(voice,/fraction < \\.25f/);
  assert.match(voice,/fraction < \\.75f/);
});
