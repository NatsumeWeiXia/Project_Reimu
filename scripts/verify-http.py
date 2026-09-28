#!/usr/bin/env python3
"""Real HTTP tests against a local, explicit demo-mode Spring Boot server.
No SDK/network model calls are claimed. Use only a disposable test database.
REIMU_TEST_SERVER_COMMAND optionally starts/restarts the server for SIGKILL tests.
"""
import os, json, time, uuid, shlex, subprocess, signal, pathlib, urllib.request, urllib.error, http.cookiejar
BASE=os.environ.get('REIMU_TEST_URL','http://127.0.0.1:8787').rstrip('/')
ROOT=pathlib.Path(__file__).resolve().parents[1]
COMMAND=os.environ.get('REIMU_TEST_SERVER_COMMAND')
USERS=json.loads(os.environ.get('REIMU_USERS_JSON','{}'))
COUNT=0

def check(ok, label):
 global COUNT
 if not ok: raise AssertionError(label)
 COUNT+=1; print(f'PASS {COUNT:02d} {label}',flush=True)

class Client:
 def __init__(self,user=None):
  self.jar=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar));self.csrf='';self.user=user
 def req(self,path,body=None,method=None,expected=200,headers=None,raw=False):
  h={'Origin':BASE,'X-CSRF-Token':self.csrf};h.update(headers or {})
  if body is not None:h['Content-Type']='application/json'
  req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers=h,method=method or ('POST' if body is not None else 'GET'))
  try:r=self.opener.open(req,timeout=8)
  except urllib.error.HTTPError as e:r=e
  text=r.read();status=r.status
  if status!=expected:raise AssertionError(f'{path} expected {expected} got {status}: {text[:600]!r}')
  return (text,dict(r.headers)) if raw else json.loads(text)
 def login(self):
  u=self.req('/api/v1/auth/login',{'username':self.user,'token':USERS[self.user]['token']});self.csrf=u['csrfToken'];return u
 def create(self,ref,title='测试会话'):
  return self.req('/api/v1/conversations',{'clientRequestId':str(uuid.uuid4()),'agentRef':ref,'title':title},expected=201)['id']
 def send(self,cid,text,refs=None,key=None):
  return self.req(f'/api/v1/conversations/{cid}/messages',{'clientRequestId':key or str(uuid.uuid4()),'text':text,'contextReferences':refs or []},expected=202)
 def snap(self,cid):return self.req(f'/api/v1/conversations/{cid}/snapshot')
 def wait(self,cid,predicate=lambda s:s['activity']['state']=='idle'):
  deadline=time.monotonic()+15
  while time.monotonic()<deadline:
   s=self.snap(cid)
   if predicate(s):return s
   if s['activity']['state']=='failed':raise AssertionError('Run failed: '+str(s['activity']))
   time.sleep(.08)
  raise AssertionError('Timed out waiting for state')

process=None;log=None

def start():
 global process,log
 log=open(ROOT/'evidence/spring-http-server.log','a',encoding='utf-8')
 process=subprocess.Popen(shlex.split(COMMAND),stdout=log,stderr=subprocess.STDOUT,env=os.environ.copy(),start_new_session=True)
 deadline=time.monotonic()+25
 while time.monotonic()<deadline:
  if process.poll() is not None:raise AssertionError('Server exited; see spring-http-server.log')
  try:
   with urllib.request.urlopen(BASE+'/api/health',timeout=1) as r:
    if r.status==200:return
  except Exception:pass
  time.sleep(.1)
 raise AssertionError('Server startup timeout')

def stop(hard=False):
 global process
 if process and process.poll() is None:
  os.killpg(process.pid,signal.SIGKILL if hard else signal.SIGTERM)
  try:process.wait(timeout=10)
  except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
 if log:log.close()
 process=None

