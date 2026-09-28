import { lazy, Suspense, useEffect, useState } from 'react';
import { client } from '../core/api';
import { safeUrl, type Artifact, type Part, type Reference, type Selection } from '../core/protocol';
const Graph = lazy(() => import('./Graph'));
const DataTable = lazy(() => import('./DataTable'));
const Markdown = lazy(() => import('./Markdown'));
export type ArtifactRef = Extract<Part, {
    type: 'artifact_ref';
}>;
export function ArtifactPanel({ reference, onClose, onQuote }: {
    reference: ArtifactRef;
    onClose: () => void;
    onQuote: (ref: Reference) => void;
}) {
    const [revision, setRevision] = useState(reference.revision), [artifact, setArtifact] = useState<Artifact>(), [versions, setVersions] = useState<number[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false);
    useEffect(() => setRevision(reference.revision), [reference.artifactId, reference.revision]);
    useEffect(() => { const abort = new AbortController(); setArtifact(undefined); setError(''); Promise.all([client.request<Artifact>(`/api/v1/artifacts/${reference.artifactId}/revisions/${revision}`, { signal: abort.signal }), client.request<{
            revisions: {
                revision: number;
            }[];
        }>(`/api/v1/artifacts/${reference.artifactId}`, { signal: abort.signal })]).then(([a, m]) => { if (abort.signal.aborted)
        return; if (a.artifactId !== reference.artifactId || a.revision !== revision || a.schemaVersion !== 1)
        throw new Error('产物协议不匹配'); setArtifact(a); setVersions(m.revisions.map(v => v.revision)); }).catch(e => { if (!abort.signal.aborted)
        setError(e.message); }); return () => abort.abort(); }, [reference.artifactId, revision]);
    const quote = (selection: Selection) => { if (artifact?.permissions.canReference)
        onQuote({ artifactId: artifact.artifactId, revision: artifact.revision, title: artifact.title, selection }); };
    async function download() { setBusy(true); try {
        await client.download(`/api/v1/artifacts/${reference.artifactId}/revisions/${revision}/download`, reference.kind === 'markdown' ? `report-v${revision}.md` : `artifact-v${revision}.json`);
    }
    catch (e) {
        setError((e as Error).message);
    }
    finally {
        setBusy(false);
    } }
    return <section className="artifact-workspace" aria-label="内容工作区"><header className="artifact-header"><div><span className="eyebrow">ARTIFACT / {reference.kind.toUpperCase()}</span><h2>{reference.title}</h2></div><button onClick={onClose} aria-label="关闭内容工作区">×</button></header><div className="artifact-toolbar"><label>版本 <select value={revision} onChange={e => setRevision(Number(e.target.value))}>{(versions.length ? versions : [revision]).map(v => <option key={v} value={v}>v{v}</option>)}</select></label><button onClick={() => quote({ type: 'whole' })} disabled={!artifact?.permissions.canReference}>引用到对话</button>{reference.kind !== 'table' && <button onClick={download} disabled={busy || !artifact?.permissions.canDownload}>下载</button>}</div>
    {versions.some(v => v > revision) && <div className="notice subtle">有更新版本；当前仍显示你打开的 v{revision}。</div>}{error && <p role="alert" className="notice error">{error}</p>}
    <div className={'artifact-body kind-' + reference.kind}><Suspense fallback={<p className="muted">正在加载展示组件…</p>}>{!artifact && !error ? <p className="muted">正在读取已保存内容…</p> : artifact?.kind === 'reactflow' ? <Graph key={artifact.artifactId + revision} artifact={artifact} quote={quote}/> : artifact?.kind === 'table' ? <DataTable key={artifact.artifactId + revision} artifact={artifact} quote={quote}/> : artifact?.kind === 'markdown' ? <Markdown key={artifact.artifactId + revision} artifact={artifact} quote={quote}/> : artifact?.kind === 'webpage' ? <WebPreview artifact={artifact}/> : artifact ? <p>此类型暂不支持预览。</p> : null}</Suspense></div>
    <footer className="artifact-footer">固定版本 · 打开预览不会调用模型 · {artifact?.contentHash.slice(0, 12)}</footer></section>;
}
function WebPreview({ artifact }: {
    artifact: Artifact;
}) { const href = safeUrl(String(artifact.content.url ?? '')); return <div className="web-fallback"><div className="web-icon" aria-hidden>↗</div><h3>{String(artifact.content.title ?? artifact.title)}</h3><p>外部网页使用安全外链打开，不移除对方的嵌入限制，也不共享你的登录凭据。</p><p className="muted">{String(artifact.content.reason ?? '此网站的嵌入授权未核实。')}</p>{href ? <a className="button primary" href={href} target="_blank" rel="noopener noreferrer">在新标签页打开</a> : <p role="alert">网址无效或使用了不允许的协议。</p>}<code>{href}</code></div>; }
