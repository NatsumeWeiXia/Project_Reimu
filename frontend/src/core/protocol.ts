/** Platform contract. SDK and assistant-ui objects never become the authoritative data model. */
export type Kind = 'reactflow' | 'table' | 'markdown' | 'webpage';
export type Part = {
    id: string;
    type: 'text';
    text: string;
    attemptId?: string;
} | {
    id: string;
    type: 'artifact_ref';
    artifactId: string;
    revision: number;
    kind: Kind;
    title: string;
} | {
    id: string;
    type: 'tool_ref';
    operationId: string;
} | {
    id: string;
    type: 'approval_ref';
    approvalId: string;
} | {
    id: string;
    type: 'question_ref';
    questionId: string;
};
export interface Message {
    id: string;
    conversationId: string;
    ordinal: string;
    entityVersion: number;
    role: 'user' | 'assistant';
    status: string;
    parts: Part[];
    createdAt: string;
    runId?: string;
    clientRequestId?: string;
}
export interface Conversation {
    id: string;
    title: string;
    entityVersion: number;
    entry: {
        kind: 'agent';
        ref: string;
    };
    pinned: boolean;
    archived: boolean;
    createdAt: string;
    updatedAt: string;
}
export interface Activity {
    state: string;
    sendMode: 'chat' | 'disabled';
    runId?: string;
    canRequestCancellation: boolean;
    label: string;
}
export interface Tool {
    operationId: string;
    entityVersion: number;
    toolName: string;
    state: string;
    summary: string;
}
export interface Approval {
    approvalId: string;
    runId: string;
    entityVersion: number;
    operationId: string;
    argsHash: string;
    parameterSummary: string;
    riskSummary: string;
    expiresAt: string;
    status: string;
    canDecide: boolean;
}
export interface Question {
    questionId: string;
    runId: string;
    entityVersion: number;
    text: string;
    status: string;
    canAnswer: boolean;
}
export interface Snapshot {
    protocolVersion: 1;
    conversation: Conversation;
    streamId: string;
    cursor: string;
    permissionVersion: string;
    workspaceId: string;
    messages: Message[];
    hasMore: boolean;
    activity: Activity;
    tools: Tool[];
    approvals: Approval[];
    questions: Question[];
}
export interface EventEnvelope {
    protocolVersion: 1;
    eventId: string;
    streamId: string;
    conversationId: string;
    workspaceId: string;
    permissionVersion: string;
    cursor: string;
    previousCursor: string;
    occurredAt: string;
    type: string;
    data: Record<string, unknown>;
}
export type Selection = {
    type: 'whole';
} | {
    type: 'graph_nodes';
    nodeIds: string[];
} | {
    type: 'markdown_blocks';
    blockIds: string[];
} | {
    type: 'table_rows';
    rowIds: string[];
    querySnapshotId?: string;
};
export interface Reference {
    artifactId: string;
    revision: number;
    selection: Selection;
    title?: string;
}
export interface Artifact {
    artifactId: string;
    conversationId: string;
    revision: number;
    kind: Kind;
    schemaVersion: number;
    title: string;
    contentHash: string;
    content: Record<string, unknown>;
    permissions: {
        canView: boolean;
        canReference: boolean;
        canDownload: boolean;
        canRequestRevision: boolean;
    };
}
export class ResetRequired extends Error {
}
function requireValue(value: unknown, why: string): asserts value { if (!value)
    throw new ResetRequired(why); }
export function isObject(v: unknown): v is Record<string, unknown> { return typeof v === 'object' && v !== null && !Array.isArray(v); }
export function message(value: unknown, cid: string): Message { requireValue(isObject(value), 'Invalid message'); requireValue(typeof value.id === 'string' && value.conversationId === cid, 'Message scope mismatch'); requireValue(typeof value.ordinal === 'string' && /^[1-9][0-9]*$/.test(value.ordinal), 'Invalid ordinal'); requireValue(Number.isSafeInteger(value.entityVersion) && Number(value.entityVersion) > 0, 'Invalid message version'); requireValue(value.role === 'user' || value.role === 'assistant', 'Invalid role'); requireValue(Array.isArray(value.parts), 'Invalid parts'); const ids = new Set<string>(); for (const p of value.parts) {
    requireValue(isObject(p) && typeof p.id === 'string' && !ids.has(p.id), 'Invalid or duplicate part');
    ids.add(p.id);
    requireValue(['text', 'artifact_ref', 'tool_ref', 'approval_ref', 'question_ref'].includes(String(p.type)), 'Unsupported message part');
    if (p.type === 'text')
        requireValue(typeof p.text === 'string', 'Invalid text');
    if (p.type === 'artifact_ref')
        requireValue(typeof p.artifactId === 'string' && Number.isSafeInteger(p.revision) && Number(p.revision) > 0, 'Invalid artifact ref');
} return value as unknown as Message; }
export function snapshot(value: unknown): Snapshot { requireValue(isObject(value) && value.protocolVersion === 1, 'Unsupported protocol'); requireValue(isObject(value.conversation) && typeof value.conversation.id === 'string', 'Missing conversation'); const cid = value.conversation.id; requireValue(typeof value.streamId === 'string' && typeof value.cursor === 'string' && typeof value.permissionVersion === 'string' && typeof value.workspaceId === 'string', 'Missing snapshot watermark'); requireValue(Array.isArray(value.messages) && Array.isArray(value.tools) && Array.isArray(value.approvals) && Array.isArray(value.questions) && isObject(value.activity), 'Invalid snapshot collections'); const ids = new Set<string>(); for (const m of value.messages) {
    const parsed = message(m, cid);
    requireValue(!ids.has(parsed.id), 'Duplicate message');
    ids.add(parsed.id);
} return structuredClone(value) as unknown as Snapshot; }
export function event(value: unknown): EventEnvelope { requireValue(isObject(value) && value.protocolVersion === 1, 'Unsupported event'); for (const key of ['eventId', 'streamId', 'conversationId', 'workspaceId', 'permissionVersion', 'cursor', 'previousCursor', 'type'])
    requireValue(typeof value[key] === 'string', 'Invalid event ' + key); requireValue(isObject(value.data), 'Invalid event data'); return value as unknown as EventEnvelope; }