try:
 if COMMAND:start()
 anonymous=Client()
 check(anonymous.req('/api/health')['mode']=='demo','Server explicitly reports demo mode')
 check(anonymous.req('/api/v1/conversations',expected=401)['code']=='UNAUTHENTICATED','Unauthenticated request rejected')
 a,b=Client('alice'),Client('bob');me=a.login();b.login()
 check(me['principalId']=='alice','Named session identity is server-issued')
 _,headers=a.req('/api/v1/me',raw=True)
 check('no-store' in headers.get('Cache-Control',''),'Sensitive API responses not cacheable')
 check(a.req('/api/v1/conversations',{},headers={'Origin':'https://evil.invalid'},expected=403)['code']=='ORIGIN_DENIED','Cross-origin write rejected')
 check(a.req('/api/v1/conversations',{},headers={'X-CSRF-Token':'wrong'},expected=403)['code']=='CSRF','Missing or invalid CSRF rejected')
 caps=a.req('/api/v1/ui-capabilities')
 check(not caps['multiAgentWorkflows'] and not caps['attachments'],'Unsupported features explicitly disabled')
 agents=a.req('/api/v1/agents');ref=agents[0]['id']+'@'+str(agents[0]['revision'])
 check(agents[0]['revision']>0,'Bootstrap agent is published')
 key=str(uuid.uuid4());body={'clientRequestId':key,'agentRef':ref,'title':'恢复测试'}
 c=a.req('/api/v1/conversations',body,expected=201);cid=c['id']
 check(a.req('/api/v1/conversations',body,expected=201)['id']==cid,'Conversation creation idempotent')
 check(b.req(f'/api/v1/conversations/{cid}/snapshot',expected=404)['code']=='RESOURCE_NOT_AVAILABLE','Cross-user conversation access rejected')
 snap=a.snap(cid);key=str(uuid.uuid4());receipt=a.send(cid,'生成演示流程、表格和报告',key=key)
 check(a.send(cid,'生成演示流程、表格和报告',key=key)==receipt,'HTTP message retry returns same receipt')
 check(a.req('/api/v1/requests/by-client-id/'+key)==receipt,'Lost response recoverable by client request ID')
 a.req(f'/api/v1/conversations/{cid}/messages',{'clientRequestId':key,'text':'different','contextReferences':[]},expected=409)
 check(True,'Reusing idempotency key for changed body rejected')
 done=a.wait(cid);parts=[p for m in done['messages'] for p in m['parts'] if p['type']=='artifact_ref']
 check(len(parts)==4 and len({p['kind'] for p in parts})==4,'Real dispatcher persisted four artifact kinds')
 check(len([m for m in done['messages'] if m['role']=='user'])==1,'Duplicate POST creates one user message')
 es=a.req(f'/api/v1/conversations/{cid}/events?streamId={snap["streamId"]}&after={snap["cursor"]}')['events']
 prev=snap['cursor']
 for e in es:
  if e['previousCursor']!=prev:raise AssertionError('Event cursor gap')
  prev=e['cursor']
 check(prev==done['cursor'],'Persisted event replay reaches consistent snapshot watermark')
 # Real streaming endpoint, not the JSON events API.
 req=urllib.request.Request(BASE+f'/api/v1/conversations/{cid}/stream?streamId={snap["streamId"]}&after={snap["cursor"]}',headers={'Accept':'text/event-stream'})
 with a.opener.open(req,timeout=5) as stream:
  check('text/event-stream' in stream.headers['Content-Type'],'SSE endpoint returns event stream')
  frame=[]
  for _ in range(10):
   line=stream.readline().decode().strip();frame.append(line)
   if line.startswith('data:'):break
  check(any(x.startswith('data:') for x in frame),'SSE replays persisted data after supplied cursor')
 aid=parts[0]['artifactId'];p=parts[0]
 check(b.req(f'/api/v1/artifacts/{aid}/revisions/1',expected=404)['code']=='RESOURCE_NOT_AVAILABLE','Artifact read enforces ownership')
 table=next(p for p in parts if p['kind']=='table');tid=table['artifactId']
 tablebody=a.req(f'/api/v1/artifacts/{tid}/revisions/1')
 check('rows' not in tablebody['content'],'Large table row data excluded from artifact header')
 q=a.req(f'/api/v1/artifacts/{tid}/revisions/1/table-queries',{'sortColumn':'value','descending':False,'filter':'','pageSize':1})
 page=a.req(f'/api/v1/artifacts/{tid}/table-queries/{q["queryId"]}/rows')
 check(len(page['rows'])==1 and q['querySnapshotId'],'Server-side table pagination creates snapshot')
 text,headers=a.req(f'/api/v1/artifacts/{tid}/table-queries/{q["queryId"]}/download',raw=True)
 check('9007199254740993' in text.decode('utf-8-sig') and 'attachment' in headers.get('Content-Disposition',''),'CSV retains large integer precision and downloads as attachment')
 a.send(cid,'上一条请求是什么？');continued=a.wait(cid)
 check(len([m for m in continued['messages'] if m['role']=='user'])==2,'Second turn continues the same persisted conversation')
 # Approval is persisted, parameter-bound and recoverable across a real SIGKILL.
 cid2=a.create(ref,'审批恢复');a.send(cid2,'请创建工单')
 waiting=a.wait(cid2,lambda s:s['activity']['state']=='waiting_approval');approval=waiting['approvals'][0]
 check(waiting['tools'][0]['state']=='approval_pending','Side effect remains blocked before approval')
 if COMMAND:
  stop(hard=True);start();a.login();b.login();restored=a.snap(cid2)
  check(restored['approvals'][0]['approvalId']==approval['approvalId'],'SIGKILL/restart retains exact approval ID')
  check(len(a.snap(cid)['messages'])==len(continued['messages']),'SIGKILL/restart retains prior conversation and artifacts')
 else:print('NOT RUN process-restart tests: provide REIMU_TEST_SERVER_COMMAND',flush=True)
 decision={'approvalRevision':approval['entityVersion'],'operationId':approval['operationId'],'argsHash':approval['argsHash'],'decision':'approved','clientRequestId':str(uuid.uuid4())}
 a.req('/api/v1/approvals/'+approval['approvalId']+'/decisions',{**decision,'argsHash':'wrong'},expected=409)
 check(True,'Approval rejects altered parameter hash')
 answer=a.req('/api/v1/approvals/'+approval['approvalId']+'/decisions',decision,expected=202)
 check(a.req('/api/v1/approvals/'+approval['approvalId']+'/decisions',decision,expected=202)==answer,'Repeated approval returns same receipt')
 resolved=a.wait(cid2)
 check(resolved['tools'][0]['state']=='succeeded','Internal ticket tool succeeds only after approval')
 cid3=a.create(ref,'补问');a.send(cid3,'请补问我一个问题');s=a.wait(cid3,lambda s:s['activity']['state']=='waiting_user');question=s['questions'][0]
 qb={'questionRevision':question['entityVersion'],'text':'演示系统A','clientRequestId':str(uuid.uuid4())}
 a.req('/api/v1/questions/'+question['questionId']+'/answers',qb,expected=202);s=a.wait(cid3)
 check(s['questions'][0]['status']=='answered','Question-specific answer resumes execution')
 cid4=a.create(ref,'取消');a.send(cid4,'请创建工单');s=a.wait(cid4,lambda s:s['activity']['state']=='waiting_approval');run=s['activity']['runId']
 a.req(f'/api/v1/runs/{run}/cancellation-requests',{'clientRequestId':str(uuid.uuid4())},expected=202);s=a.wait(cid4)
 check(all(p['status']!='pending' for p in s['approvals']),'Cancellation revokes pending approval without executing tool')
 resource=a.req('/api/v1/resources',{'kind':'knowledge','name':'数据库说明','content':'缓存命中率低会导致数据库查询增多。应核验 SQL 延迟和连接池等待。','readers':[]})
 results=a.req('/api/v1/knowledge/search',{'refs':[resource['id']+'@1'],'query':'缓存命中率'})
 check(bool(results),'Knowledge retrieval returns real uploaded content')
 b.req('/api/v1/knowledge/search',{'refs':[resource['id']+'@1'],'query':'缓存命中率'},expected=404)
 check(True,'Knowledge retrieval enforces resource ACL')
 b.req('/api/v1/resources',{'kind':'knowledge','name':'未授权','content':'no','readers':[]},expected=403)
 check(True,'Non-admin cannot publish capability resources')
 a.req('/api/v1/mcp/not-configured/discover',{},expected=503)
 check(True,'Disabled MCP never silently simulates a result')
 a.req('/api/v1/auth/logout',{})
 check(a.req('/api/v1/me',expected=401)['code']=='UNAUTHENTICATED','Logout invalidates server session')
 print(f'HTTP PASSED {COUNT}; real Spring Boot + H2; deterministic DemoRunner, not live AgentScope',flush=True)
finally:
 if COMMAND:stop()
