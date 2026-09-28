import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AssistantRuntimeProvider, ComposerPrimitive, ThreadPrimitive, useExternalStoreRuntime, type AppendMessage, type ThreadMessageLike } from '@assistant-ui/react';
import { ChatController } from '../core/controller';
import { client } from '../core/api';
import type { Message, Part, Reference, Snapshot } from '../core/protocol';
import type { ArtifactRef } from '../renderers/ArtifactPanel';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { safeUrl } from '../core/protocol';
const labels: Record<string, string> = { queued: '排队中', generating: '正在生成', working: '正在使用工具', waiting_approval: '等待批准', waiting_user: '等待补充', paused_budget: '预算暂停', cancelling: '正在请求停止', idle: '可以继续提问', failed: '需要核实工具结果' };
export function Chat({ cid, refs, setRefs, onArtifact, onConversation }: {
    cid: string;
    refs: Reference[];
    setRefs: (v: Reference[]) => void;
    onArtifact: (v: ArtifactRef) => void;
    onConversation: () => void;
}) {
    const controller = useMemo(() => new ChatController(cid, client), [cid]);
    const view = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
    const [error, setError] = useState('');
    useEffect(() => { controller.start(); return () => controller.close(); }, [controller]);
    const convert = useCallback((m: Message): ThreadMessageLike => ({ id: m.id, role: m.role, content: [{ type: 'text', text: m.parts.filter((p): p is Extract<Part, {
                    type: 'text';
                }> => p.type === 'text').map(p => p.text).join('\n') }], createdAt: new Date(m.createdAt) }), []);
    const runtime = useExternalStoreRuntime({ messages: view.snapshot?.messages ?? [], convertMessage: convert, isRunning: view.sending || !!view.pending || view.snapshot?.activity.sendMode === 'disabled', onNew: async (m: AppendMessage) => { const text = m.content.filter(p => p.type === 'text').map(p => p.type === 'text' ? p.text : '').join('\n'); try {
            await controller.send(text, refs);
            setRefs([]);
            onConversation();
        }
        catch (e) {
            setError((e as Error).message);
            throw e;
        } }, onCancel: async () => { try {
            await controller.cancel();
        }
        catch (e) {
            setError((e as Error).message);
        } } });
    const viewport = useRef<HTMLDivElement>(null), follow = useRef(true);
    const messages = view.snapshot?.messages ?? [];
    useEffect(() => { if (follow.current && viewport.current)
        viewport.current.scrollTop = viewport.current.scrollHeight; }, [messages]);
    const snap = view.snapshot;
    return <AssistantRuntimeProvider runtime={runtime}><ThreadPrimitive.Root className="chat-thread"><header className="chat-header"><div><span className="eyebrow">CONVERSATION</span><h1>{snap?.conversation.title ?? '正在加载会话'}</h1></div><div className="chat-header-actions"><span className={'connection ' + view.connection}>{view.connection === 'connected' ? '已同步' : view.connection === 'loading' ? '读取中' : '重连中'}</span>{snap && <button onClick={async () => { const title = window.prompt('会话名称', snap.conversation.title); if (title?.trim())
        try {
            await client.request(`/api/v1/conversations/${cid}`, { method: 'PATCH', body: JSON.stringify({ expectedVersion: snap.conversation.entityVersion, title }) });
            onConversation();
        }
        catch (e) {
            setError((e as Error).message);
        } }}>重命名</button>}</div></header>
   {(error || view.error) && <div className="notice error" role="alert"><span>{error || view.error}</span><button onClick={() => setError('')}>关闭</button></div>}{view.pending && <div className="notice"><span>原请求仍待核对。不会生成新的请求标识。</span><button disabled={view.sending} onClick={() => void controller.retryPending().catch(e => setError(e.message))}>核对/重试原请求</button></div>}
   <div className="message-viewport" ref={viewport} onScroll={() => { const v = viewport.current; if (v)
        follow.current = v.scrollHeight - v.clientHeight - v.scrollTop < 80; }} role="log" aria-live="polite" aria-label="聊天记录">
   {snap?.hasMore && <button className="history-more" onClick={async () => { const v = viewport.current, old = v?.scrollHeight ?? 0; try {
        await controller.more();
        requestAnimationFrame(() => { if (v)
            v.scrollTop += v.scrollHeight - old; });
    }
    catch (e) {
        setError((e as Error).message);
    } }}>加载更早消息</button>}
   {!messages.length && <div className="conversation-empty"><span className="brand-emblem large">霊</span><h2>从一个问题开始。</h2><p>一起梳理问题、寻找证据，把结果变成可以查看和继续修改的内容。</p></div>}
   {snap && messages.map(m => <Bubble key={m.id} message={m} snapshot={snap} onArtifact={onArtifact} onError={setError}/>)}
   </div>
   <div className="composer-wrap"><div className="activity" role="status"><span className={snap?.activity.state === 'idle' ? 'dot idle' : 'dot'}/>{labels[snap?.activity.state ?? ''] ?? '正在读取'}{snap?.activity.canRequestCancellation && <button className="ghost" onClick={() => void controller.cancel().catch(e => setError(e.message))}>停止任务</button>}</div>
   {refs.length > 0 && <div className="reference-chips">{refs.map((r, i) => <span key={r.artifactId + ':' + r.revision} className="reference-chip">{r.title ?? r.artifactId} · v{r.revision} · {r.selection.type === 'whole' ? '全文' : '选中内容'}<button aria-label="移除引用" onClick={() => setRefs(refs.filter((_, n) => n !== i))}>×</button></span>)}</div>}
   <ComposerPrimitive.Root className="composer"><ComposerPrimitive.Input className="composer-input" placeholder="发送消息；选中右侧内容后可引用到这里" autoFocus rows={2}/><ComposerPrimitive.Send className="primary send-button" aria-label="发送消息">发送 ↑</ComposerPrimitive.Send></ComposerPrimitive.Root><p className="composer-footnote">结果需核实 · 写操作需批准 · 文档可在知识资料中录入</p></div>
 </ThreadPrimitive.Root></AssistantRuntimeProvider>;
}
function Bubble({ message: m, snapshot: s, onArtifact, onError }: {
    message: Message;
    snapshot: Snapshot;
    onArtifact: (p: ArtifactRef) => void;
    onError: (s: string) => void;
}) { return <article className={'message ' + m.role} data-testid={'message-' + m.role}><div className="message-author">{m.role === 'user' ? '你' : 'Reimu'}{m.status === 'interrupted' && <span> · 已停止</span>}{m.status === 'error' && <span> · 执行未完成</span>}</div><div className="message-content">{m.parts.map(p => p.type === 'text' ? <ReactMarkdown key={p.id} remarkPlugins={[remarkGfm]} skipHtml components={{ a: ({ href, children }) => { const url = safeUrl(href ?? ''); return url ? <a href={url} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>; }, img: ({ alt }) => <span>[图片：{alt}]</span> }}>{p.text}</ReactMarkdown> : p.type === 'artifact_ref' ? <button className="artifact-card" key={p.id} onClick={() => onArtifact(p)}><span className="artifact-kind">{{ reactflow: '流程图', table: '数据表', markdown: '文档', webpage: '网址' }[p.kind]}</span><span><strong>{p.title}</strong><small>版本 {p.revision} · 点击查看</small></span><span>↗</span></button> : p.type === 'tool_ref' ? <ToolStatus key={p.id} id={p.operationId} snapshot={s}/> : p.type === 'approval_ref' ? <ApprovalCard key={p.id} id={p.approvalId} snapshot={s} onError={onError}/> : p.type === 'question_ref' ? <QuestionCard key={p.id} id={p.questionId} snapshot={s} onError={onError}/> : null)}{m.status === 'pending' && <span className="muted">任务已接收，正在排队…</span>}</div></article>; }
function ToolStatus({ id, snapshot }: {
    id: string;
    snapshot: Snapshot;
}) { const t = snapshot.tools.find(t => t.operationId === id); return t ? <details className="tool-status"><summary>{t.toolName} · {t.state}</summary><p>{t.summary}</p><code>{t.operationId}</code></details> : null; }
function ApprovalCard({ id, snapshot, onError }: {
    id: string;
    snapshot: Snapshot;
    onError: (s: string) => void;
}) { const a = snapshot.approvals.find(a => a.approvalId === id); const [busy, setBusy] = useState(false); const requestKeys = useRef<Record<string, string>>({}); if (!a)
    return null; async function decide(decision: 'approved' | 'rejected') { if (!a)
    return; setBusy(true); try {
    const key = requestKeys.current[decision] ??= crypto.randomUUID();
    await client.post(`/api/v1/approvals/${id}/decisions`, { approvalRevision: a.entityVersion, operationId: a.operationId, argsHash: a.argsHash, decision, clientRequestId: key });
}
catch (e) {
    onError((e as Error).message);
}
finally {
    setBusy(false);
} } const active = a.canDecide && a.status === 'pending' && Date.parse(a.expiresAt) > Date.now(); return <section className="approval-card"><h3>此操作需要你确认</h3><p>{a.riskSummary}</p><pre>{a.parameterSummary}</pre><small>状态：{a.status} · 到期：{new Date(a.expiresAt).toLocaleString()}</small><div className="actions"><button disabled={!active || busy} onClick={() => void decide('rejected')}>拒绝</button><button className="primary" disabled={!active || busy} onClick={() => void decide('approved')}>批准本次操作</button></div></section>; }
function QuestionCard({ id, snapshot, onError }: {
    id: string;
    snapshot: Snapshot;
    onError: (s: string) => void;
}) { const q = snapshot.questions.find(q => q.questionId === id), [answer, setAnswer] = useState(''), [busy, setBusy] = useState(false); const request = useRef<{
    text: string;
    id: string;
}>(); if (!q)
    return null; return <form className="question-card" onSubmit={async (e) => { e.preventDefault(); setBusy(true); if (request.current?.text !== answer)
    request.current = { text: answer, id: crypto.randomUUID() }; try {
    await client.post(`/api/v1/questions/${id}/answers`, { questionRevision: q.entityVersion, text: answer, clientRequestId: request.current.id });
}
catch (error) {
    onError((error as Error).message);
}
finally {
    setBusy(false);
} }}><h3>需要补充信息</h3><p>{q.text}</p>{q.canAnswer ? <><textarea required aria-label="补充回答" maxLength={16000} value={answer} onChange={e => setAnswer(e.target.value)} disabled={busy}/><button className="primary" disabled={busy || !answer.trim()}>提交补充</button></> : <small>问题已关闭：{q.status}</small>}</form>; }
