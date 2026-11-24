export const AVATARS=[
 {id:'star',glyph:'✦',color:'#286744',label:'별빛'},
 {id:'moon',glyph:'☾',color:'#5965A1',label:'달'},
 {id:'sun',glyph:'☀',color:'#A76A29',label:'햇살'},
 {id:'leaf',glyph:'❋',color:'#427D72',label:'숲'},
 {id:'heart',glyph:'♡',color:'#A65576',label:'하트'},
 {id:'orbit',glyph:'◎',color:'#526C89',label:'궤도'}
] as const;
export function avatarFor(id?:string){return AVATARS.find(x=>x.id===id)||AVATARS[0]}
export function assistantName(name?:string){return name?.trim().slice(0,40)||'나의 AI 비서'}
