import { useCallback, useEffect, useState, useRef } from 'react';
import { Panel, PanelGroup, PanelResizeHandle } from 'react-resizable-panels';
import { client } from './core/api';
import type { Conversation, Reference } from './core/protocol';
import { Chat } from './components/Chat';
import { Admin, type Agent } from './components/Admin';
import { ArtifactPanel, type ArtifactRef } from './renderers/ArtifactPanel';
interface User {
    principalId: string;
    workspaceId: string;
    tenantId: string;
    admin: boolean;
    csrfToken: string;
}
interface Capabilities {
    mode: 'demo' | 'live';
    multiAgentWorkflows: boolean;
    attachments: boolean;
}
function routeId() { const id = /^\/app\/chat\/([^/]+)$/.exec(location.pathname)?.[1] ?? ''; return id === 'new' ? '' : id; }
export function App() {
    const [user, setUser] = useState<User>(), [checking, setChecking] = useState(true), [error, setError] = useState(''), [username, setUsername] = useState(''), [token, setToken] = useState(''), [busy, setBusy] = useState(false), [cap, setCap] = useState<Capabilities>(), [conversations, setConversations] = useState<Conversation[]>([]), [nextCursor, setNextCursor] = useState<string>(), [agents, setAgents] = useState<Agent[]>([]), [agentRef, setAgentRef] = useState(''), [cid, setCid] = useState(routeId), [search, setSearch] = useState(''), [archived, setArchived] = useState(false), [refs, setRefs] = useState<Reference[]>([]), [tabs, setTabs] = useState<ArtifactRef[]>([]), [activeTab, setActiveTab] = useState(''), [adminPage, setAdminPage] = useState(''), [narrow, setNarrow] = useState(() => window.innerWidth < 1100), [nav, setNav] = useState(false);
    const listGeneration = useRef(0);
    const createKey = useRef<{
        agentRef: string;
        key: string;
        cid?: string;
    }>();
    const reset = useCallback(() => { listGeneration.current++; createKey.current = undefined; client.resetScope(); setAgentRef(''); setUser(undefined); setConversations([]); setAgents([]); setCid(''); setRefs([]); setTabs([]); setAdminPage(''); }, []);
    useEffect(() => { client.onUnauthorized = reset; client.request<User>('/api/v1/me').then(u => { client.csrf = u.csrfToken; setUser(u); }).catch(() => { }).finally(() => setChecking(false)); const resize = () => setNarrow(window.innerWidth < 1100); window.addEventListener('resize', resize); const pop = () => setCid(routeId()); window.addEventListener('popstate', pop); return () => { window.removeEventListener('resize', resize); window.removeEventListener('popstate', pop); }; }, [reset]);
    const loadList = useCallback(async (cursor?: string) => { const generation = ++listGeneration.current; const q = new URLSearchParams({ q: search, archived: String(archived), limit: '30' }); if (cursor)
        q.set('cursor', cursor); const result = await client.request<{
        items: Conversation[];
        nextCursor?: string;
    }>('/api/v1/conversations?' + q); if (generation !== listGeneration.current)
        return; setConversations(old => cursor ? [...old, ...result.items.filter(c => !old.some(o => o.id === c.id))] : result.items); setNextCursor(result.nextCursor); }, [search, archived]);
    useEffect(() => { if (!user)
        return; const a = new AbortController(); Promise.all([client.request<Agent[]>('/api/v1/agents', { signal: a.signal }), client.request<Capabilities>('/api/v1/ui-capabilities', { signal: a.signal })]).then(([as, c]) => { if (a.signal.aborted)
        return; setAgents(as); setCap(c); setAgentRef(old => old || (as.find(v => v.revision > 0) ? as.find(v => v.revision > 0)!.id + '@' + as.find(v => v.revision > 0)!.revision : '')); }).catch(e => { if (!a.signal.aborted)
        setError(e.message); }); return () => a.abort(); }, [user, adminPage]);
    useEffect(() => { if (!user)
        return; let cancelled = false; const timer = setTimeout(() => void loadList().catch(e => { if (!cancelled)
        setError(e.message); }), 200); return () => { cancelled = true; listGeneration.current++; clearTimeout(timer); }; }, [user, loadList]);
    useEffect(() => { setRefs([]); setTabs([]); setActiveTab(''); setNav(false); }, [cid]);
    function select(id: string) { setCid(id); history.pushState({}, '', id ? '/app/chat/' + id : '/app/chat/new'); }
    async function create() { if (!agentRef)
        return; setBusy(true); setError(''); try {
        if (!createKey.current || createKey.current.agentRef !== agentRef)
            createKey.current = { agentRef, key: crypto.randomUUID() };
        const c = createKey.current.cid ? { id: createKey.current.cid } : await client.post<Conversation>('/api/v1/conversations', { clientRequestId: createKey.current.key, agentRef, title: '新会话' });
        createKey.current.cid = c.id;
        select(c.id);
        createKey.current = undefined;
        await loadList();
    }
    catch (e) {
        setError((e as Error).message);
    }
    finally {
        setBusy(false);
    } }
    function openArtifact(r: ArtifactRef) { const key = r.artifactId + ':' + r.revision; setTabs(old => old.some(t => t.artifactId === r.artifactId && t.revision === r.revision) ? old : [...old.slice(-4), r]); setActiveTab(key); }
    function quote(r: Reference) { setRefs(old => [...old.filter(v => v.artifactId !== r.artifactId).slice(-4), r]); }
    const tab = tabs.find(t => t.artifactId + ':' + t.revision === activeTab);
    const artifact = tab ? <div className="artifact-host"><nav className="artifact-tabs" aria-label="产物标签">{tabs.map(t => <button key={t.artifactId + ':' + t.revision} className={t === tab ? 'active' : ''} onClick={() => setActiveTab(t.artifactId + ':' + t.revision)}>{t.title} · v{t.revision}</button>)}</nav><ArtifactPanel key={tab.artifactId + ':' + tab.revision} reference={tab} onQuote={quote} onClose={() => { setTabs(old => old.filter(t => t !== tab)); setActiveTab(''); }}/></div> : null;
    const sidebar = <aside className={'sidebar ' + (nav ? 'drawer' : '')}><div className="brand"><span className="brand-emblem">霊</span><div>REIMU<small>Agent Workspace</small></div>{narrow && <button onClick={() => setNav(false)} aria-label="关闭会话导航">×</button>}</div><button className="new-chat" onClick={() => select('')}>＋ 新建会话</button><input className="search" placeholder="搜索会话" aria-label="搜索会话" value={search} onChange={e => setSearch(e.target.value)}/><label className="checkbox-row muted"><input type="checkbox" checked={archived} onChange={e => setArchived(e.target.checked)}/>查看已归档</label><div className="conversation-list">{conversations.map(c => <div key={c.id} className={'conversation-row ' + (cid === c.id ? 'active' : '')}><button onClick={() => select(c.id)}><strong>{c.pinned ? '● ' : ''}{c.title}</strong><small>{new Date(c.updatedAt).toLocaleDateString()}</small></button><details className="conversation-menu"><summary aria-label={'会话操作 ' + c.title}>⋯</summary><div><button onClick={() => void client.request(`/api/v1/conversations/${c.id}`, { method: 'PATCH', body: JSON.stringify({ expectedVersion: c.entityVersion, pinned: !c.pinned }) }).then(() => loadList()).catch(e => setError(e.message))}>{c.pinned ? '取消置顶' : '置顶'}</button><button onClick={() => void client.request(`/api/v1/conversations/${c.id}`, { method: 'PATCH', body: JSON.stringify({ expectedVersion: c.entityVersion, archived: !c.archived }) }).then(() => loadList()).catch(e => setError(e.message))}>{c.archived ? '取消归档' : '归档'}</button><button onClick={() => { if (window.confirm('删除此会话及其产物？已确认的长期记忆需要另行遗忘。'))
        void client.request(`/api/v1/conversations/${c.id}`, { method: 'DELETE', headers: { 'X-CSRF-Token': client.csrf } }).then(() => { if (cid === c.id)
            select(''); return loadList(); }).catch(e => setError(e.message)); }}>删除</button></div></details></div>)}{nextCursor && <button onClick={() => void loadList(nextCursor).catch(e => setError(e.message))}>加载更多</button>}</div><div className="sidebar-tools"><button onClick={() => setAdminPage('memories')}>个人记忆</button><button onClick={() => setAdminPage('resources')}>知识与 Skill</button><button onClick={() => setAdminPage('agents')}>Agent 配置</button><button onClick={() => setAdminPage('mcp')}>MCP</button><button onClick={() => setAdminPage('runs')}>运行记录</button></div><footer className="profile"><div className="avatar">{user?.principalId.slice(0, 1).toUpperCase()}</div><div><strong>{user?.principalId}</strong><small>{user?.workspaceId}</small></div><button onClick={() => void client.post('/api/v1/auth/logout', {}).then(reset).catch(e => setError('退出未确认：' + e.message))} aria-label="退出登录">退出</button></footer></aside>;
    if (checking)
        return <main className="login-screen">正在检查会话…</main>;
    if (!user)
        return <main className="login-screen"><form className="login-card" onSubmit={async (e) => { e.preventDefault(); setBusy(true); setError(''); try {
            const u = await client.post<User>('/api/v1/auth/login', { username, token });
            client.csrf = u.csrfToken;
            setToken('');
            setUser(u);
        }
        catch (err) {
            setError((err as Error).message);
        }
        finally {
            setBusy(false);
        } }}><span className="brand-emblem large">霊</span><p className="eyebrow">PROJECT REIMU</p><h1>让对话，成为工作。</h1><p>会话、工具与知识，在一个受控的工作空间里协同。</p><label>用户<input required autoComplete="username" value={username} onChange={e => setUsername(e.target.value)}/></label><label>访问密钥<input required type="password" autoComplete="current-password" value={token} onChange={e => setToken(e.target.value)}/></label><button className="primary" disabled={busy}>进入工作台</button><small>访问密钥由部署管理员提供。首版不提供第三方 SSO。</small>{error && <p role="alert" className="notice error">{error}</p>}</form></main>;
    const chat = cid ? <Chat key={user.principalId + cid} cid={cid} refs={refs} setRefs={setRefs} onArtifact={openArtifact} onConversation={() => void loadList().catch(e => setError(e.message))}/> : <div className="welcome"><span className="eyebrow">YOUR AGENT WORKSPACE</span><h1>今天，一起解决什么？</h1><p>持续对话、可追溯结果，以及在右侧随时展开的工作内容。</p><div className="entry-card"><label>选择已发布的助手<select aria-label="选择助手" value={agentRef} onChange={e => setAgentRef(e.target.value)}>{agents.filter(a => a.revision > 0).map(a => <option value={a.id + '@' + a.revision} key={a.id}>{a.name} · v{a.revision}</option>)}</select></label><button className="primary" disabled={busy || !agentRef} onClick={() => void create()}>开始新会话 →</button></div><div className="welcome-cards"><div><strong>流程与结构</strong><p>将步骤组织成可以展开查看的流程图。</p></div><div><strong>数据与证据</strong><p>查看可筛选表格，带着选中记录继续提问。</p></div><div><strong>文档与结论</strong><p>查看固定版本报告，按段落提出修改。</p></div></div>{cap?.mode === 'demo' && <div className="notice">当前为确定性演示模式，不调用真实大模型。可输入“生成演示流程、表格和报告”验证。</div>}</div>;
    return <div className="app"><div className="topbar">{narrow && <button aria-label="打开会话导航" onClick={() => setNav(!nav)}>☰</button>}<span>REIMU / 工作台</span><span className={'mode ' + cap?.mode}>{cap?.mode === 'demo' ? 'DEMO · 非真实模型' : 'LIVE · 服务端模型'}</span><small>开发预览 · 单 Worker</small></div>{error && <div role="alert" className="global-error"><span>{error}</span><button onClick={() => setError('')}>关闭</button></div>}<div className="workbench">{narrow ? <>{nav && sidebar}<main className="chat-host">{chat}</main>{artifact && <div className="mobile-artifact">{artifact}</div>}</> : <PanelGroup direction="horizontal" autoSaveId="reimu-layout"><Panel id="navigation" order={1} defaultSize={20} minSize={16} maxSize={26}>{sidebar}</Panel><PanelResizeHandle className="resize-handle"/><Panel id="chat" order={2} defaultSize={artifact ? 42 : 80} minSize={30}><main className="chat-host">{chat}</main></Panel>{artifact && <><PanelResizeHandle className="resize-handle"/><Panel id="artifact" order={3} defaultSize={38} minSize={28}>{artifact}</Panel></>}</PanelGroup>}</div>{adminPage && <Admin key={adminPage} page={adminPage} admin={user.admin} onClose={() => { setAdminPage(''); void loadList().catch(e => setError(e.message)); }}/>}</div>;
}