export function appendText(current: string, offset: number, text: string): string { requireValue(Number.isSafeInteger(offset) && offset >= 0 && typeof text === 'string', 'Invalid delta'); if (offset === current.length)
    return current + text; if (offset < current.length && offset + text.length <= current.length && current.slice(offset, offset + text.length) === text)
    return current; throw new ResetRequired('Text delta gap or conflict'); }
function upsert<T>(items: T[], next: T, id: (v: T) => string, version: (v: T) => number): T[] { const old = items.find(v => id(v) === id(next)); if (old && version(old) > version(next))
    return items; return old ? items.map(v => id(v) === id(next) ? next : v) : [...items, next]; }
export class Projection {
    value: Snapshot;
    private seen = new Map<string, string>();
    constructor(input: Snapshot) { this.value = snapshot(input); }
    apply(input: unknown): boolean {
        const e = event(input), s = this.value;
        requireValue(e.conversationId === s.conversation.id && e.streamId === s.streamId && e.workspaceId === s.workspaceId, 'Cross-scope event');
        requireValue(e.permissionVersion === s.permissionVersion, 'Permission changed');
        const signature = JSON.stringify(e);
        if (this.seen.has(e.cursor)) {
            requireValue(this.seen.get(e.cursor) === signature, 'Conflicting repeated event');
            return false;
        }
        requireValue(e.previousCursor === s.cursor, 'Event gap; obtain a new snapshot');
        const n = structuredClone(s), d = e.data;
        switch (e.type) {
            case 'message.upsert': {
                const m = message(d.message, s.conversation.id);
                n.messages = upsert(n.messages, m, v => v.id, v => v.entityVersion);
                n.messages.sort((a, b) => BigInt(a.ordinal) < BigInt(b.ordinal) ? -1 : BigInt(a.ordinal) > BigInt(b.ordinal) ? 1 : 0);
                break;
            }
            case 'message.text.delta': {
                const m = n.messages.find(v => v.id === d.messageId);
                requireValue(m, 'Missing message');
                const p = m.parts.find(v => v.id === d.partId);
                requireValue(p?.type === 'text' && p.attemptId === d.attemptId, 'Old or missing attempt');
                requireValue(typeof d.offsetUtf16 === 'number', 'Offset must be a JSON number');
                const previous = p.text;
                p.text = appendText(p.text, Number(d.offsetUtf16), d.text as string);
                if (p.text !== previous)
                    m.entityVersion++;
                break;
            }
            case 'activity.changed':
                requireValue(isObject(d.activity), 'Invalid activity');
                n.activity = d.activity as unknown as Activity;
                break;
            case 'conversation.updated':
                requireValue(isObject(d.conversation) && d.conversation.id === s.conversation.id, 'Invalid conversation update');
                n.conversation = d.conversation as unknown as Conversation;
                break;
            case 'tool.updated':
                requireValue(isObject(d.tool), 'Invalid tool');
                n.tools = upsert(n.tools, d.tool as unknown as Tool, v => v.operationId, v => v.entityVersion);
                break;
            case 'approval.updated':
                requireValue(isObject(d.approval), 'Invalid approval');
                n.approvals = upsert(n.approvals, d.approval as unknown as Approval, v => v.approvalId, v => v.entityVersion);
                break;
            case 'question.updated':
                requireValue(isObject(d.question), 'Invalid question');
                n.questions = upsert(n.questions, d.question as unknown as Question, v => v.questionId, v => v.entityVersion);
                break;
            case 'artifact.available': break; // message carries an immutable reference; never auto-replace an open revision.
            default: throw new ResetRequired('Unknown event ' + e.type);
        }
        n.cursor = e.cursor;
        this.value = n;
        this.seen.set(e.cursor, signature);
        if (this.seen.size > 2048)
            this.seen.delete(this.seen.keys().next().value!);
        return true;
    }
    prepend(items: unknown[], hasMore: boolean) { const next = structuredClone(this.value); for (const item of items) {
        const m = message(item, next.conversation.id);
        next.messages = upsert(next.messages, m, v => v.id, v => v.entityVersion);
    } next.messages.sort((a, b) => BigInt(a.ordinal) < BigInt(b.ordinal) ? -1 : BigInt(a.ordinal) > BigInt(b.ordinal) ? 1 : 0); next.hasMore = hasMore; this.value = next; }
}
export function safeUrl(input: string): string | null { try {
    if (/[\u0000-\u0020]/.test(input))
        return null;
    const u = new URL(input);
    return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password ? u.href : null;
}
catch {
    return null;
} }
