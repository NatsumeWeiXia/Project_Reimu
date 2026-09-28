import { useEffect, useMemo, useState, useRef } from 'react';
import { useReactTable, getCoreRowModel, flexRender, type ColumnDef } from '@tanstack/react-table';
import { client } from '../core/api';
import { safeUrl, type Artifact, type Selection } from '../core/protocol';
interface Row {
    rowId: string;
    values: Record<string, string | boolean | null>;
}
interface Column {
    id: string;
    title: string;
    dataType: string;
}
interface Page {
    queryId: string;
    querySnapshotId: string;
    revision: number;
    rows: Row[];
    nextCursor?: string;
    totalRows: string;
    asOf: string;
}
export default function DataTable({ artifact, quote }: {
    artifact: Artifact;
    quote: (s: Selection) => void;
}) {
    const generation = useRef(0);
    const defs = artifact.content.columns as Column[];
    const [filter, setFilter] = useState(''), [search, setSearch] = useState(''), [sort, setSort] = useState(''), [desc, setDesc] = useState(false), [query, setQuery] = useState(''), [page, setPage] = useState<Page>(), [selected, setSelected] = useState<string[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false);
    useEffect(() => { generation.current++; const a = new AbortController(); setError(''); setBusy(true); setPage(undefined); setSelected([]); setQuery(''); client.post<{
        queryId: string;
    }>(`/api/v1/artifacts/${artifact.artifactId}/revisions/${artifact.revision}/table-queries`, { sortColumn: sort, descending: desc, filter: search, pageSize: 50 }, a.signal).then(async (q) => { const p = await client.request<Page>(`/api/v1/artifacts/${artifact.artifactId}/table-queries/${q.queryId}/rows`, { signal: a.signal }); if (!a.signal.aborted) {
        setQuery(q.queryId);
        setPage(p);
    } }).catch(e => { if (!a.signal.aborted)
        setError(e.message); }).finally(() => { if (!a.signal.aborted)
        setBusy(false); }); return () => { generation.current++; a.abort(); }; }, [artifact.artifactId, artifact.revision, sort, desc, search]);
    const columns = useMemo<ColumnDef<Row>[]>(() => [{ id: 'selection', header: '选择', cell: ({ row }) => <input aria-label={'选择行 ' + row.original.rowId} type="checkbox" checked={selected.includes(row.original.rowId)} onChange={() => setSelected(old => old.includes(row.original.rowId) ? old.filter(x => x !== row.original.rowId) : [...old, row.original.rowId])}/> }, ...defs.map(c => ({ id: c.id, header: () => <button className="table-sort" onClick={() => { if (sort === c.id)
                setDesc(!desc);
            else {
                setSort(c.id);
                setDesc(false);
            } }}>{c.title}{sort === c.id ? (desc ? ' ↓' : ' ↑') : ''}</button>, accessorFn: (r: Row) => r.values[c.id], cell: ({ row }: {
                row: {
                    original: Row;
                };
            }) => { const v = row.original.values[c.id]; if (v == null)
                return <span className="muted">—</span>; if (c.dataType === 'link') {
                const href = safeUrl(String(v));
                return href ? <a href={href} target="_blank" rel="noopener noreferrer">{String(v)}</a> : <span>无效链接</span>;
            } return <span>{String(v)}</span>; } }))], [defs, selected, sort, desc]);
    const table = useReactTable({ data: page?.rows ?? [], columns, getCoreRowModel: getCoreRowModel(), getRowId: r => r.rowId, manualPagination: true, manualSorting: true });
    async function next() { if (!page?.nextCursor || busy)
        return; const current = generation.current; setBusy(true); try {
        const p = await client.request<Page>(`/api/v1/artifacts/${artifact.artifactId}/table-queries/${query}/rows?cursor=${encodeURIComponent(page.nextCursor)}`);
        if (current !== generation.current)
            return;
        if (p.queryId !== query)
            throw new Error('查询快照发生改变');
        setPage(p);
        setSelected([]);
    }
    catch (e) {
        if (current === generation.current)
            setError((e as Error).message);
    }
    finally {
        if (current === generation.current)
            setBusy(false);
    } }
    return <div className="table-view"><div className="renderer-toolbar"><form onSubmit={e => { e.preventDefault(); setSearch(filter); }}><input aria-label="筛选表格" placeholder="筛选内容" value={filter} onChange={e => setFilter(e.target.value)}/><button>筛选</button></form><button disabled={!selected.length || busy} onClick={() => quote({ type: 'table_rows', rowIds: selected, querySnapshotId: query })}>引用 {selected.length} 行</button><button disabled={!query || busy} onClick={() => void client.download(`/api/v1/artifacts/${artifact.artifactId}/table-queries/${query}/download`, 'table.csv').catch(e => setError(e.message))}>导出 CSV</button></div>{error && <p role="alert" className="notice error">{error}</p>}<div className="data-table-scroll"><table><thead>{table.getHeaderGroups().map(h => <tr key={h.id}>{h.headers.map(c => <th key={c.id}>{flexRender(c.column.columnDef.header, c.getContext())}</th>)}</tr>)}</thead><tbody>{table.getRowModel().rows.map(r => <tr key={r.id}>{r.getVisibleCells().map(c => <td key={c.id}>{flexRender(c.column.columnDef.cell, c.getContext())}</td>)}</tr>)}</tbody></table>{busy && <p>正在读取查询快照…</p>}</div><div className="renderer-toolbar"><span>共 {page?.totalRows ?? '—'} 行 · {page?.asOf ? new Date(page.asOf).toLocaleString() : ''}</span><button disabled={!page?.nextCursor || busy} onClick={() => void next()}>下一页</button></div></div>;
}
