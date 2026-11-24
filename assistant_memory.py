"""Deterministic bounded retrieval; owner-editable memory, no hidden profiling."""
import json
import re
import unicodedata

DEFAULT_PROFILE={'name':'나의 AI 비서','style':'자연스럽고 이해하기 쉽게','language':'ko','notes':[]}

def validate_profile(data):
    if not isinstance(data,dict): raise ValueError('Invalid profile')
    name,style,language,notes=(data.get(k) for k in ('name','style','language','notes'))
    if not isinstance(name,str) or not name.strip() or len(name)>40 or not isinstance(style,str) or len(style.encode())>800 or language not in ('ko','en') or not isinstance(notes,list) or len(notes)>20:
        raise ValueError('이름·말투·기억 설정을 확인하세요.')
    if any(not isinstance(n,str) or not n.strip() or len(n.encode())>400 for n in notes):
        raise ValueError('기억 메모는 각각 400바이트 이하여야 합니다.')
    return {'name':name.strip(),'style':style.strip(),'language':language,'notes':[n.strip() for n in notes]}

def terms(text):
    words=re.findall(r'[\w]+',unicodedata.normalize('NFKC',text).casefold())
    result=set()
    for w in words:
        if len(w)>=2:
            result.add(w)
            if any('\uac00'<=c<='\ud7a3' for c in w):
                result.update(w[i:i+2] for i in range(len(w)-1))
    return result

def relevance(query,value):
    a,b=terms(query),terms(value)
    return len(a&b)/(max(1,len(a))**.5 * max(1,len(b))**.5)

def context(query,turns,profile,budget=5400):
    completed=[t for t in turns if t.get('status')=='completed']
    recent=completed[-4:]
    ranked=sorted(completed[:-4],key=lambda t:relevance(query,t.get('text','')+' '+t.get('answer','')[:1500]),reverse=True)
    selected=[t for t in ranked[:5] if relevance(query,t.get('text','')+' '+t.get('answer','')[:1500])>0]+recent
    notes=sorted(profile.get('notes',[]),key=lambda n:relevance(query,n),reverse=True)
    data={'profile':{k:profile[k] for k in ('name','style','language')},'ownerNotes':notes[:8],
          'history': [{'user':t['text'][:1000],'assistant':t.get('answer','')[:1000]} for t in selected]}
    def size(): return len(json.dumps(data,ensure_ascii=False).encode())
    while size()>budget and data['history']: data['history'].pop(0)
    while size()>budget and data['ownerNotes']: data['ownerNotes'].pop()
    return data
