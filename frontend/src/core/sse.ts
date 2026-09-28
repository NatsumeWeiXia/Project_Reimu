/** Streaming UTF-8/SSE parser. Framing is independent of network chunk boundaries. */
export interface SseFrame {
    event: string;
    data: string;
    id?: string;
}
export class SseParser {
    private frameSize = 0;
    private buffer = '';
    private type = 'message';
    private data: string[] = [];
    private id?: string;
    constructor(private readonly emit: (frame: SseFrame) => void) { }
    feed(chunk: string) { this.buffer += chunk; if (this.buffer.length > 2200000)
        throw new Error('SSE frame exceeds limit'); while (true) {
        const m = /\r\n|\r|\n/.exec(this.buffer);
        if (!m)
            return;
        if (m[0] === '\r' && m.index === this.buffer.length - 1)
            return;
        const line = this.buffer.slice(0, m.index);
        this.frameSize += line.length + 1;
        if (this.frameSize > 2200000)
            throw new Error('SSE frame exceeds limit');
        this.buffer = this.buffer.slice(m.index + m[0].length);
        if (line === '') {
            this.frameSize = 0;
            if (this.data.length)
                this.emit({ event: this.type, data: this.data.join('\n'), id: this.id });
            this.type = 'message';
            this.data = [];
            this.id = undefined;
            continue;
        }
        if (line.startsWith(':'))
            continue;
        const split = line.indexOf(':');
        const key = split < 0 ? line : line.slice(0, split);
        let v = split < 0 ? '' : line.slice(split + 1);
        if (v.startsWith(' '))
            v = v.slice(1);
        if (key === 'event')
            this.type = v;
        else if (key === 'data')
            this.data.push(v);
        else if (key === 'id' && !v.includes('\0'))
            this.id = v;
    } }
    // Incomplete frames at EOF are intentionally discarded; reconnect from last applied cursor.
    end() { this.frameSize = 0; this.buffer = ''; this.data = []; this.type = 'message'; this.id = undefined; }
}
export async function consumeSse(body: ReadableStream<Uint8Array>, onFrame: (frame: SseFrame) => void, signal: AbortSignal) { const reader = body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true }), parser = new SseParser(onFrame); const cancel = () => { void reader.cancel().catch(() => { }); }; signal.addEventListener('abort', cancel, { once: true }); try {
    while (!signal.aborted) {
        const { done, value } = await reader.read();
        if (done)
            break;
        parser.feed(decoder.decode(value, { stream: true }));
    }
    parser.feed(decoder.decode());
    parser.end();
}
finally {
    signal.removeEventListener('abort', cancel);
    await reader.cancel().catch(() => { });
    reader.releaseLock();
} }
