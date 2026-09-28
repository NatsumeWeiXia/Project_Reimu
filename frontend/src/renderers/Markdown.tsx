import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { safeUrl, type Artifact, type Selection } from '../core/protocol';
export default function Markdown({ artifact, quote }: {
    artifact: Artifact;
    quote: (s: Selection) => void;
}) {
    const blocks = artifact.content.blocks as {
        blockId: string;
        markdown: string;
    }[];
    const [selected, setSelected] = useState<string[]>([]);
    return <div className="document-view"><div className="renderer-toolbar"><span>选择段落可带着明确版本继续提问</span><button disabled={!selected.length} onClick={() => quote({ type: 'markdown_blocks', blockIds: selected })}>引用 {selected.length} 段</button></div>{blocks.map(b => <section className={'markdown-block ' + (selected.includes(b.blockId) ? 'selected' : '')} key={b.blockId}><label className="block-select"><input type="checkbox" aria-label={'选择段落 ' + b.blockId} checked={selected.includes(b.blockId)} onChange={() => setSelected(old => old.includes(b.blockId) ? old.filter(x => x !== b.blockId) : [...old, b.blockId])}/>选择此段</label><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml components={{ a: ({ href, children }) => { const safe = safeUrl(href ?? ''); return safe ? <a href={safe} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>; }, img: ({ alt }) => <span className="muted">[外部图片未自动加载：{alt}]</span> }}>{b.markdown}</ReactMarkdown></section>)}</div>;
}
