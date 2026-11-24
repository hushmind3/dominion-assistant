import {cloud,type CloudAccount} from './cloud';
export type ChatMessage={role:'system'|'user'|'assistant';content:string};
// Mobile never accepts a provider URL/key. Provider credentials and routing belong to DOMINION.
export async function complete(a:CloudAccount,model:string,messages:ChatMessage[]){
 return cloud('/v1/chat/completions',{model,messages,stream:false,max_tokens:2048},a.token,90000);
}
