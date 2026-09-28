import { ApiError, Client } from './api.js';
import { Projection, ResetRequired, snapshot, type Snapshot, type Reference } from './protocol.js';
import { consumeSse } from './sse.js';
export interface View {
    snapshot?: Snapshot;
    connection: 'loading' | 'connected' | 'reconnecting' | 'closed';
    error?: string;
    pending?: {
        clientRequestId: string;
        text: string;
        contextReferences: Reference[];
    };
    sending: boolean;
}
/** One instance per selected conversation. Closing the reader does NOT cancel the business run. */
export class ChatController {
    private projection?: Projection;
    private listeners = new Set<() => void>();
    private abort = new AbortController();
    private active = false;
    private epoch = 0;
    private view: View = { connection: 'loading', sending: false };
    constructor(readonly cid: string, readonly api: Client) { }
    subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
    getSnapshot = () => this.view;
    private patch(patch: Partial<View>) { if (!this.active)
        return; this.view = { ...this.view, ...patch }; this.listeners.forEach(f => f()); }
    private publish() { this.patch({ snapshot: this.projection?.value }); }
    start() { if (this.active)
        return; this.active = true; this.abort = new AbortController(); void this.listen(++this.epoch, this.abort.signal); }
    close() { this.active = false; this.epoch++; this.abort.abort(); this.listeners.clear(); }
    async refresh(signal = this.abort.signal, epoch = this.epoch) { const result = await this.api.request<unknown>(`/api/v1/conversations/${this.cid}/snapshot`, { signal }); if (!this.active || epoch !== this.epoch || signal.aborted)
        return; this.projection = new Projection(snapshot(result)); this.publish(); }
    private async listen(epoch: number, signal: AbortSignal) {
        let retry = 0;
        while (this.active && epoch === this.epoch && !signal.aborted) {
            try {
                await this.refresh(signal, epoch);
                if (!this.active || epoch !== this.epoch || signal.aborted || !this.projection)
                    return;
                const s = this.projection.value;
                this.patch({ connection: 'connected', error: undefined });
                const path = `/api/v1/conversations/${this.cid}/stream?streamId=${encodeURIComponent(s.streamId)}&after=${encodeURIComponent(s.cursor)}`;
                const r = await fetch(path, { headers: { Accept: 'text/event-stream' }, credentials: 'same-origin', cache: 'no-store', signal });
                if (epoch !== this.epoch || signal.aborted) {
                    await r.body?.cancel();
                    return;
                }
                if (r.status === 401) {
                    this.api.onUnauthorized();
                    return;
                }
                if (r.status === 410)
                    throw new ResetRequired('需要重新读取快照');
                if (!r.ok || !r.body || !r.headers.get('content-type')?.includes('text/event-stream'))
                    throw new Error('事件订阅暂时不可用');
                retry = 0;
                await consumeSse(r.body, f => { if (!this.active || epoch !== this.epoch || signal.aborted)
                    return; if (f.event === 'stream.reset')
                    throw new ResetRequired('订阅需刷新'); const changed = this.projection!.apply(JSON.parse(f.data)); if (changed)
                    this.publish(); }, signal);
            }
            catch (e) {
                if (!this.active || epoch !== this.epoch || signal.aborted)
                    return;
                this.patch({ connection: 'reconnecting', error: e instanceof ResetRequired ? '正在同步最新状态' : e instanceof Error ? e.message : '连接中断' });
            }
            if (!this.active || epoch !== this.epoch || signal.aborted)
                return;
            const wait = Math.min(10000, 500 * 2 ** Math.min(retry++, 5));
            await new Promise<void>(resolve => { const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); }; const timer = setTimeout(done, wait); signal.addEventListener('abort', done, { once: true }); });
        }
    }
    async send(text: string, refs: Reference[]) { if (this.view.pending || this.view.sending)
        throw new Error('上一条请求尚未核对'); if (!text.trim())
        return; this.patch({ pending: { clientRequestId: crypto.randomUUID(), text, contextReferences: refs.map(({ title, ...r }) => r) }, sending: true }); await this.retryPending(); }
    async retryPending() {
        const pending = this.view.pending;
        if (!pending)
            return;
        this.patch({ sending: true, error: undefined });
        try {
            await this.api.post(`/api/v1/conversations/${this.cid}/messages`, pending);
            this.patch({ pending: undefined, sending: false });
        }
        catch (e) {
            if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 429) {
                this.patch({ pending: undefined, sending: false, error: e.message });
                throw e;
            }
            try {
                await this.api.request(`/api/v1/requests/by-client-id/${pending.clientRequestId}`);
                this.patch({ pending: undefined, sending: false });
            }
            catch {
                this.patch({ sending: false, error: '发送结果尚未确认。请使用“核对/重试原请求”，不要重复发送。' });
                throw e;
            }
        }
    }
    async more() { const first = this.projection?.value.messages[0]; if (!first)
        return; const result = await this.api.request<{
        items: unknown[];
        hasMore: boolean;
    }>(`/api/v1/conversations/${this.cid}/messages?before=${encodeURIComponent(first.ordinal)}`); if (this.active && this.projection) {
        this.projection.prepend(result.items, result.hasMore);
        this.publish();
    } }
    async cancel() { const run = this.projection?.value.activity.runId; if (run)
        await this.api.post(`/api/v1/runs/${run}/cancellation-requests`, { clientRequestId: crypto.randomUUID() }); }
}
