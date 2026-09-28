import { useState, useMemo, useCallback } from 'react';
import { ReactFlow, Background, Controls, MiniMap, applyNodeChanges, type Node, type NodeChange, type Edge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { Artifact, Selection } from '../core/protocol';
interface GraphNode {
    id: string;
    label: string;
    type: string;
    position: {
        x: number;
        y: number;
    };
    data?: Record<string, unknown>;
}
export default function Graph({ artifact, quote }: {
    artifact: Artifact;
    quote: (s: Selection) => void;
}) {
    const source = artifact.content.nodes as GraphNode[];
    const [nodes, setNodes] = useState<Node[]>(() => source.map(n => ({ id: n.id, position: n.position, type: n.type === 'start' ? 'input' : n.type === 'end' ? 'output' : 'default', data: { label: n.label } })));
    const edges = useMemo<Edge[]>(() => (artifact.content.edges as Edge[]).map(e => ({ id: e.id, source: e.source, target: e.target, animated: false })), [artifact]);
    const [selected, setSelected] = useState<string[]>([]);
    const onChanges = useCallback((changes: NodeChange[]) => setNodes(old => applyNodeChanges(changes, old)), []);
    return <div className="graph-view"><div className="renderer-toolbar"><span>拖动只改变本次查看布局，不发布业务流程。</span><button disabled={!selected.length} onClick={() => quote({ type: 'graph_nodes', nodeIds: selected })}>引用 {selected.length} 个节点</button></div><div className="graph-canvas"><ReactFlow nodes={nodes} edges={edges} onNodesChange={onChanges} onSelectionChange={({ nodes }) => setSelected(nodes.map(n => n.id))} nodesConnectable={false} edgesReconnectable={false} deleteKeyCode={null} fitView minZoom={0.15} maxZoom={2} colorMode="dark"><Background /><Controls showInteractive={false}/><MiniMap /></ReactFlow></div>{selected.length === 1 && <div className="node-detail"><strong>节点详情</strong><pre>{JSON.stringify(source.find(n => n.id === selected[0]), null, 2)}</pre></div>}</div>;
}
