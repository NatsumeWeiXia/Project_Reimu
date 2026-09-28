import {Projection,appendText,message,snapshot,safeUrl,type Snapshot,type EventEnvelope} from '../src/core/protocol.js';
import {SseParser,consumeSse,type SseFrame} from '../src/core/sse.js';
let passed=0;
function assert(ok:unknown,label='Assertion failed'):asserts ok{if(!ok)throw new Error(label);}
function throws(f:()=>unknown){try{f();}catch{return;}throw new Error('Expected rejection');}
function test(name:string,f:()=>void){f();console.log(`PASS ${++passed} ${name}`);}
const base:Snapshot={protocolVersion:1,conversation:{id:'c1',title:'会话',entityVersion:1,entry:{kind:'agent',ref:'a@1'},pinned:false,archived:false,createdAt:'2026-09-24T00:00:00Z',updatedAt:'2026-09-24T00:00:00Z'},streamId:'s1',cursor:'p0',workspaceId:'w',permissionVersion:'1',hasMore:false,messages:[{id:'m1',conversationId:'c1',ordinal:'1',entityVersion:1,role:'assistant',status:'streaming',createdAt:'2026-09-24T00:00:00Z',parts:[{id:'t1',type:'text',text:'',attemptId:'a1'}]}],activity:{state:'generating',sendMode:'disabled',canRequestCancellation:true,label:'生成中',runId:'r1'},tools:[],approvals:[],questions:[]};
function evt(type:string,data:Record<string,unknown>,cursor='p1',previousCursor='p0'):EventEnvelope{return{protocolVersion:1,eventId:'e'+cursor,streamId:'s1',conversationId:'c1',workspaceId:'w',permissionVersion:'1',cursor,previousCursor,occurredAt:'2026-09-24T00:00:00Z',type,data};}
function delta(text:string,offset=0,cursor='p1',previous='p0'){return evt('message.text.delta',{messageId:'m1',partId:'t1',attemptId:'a1',offsetUtf16:offset,text},cursor,previous);}
function value(p:Projection){const part=p.value.messages[0].parts[0];assert(part.type==='text');return part.text;}
test('Snapshot is a defensive copy',()=>{const s=snapshot(base);s.messages[0].parts=[];assert(base.messages[0].parts.length===1);});
test('Invalid protocol is rejected',()=>throws(()=>snapshot({...base,protocolVersion:2})));
test('Duplicate message IDs rejected',()=>throws(()=>snapshot({...base,messages:[base.messages[0],base.messages[0]]})));
test('Cross-conversation message rejected',()=>throws(()=>message({...base.messages[0],conversationId:'other'},'c1')));
test('Large ordinal stays a decimal string',()=>assert(message({...base.messages[0],ordinal:'900719925474099399'},'c1').ordinal==='900719925474099399'));
test('Emoji uses UTF-16 offsets',()=>{const p=new Projection(base);p.apply(delta('中😀'));p.apply(delta('文',3,'p2','p1'));assert(value(p)==='中😀文');});
test('Repeated event is not applied twice',()=>{const p=new Projection(base);const e=delta('你好');assert(p.apply(e));assert(!p.apply(e));assert(value(p)==='你好');});
test('Same cursor with changed payload is rejected',()=>{const p=new Projection(base);p.apply(delta('一'));throws(()=>p.apply(delta('二')));assert(value(p)==='一');});
test('Duplicate text under a new cursor is idempotent',()=>{const p=new Projection(base);p.apply(delta('你好'));p.apply(delta('你好',0,'p2','p1'));assert(value(p)==='你好');});
test('Cursor gap rejected without advancing state',()=>{const p=new Projection(base);throws(()=>p.apply(delta('hi',0,'p2','missing')));assert(p.value.cursor==='p0');});
test('Future text offset rejected',()=>throws(()=>appendText('a',2,'b')));
test('Conflicting overlapping text rejected',()=>throws(()=>appendText('abc',1,'z')));
test('String offset rejected at event boundary',()=>{const p=new Projection(base),e=delta('a');e.data.offsetUtf16='0';throws(()=>p.apply(e));});
test('Old model attempt cannot append text',()=>{const p=new Projection(base),e=delta('a');e.data.attemptId='old';throws(()=>p.apply(e));});
test('Different stream rejected',()=>{const p=new Projection(base);throws(()=>p.apply({...delta('a'),streamId:'other'}));});
test('Different workspace rejected',()=>{const p=new Projection(base);throws(()=>p.apply({...delta('a'),workspaceId:'other'}));});
test('Permission change forces new snapshot',()=>{const p=new Projection(base);throws(()=>p.apply({...delta('a'),permissionVersion:'2'}));});
test('Cross-conversation stream cannot contaminate state',()=>{const p=new Projection(base);throws(()=>p.apply({...delta('a'),conversationId:'other'}));assert(value(p)==='');});
test('Complete message reconciles streamed text',()=>{const p=new Projection(base);p.apply(delta('draft'));p.apply(evt('message.upsert',{message:{...base.messages[0],entityVersion:3,status:'complete',parts:[{id:'t1',type:'text',text:'final',attemptId:'a1'}]}},'p2','p1'));assert(value(p)==='final');});
test('Old message revision does not overwrite newer state',()=>{const p=new Projection(base);p.apply(delta('new'));p.apply(evt('message.upsert',{message:base.messages[0]},'p2','p1'));assert(value(p)==='new');});
test('New artifact availability never rewrites old reference',()=>{const s=structuredClone(base);s.messages[0].parts.push({id:'ar',type:'artifact_ref',artifactId:'a1',revision:1,kind:'markdown',title:'报告'});const p=new Projection(s);p.apply(evt('artifact.available',{artifactId:'a1',revision:2}));const part=p.value.messages[0].parts[1];assert(part.type==='artifact_ref'&&part.revision===1);});
test('Unknown required event fails closed',()=>{const p=new Projection(base);throws(()=>p.apply(evt('unknown.required',{})));assert(p.value.cursor==='p0');});
test('Older history merges once and sorts big ordinals exactly',()=>{const p=new Projection(base);const m={...base.messages[0],id:'m2',ordinal:'9007199254740993'};p.prepend([m,m],false);assert(p.value.messages.length===2&&p.value.messages[1].id==='m2');});
test('Safe HTTPS URL permitted',()=>assert(safeUrl('https://example.com/doc')==='https://example.com/doc'));
test('Executable, credential and control URLs denied',()=>{for(const u of ['javascript:alert(1)','data:text/html,hello','https://u:p@example.com','https://example.com/\nsecret'])assert(safeUrl(u)===null);});
test('SSE frames independent of every character boundary',()=>{const frames:SseFrame[]=[];const p=new SseParser(f=>frames.push(f));for(const ch of 'id: p1\r\nevent: test\r\ndata: 一\r\ndata: 二\r\n\r\n')p.feed(ch);assert(frames.length===1&&frames[0].data==='一\n二'&&frames[0].id==='p1');});
test('SSE comments do not become messages',()=>{const frames:SseFrame[]=[];new SseParser(f=>frames.push(f)).feed(': heartbeat\n\ndata: ok\n\n');assert(frames.length===1&&frames[0].data==='ok');});
test('Truncated EOF is discarded',()=>{const frames:SseFrame[]=[];const p=new SseParser(f=>frames.push(f));p.feed('data: partial\n');p.end();assert(frames.length===0);});
test('SSE aggregated frame has bounded size',()=>{const p=new SseParser(()=>{});throws(()=>{for(let i=0;i<2300;i++)p.feed('data: '+'x'.repeat(1000)+'\n');});});
const frames:SseFrame[]=[];const bytes=new TextEncoder().encode('data: 中文😀\n\n');const stream=new ReadableStream<Uint8Array>({start(controller){for(const b of bytes)controller.enqueue(Uint8Array.of(b));controller.close();}});await consumeSse(stream,f=>frames.push(f),new AbortController().signal);test('Streaming UTF-8 decoder preserves split multibyte characters',()=>assert(frames[0].data==='中文😀'));
let cancelled=false;const live=new ReadableStream<Uint8Array>({start(c){c.enqueue(new TextEncoder().encode('data: x\n\n'));},cancel(){cancelled=true;}});try{await consumeSse(live,()=>{throw new Error('bad payload');},new AbortController().signal);}catch{}test('Failed stream parsing cancels network reader',()=>assert(cancelled));
console.log(`FRONTEND PROTOCOL PASSED ${passed}; pure TypeScript only, not a React build`);
// These run the real controller with a deliberately labelled HTTP test double.
// They validate client lifecycle and request IDs, not the React component library.
const {Client}=await import('../src/core/api.js');
const {ChatController}=await import('../src/core/controller.js');
const originalFetch=globalThis.fetch;
function json(value:unknown,status=200){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});}
function pause(ms=10){return new Promise<void>(r=>setTimeout(r,ms));}
let streamOpens=0,streamCancels=0;
globalThis.fetch=(async(input:RequestInfo|URL,options?:RequestInit)=>{const path=String(input);if(path.includes('/snapshot'))return json(base);if(path.includes('/stream?')){streamOpens++;return new Response(new ReadableStream<Uint8Array>({cancel(){streamCancels++;}}),{headers:{'Content-Type':'text/event-stream'}});}throw new Error(path);}) as typeof fetch;
const controller=new ChatController('c1',new Client());controller.start();controller.close();controller.start();await pause();test('StrictMode setup-cleanup-setup starts a live controller',()=>assert(controller.getSnapshot().connection==='connected'));test('Stale startup never opens a second stream',()=>assert(streamOpens===1));controller.close();await pause();test('Closing controller cancels its reader, not a business command',()=>assert(streamCancels===1));
let bodyIds:string[]=[],drop=true,receiptExists=false;
globalThis.fetch=(async(input:RequestInfo|URL,options?:RequestInit)=>{const path=String(input);if(path.includes('/snapshot'))return json(base);if(path.includes('/stream?'))return new Response(new ReadableStream(),{headers:{'Content-Type':'text/event-stream'}});if(path.endsWith('/messages')){const b=JSON.parse(String(options?.body));bodyIds.push(b.clientRequestId);if(drop)throw new TypeError('connection lost before commit');receiptExists=true;return json({accepted:true},202);}if(path.includes('/requests/by-client-id/'))return receiptExists?json({accepted:true}):json({code:'UNAVAILABLE',message:'Unknown outcome'},503);throw new Error(path);}) as typeof fetch;
const c2=new ChatController('c1',new Client());c2.start();await pause();try{await c2.send('一次请求',[]);}catch{}test('Unknown send result retains the original pending request',()=>assert(c2.getSnapshot().pending?.text==='一次请求'));let blocked=false;try{await c2.send('不应重复',[]);}catch{blocked=true;}test('New send blocked until pending request is resolved',()=>assert(blocked));drop=false;await c2.retryPending();test('Retry preserves the same idempotency key',()=>assert(bodyIds.length===2&&bodyIds[0]===bodyIds[1]&&!c2.getSnapshot().pending));c2.close();
const isolated=new Client();let resolveResponse!:(r:Response)=>void;globalThis.fetch=(()=>new Promise<Response>(r=>{resolveResponse=r;})) as typeof fetch;const pending=isolated.request('/api/v1/me');isolated.resetScope();resolveResponse(json({principalId:'old-user'}));let stale=false;try{await pending;}catch(e){stale=e instanceof DOMException&&e.name==='AbortError';}test('Old identity response cannot populate a new session',()=>assert(stale));globalThis.fetch=originalFetch;
console.log(`FRONTEND ALL PASSED ${passed}; protocol/controller only, React build remains separate`);
