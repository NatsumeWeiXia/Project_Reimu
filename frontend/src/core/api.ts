export class ApiError extends Error {
    constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message); }
}
export class Client {
    private generation = 0;
    resetScope() { this.generation++; this.csrf = ''; }
    csrf = '';
    onUnauthorized: () => void = () => { };
    async request<T>(path: string, options: RequestInit = {}): Promise<T> { if (!path.startsWith('/api/'))
        throw new Error('Only same-origin API paths are allowed'); const generation = this.generation; const headers = new Headers(options.headers); if (options.body) {
        headers.set('Content-Type', 'application/json');
        headers.set('X-CSRF-Token', this.csrf);
    } const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' }); if (generation !== this.generation)
        throw new DOMException('Identity changed', 'AbortError'); if (!response.ok) {
        const body = await response.json().catch(() => ({ code: 'HTTP_ERROR', message: `HTTP ${response.status}` }));
        if (generation === this.generation && response.status === 401 && !path.endsWith('/auth/login'))
            this.onUnauthorized();
        throw new ApiError(response.status, body.code, body.message, body.traceId);
    } const data = await response.json() as T; if (generation !== this.generation)
        throw new DOMException('Identity changed', 'AbortError'); return data; }
    post<T>(path: string, value: unknown, signal?: AbortSignal) { return this.request<T>(path, { method: 'POST', body: JSON.stringify(value), signal }); }
    async download(path: string, name: string) { if (!path.startsWith('/api/'))
        throw new Error('Invalid download path'); const generation = this.generation; const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store' }); if (generation !== this.generation)
        throw new DOMException('Identity changed', 'AbortError'); if (!response.ok) {
        if (response.status === 401)
            this.onUnauthorized();
        throw new Error(`下载失败 HTTP ${response.status}`);
    } const blob = await response.blob(); if (generation !== this.generation)
        throw new DOMException('Identity changed', 'AbortError'); const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
}
export const client = new Client();
