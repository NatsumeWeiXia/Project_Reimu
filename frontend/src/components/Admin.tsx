import { useEffect, useState } from 'react';
import { client } from '../core/api';
export interface Agent {
    id?: string;
    name: string;
    systemPrompt: string;
    model: string;
    revision: number;
    entityVersion: number;
    tools: string[];
    skills: string[];
    knowledge: string[];
    maxSteps: number;
}
interface Resource {
    id: string;
    revision: number;
    name: string;
    content: string;
    hash: string;
    kind: string;
    owner: string;
    readers: string[];
}
interface Memory {
    id: string;
    content: string;
    status: string;
    entityVersion: number;
    sourceMessageId: string;
}
export function Admin({ page, onClose, admin }: {
    page: string;
    onClose: () => void;
    admin: boolean;
}) {
    const [error, setError] = useState(''), [busy, setBusy] = useState(false), [agents, setAgents] = useState<Agent[]>([]), [agent, setAgent] = useState<Agent>(), [resources, setResources] = useState<Resource[]>([]), [tools, setTools] = useState<{
        name: string;
        description: string;
    }[]>([]), [memories, setMemories] = useState<Memory[]>([]), [misc, setMisc] = useState<unknown[]>([]), [name, setName] = useState(''), [content, setContent] = useState(''), [kind, setKind] = useState<'skill' | 'knowledge'>('knowledge'), [shared, setShared] = useState(false);
    async function load() { setError(''); if (page === 'agents') {
        const [a, t, k, s] = await Promise.all([client.request<Agent[]>('/api/v1/agents'), client.request<{
                name: string;
                description: string;
            }[]>('/api/v1/tools'), client.request<Resource[]>('/api/v1/resources?kind=knowledge'), client.request<Resource[]>('/api/v1/resources?kind=skill')]);
        setAgents(a);
        setTools(t);
        setResources([...k, ...s]);
    }
    else if (page === 'resources') {
        setResources(await client.request<Resource[]>(`/api/v1/resources?kind=${kind}`));
    }
    else if (page === 'memories') {
        setMemories(await client.request<Memory[]>('/api/v1/memories'));
    }
    else if (page === 'mcp') {
        setMisc(await client.request<unknown[]>('/api/v1/mcp'));
    }
    else
        setMisc(await client.request<unknown[]>('/api/v1/runs')); }
    useEffect(() => { void load().catch(e => setError(e.message)); }, [page, kind]);
    async function act(fn: () => Promise<void>) { setBusy(true); setError(''); try {
        await fn();
        await load();
    }
    catch (e) {
        setError((e as Error).message);
    }
    finally {
        setBusy(false);
    } }
    const title = ({ agents: 'Agent 定义与发布', resources: '知识与 Skill', memories: '个人长期记忆', mcp: 'MCP 连接', runs: '运行记录' } as Record<string, string>)[page];
    const toggle = (xs: string[], id: string) => xs.includes(id) ? xs.filter(x => x !== id) : [...xs, id];
    return <div className="dialog-backdrop"><section className="admin-dialog" role="dialog" aria-modal="true" aria-label={title}><header><h2>{title}</h2><button autoFocus onClick={onClose} aria-label="关闭设置">×</button></header>{error && <div role="alert" className="notice error">{error}</div>}<div className="admin-body">
 {page === 'agents' && <div className="admin-columns"><aside>{agents.map(a => <button className="resource-item" key={a.id} onClick={() => setAgent(structuredClone(a))}><strong>{a.name}</strong><small>发布版本 {a.revision} · 草稿修订 {a.entityVersion}</small></button>)}{admin && <button onClick={() => setAgent({ name: '新助手', systemPrompt: '根据证据回答问题，不伪造操作成功。', model: agents[0]?.model ?? '', revision: 0, entityVersion: 0, tools: ['artifact_create', 'artifact_revise', 'knowledge_search', 'skill_read', 'user_ask'], skills: [], knowledge: [], maxSteps: 16 })}>＋ 新建 Agent</button>}</aside>{agent ? <form onSubmit={e => { e.preventDefault(); void act(async () => { const a = await client.post<Agent>('/api/v1/agents', agent); setAgent(a); }); }}><label>名称<input value={agent.name} onChange={e => setAgent({ ...agent, name: e.target.value })} required disabled={!admin}/></label><label>模型 ID<input value={agent.model} onChange={e => setAgent({ ...agent, model: e.target.value })} disabled={!admin}/><small>必须与服务器 REIMU_MODEL_ID 一致；密钥不会发送到浏览器。</small></label><label>系统提示词<textarea rows={5} value={agent.systemPrompt} onChange={e => setAgent({ ...agent, systemPrompt: e.target.value })} required disabled={!admin}/></label><label>执行片段预算<input type="number" min={1} max={100} value={agent.maxSteps} onChange={e => setAgent({ ...agent, maxSteps: Number(e.target.value) })} disabled={!admin}/></label><fieldset><legend>工具白名单</legend>{tools.map(t => <label className="checkbox-row" key={t.name}><input type="checkbox" disabled={!admin} checked={agent.tools.includes(t.name)} onChange={() => setAgent({ ...agent, tools: toggle(agent.tools, t.name) })}/><span>{t.name}<small>{t.description}</small></span></label>)}</fieldset><fieldset><legend>绑定不可变资料版本</legend>{resources.map(r => { const field = r.kind === 'skill' ? 'skills' : 'knowledge', ref = r.id + '@' + r.revision; return <label className="checkbox-row" key={ref}><input type="checkbox" disabled={!admin} checked={agent[field].includes(ref)} onChange={() => setAgent({ ...agent, [field]: toggle(agent[field], ref) })}/>{r.kind} · {r.name} · v{r.revision}</label>; })}</fieldset>{admin && <div className="actions"><button disabled={busy}>保存草稿</button><button type="button" className="primary" disabled={!agent.id || busy} onClick={() => void act(async () => { const draft = await client.post<Agent>('/api/v1/agents', agent); const a = await client.post<Agent>(`/api/v1/agents/${draft.id}/publish`, { expectedVersion: draft.entityVersion }); setAgent(a); })}>保存并发布</button></div>}<p className="muted">已有会话不会自动升级到新版本。新版本用于新建会话。</p></form> : <p>选择一个 Agent 查看配置。</p>}</div>}
 {page === 'resources' && <><div className="toolbar"><select aria-label="资料类型" value={kind} onChange={e => setKind(e.target.value as 'skill' | 'knowledge')}><option value="knowledge">知识资料（文本）</option><option value="skill">说明型 Skill</option></select></div><div className="admin-columns"><div>{resources.map(r => <details className="resource-item" key={r.id}><summary>{r.name} · v{r.revision}</summary><small>仅属主与指定读取者可见 · {r.hash.slice(0, 12)}</small><pre>{r.content}</pre></details>)}</div>{admin && <form onSubmit={e => { e.preventDefault(); void act(async () => { await client.post('/api/v1/resources', { kind, name, content, readers: shared ? ['*'] : [] }); setName(''); setContent(''); }); }}><label>资料名称<input required value={name} onChange={e => setName(e.target.value)}/></label><label>导入 UTF-8 文本<input type="file" accept=".txt,.md" onChange={async (e) => { const f = e.target.files?.[0]; if (!f)
        return; if (f.size > 500000) {
        setError('文本文件不能超过 500 KB');
        return;
    } setContent(await f.text()); if (!name)
        setName(f.name); }}/></label><label>正文<textarea required rows={12} maxLength={500000} placeholder={kind === 'skill' ? '---\nname: diagnosis\ndescription: 按证据诊断\n---\n操作说明…' : '上传或粘贴可以追溯的知识原文'} value={content} onChange={e => setContent(e.target.value)}/></label><label className="checkbox-row"><input type="checkbox" checked={shared} onChange={e => setShared(e.target.checked)}/>显式共享给当前工作空间全部用户</label><button className="primary" disabled={busy}>创建已发布资料</button><p className="muted">首版为不可变文本资料；新内容作为新资料发布后重新绑定 Agent。Skill 不执行脚本。</p></form>}</div></>}
 {page === 'memories' && <><p>只有确认后的记忆进入未来上下文。遗忘会清理未来模型上下文，不会删除已经下载的内容。</p>{memories.map(m => <article className="memory-card" key={m.id}><strong>{m.status === 'active' ? '已确认' : '待确认'}</strong><p>{m.content}</p><small>来源消息：{m.sourceMessageId}</small><div className="actions">{m.status === 'candidate' && <button className="primary" disabled={busy} onClick={() => void act(async () => { await client.post(`/api/v1/memories/${m.id}/decision`, { expectedVersion: m.entityVersion, decision: 'active' }); })}>确认记忆</button>}<button disabled={busy} onClick={() => void act(async () => { await client.post(`/api/v1/memories/${m.id}/decision`, { expectedVersion: m.entityVersion, decision: 'deleted' }); })}>遗忘</button></div></article>)}{!memories.length && <p className="empty">暂无长期记忆。可以在会话中请求记住一条偏好。</p>}</>}
 {page === 'mcp' && <><p>仅显示部署方固定配置并授权给你的 Streamable HTTP 服务。所有 MCP 调用都需要本次操作审批；不支持任意网址、STDIO 或浏览器传入凭据。</p>{misc.map((value, i) => { const item = value as {
        name: string;
        tools: string[];
    }; return <article className="resource-item" key={i}><strong>{item.name}</strong><p>{item.tools.join(' / ')}</p>{admin && <button disabled={busy} onClick={() => void act(async () => { const result = await client.post<unknown[]>(`/api/v1/mcp/${encodeURIComponent(item.name)}/discover`, {}); setContent(JSON.stringify(result, null, 2)); })}>连接与 Schema 检查</button>}</article>; })}{!misc.length && <p className="empty">尚未配置 MCP 服务。配置入口是服务器 REIMU_MCP_JSON，不在浏览器保存密钥。</p>}{content && <pre>{content}</pre>}</>}
 {page === 'runs' && <>{misc.map((value, i) => { const r = value as {
        id: string;
        state: string;
        steps: number;
        budget: number;
        errorCode: string;
    }; return <div className="resource-item" key={i}><strong>{r.state}</strong><code>{r.id}</code><span>执行片段 {r.steps} / {r.budget}</span>{r.errorCode && <p>{r.errorCode}</p>}</div>; })}<p className="muted">首版为单 Agent 会话执行，不包含前方案的多 Agent 工作流引擎。完整操作审计、分布式运行和价格计费仍需后续实现。</p></>}
 </div></section></div>;
}
