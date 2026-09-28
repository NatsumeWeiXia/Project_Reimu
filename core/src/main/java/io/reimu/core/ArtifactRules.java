package io.reimu.core;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import static io.reimu.core.Data.*;
import static io.reimu.core.Fault.*;

public final class ArtifactRules {
    private ArtifactRules() {}
    public static void validate(String kind,Map<String,Object> c) {
        require(canonical(c).length()<=2_000_000,413,"ARTIFACT_TOO_LARGE","产物超过 2 MB 字符预算");
        switch(kind) {
            case "reactflow" -> graph(c);
            case "table" -> table(c);
            case "markdown" -> {
                var blocks=list(c.get("blocks"));require(!blocks.isEmpty()&&blocks.size()<=2000,422,"VALIDATION","段落数量不合法");
                Set<String> ids=new HashSet<>();for(Object b:blocks){var m=object(b);unique(ids,m.get("blockId"));text(m.get("markdown"),500_000,"markdown");}
            }
            case "webpage" -> {
                require("external_only".equals(c.get("mode")),422,"FRAME_POLICY","首版仅允许外链，不接受模型自行授权 iframe");
                safeUrl(text(c.get("url"),2048,"url"));
            }
            default -> throw new Fault(422,"UNKNOWN_ARTIFACT_KIND","不支持此产物类型");
        }
    }
    public static void safeUrl(String url) {
        try {
            URI u=URI.create(url);
            require(Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getRawUserInfo()==null,
                    422,"UNSAFE_URL","仅允许不含凭据的 HTTP/HTTPS 链接");
            require(!url.chars().anyMatch(x->x<32||x==127),422,"UNSAFE_URL","链接包含控制字符");
        }catch(IllegalArgumentException e){throw new Fault(422,"UNSAFE_URL","链接格式错误");}
    }
    private static void unique(Set<String> ids,Object id){require(ids.add(text(id,100,"id")),422,"DUPLICATE_ID","重复标识");}
    private static void graph(Map<String,Object> c){
        require(Set.of("explanatory","workflow_draft","run_projection").contains(c.get("purpose")),422,"VALIDATION","图用途错误");
        var nodes=list(c.get("nodes"));var edges=list(c.get("edges"));
        require(nodes.size()<=500&&edges.size()<=1500,413,"GRAPH_TOO_LARGE","请拆分流程图");
        Set<String> ids=new HashSet<>(),eids=new HashSet<>();
        for(Object n:nodes){var m=object(n);unique(ids,m.get("id"));text(m.get("label"),200,"label");
            require(Set.of("start","end","agent","step","decision").contains(m.get("type")),422,"NODE_TYPE","节点类型未注册");
            if(m.containsKey("position")){var p=object(m.get("position"));for(String k:List.of("x","y"))require(p.get(k) instanceof Number&&Double.isFinite(((Number)p.get(k)).doubleValue())&&Math.abs(((Number)p.get(k)).doubleValue())<100000,422,"POSITION","坐标错误");}}
        for(Object e:edges){var m=object(e);unique(eids,m.get("id"));require(ids.contains(m.get("source"))&&ids.contains(m.get("target")),422,"EDGE_ENDPOINT","边端点不存在");}
    }
    private static void table(Map<String,Object> c){
        var columns=list(c.get("columns"));var rows=list(c.get("rows"));
        require(columns.size()>=1&&columns.size()<=100&&rows.size()<=10000,413,"TABLE_LIMIT","表格超出首版容量");
        Set<String> ids=new HashSet<>(),rids=new HashSet<>();Map<String,String> types=new HashMap<>();
        for(Object col:columns){var m=object(col);unique(ids,m.get("id"));text(m.get("title"),100,"title");
            String t=text(m.get("dataType"),30,"dataType");require(Set.of("string","integerString","decimalString","boolean","date","datetime","link").contains(t),422,"COLUMN_TYPE","不支持的列类型");types.put((String)m.get("id"),t);}
        for(Object row:rows){var m=object(row);unique(rids,m.get("rowId"));var values=object(m.get("values"));require(ids.containsAll(values.keySet()),422,"COLUMN_UNKNOWN","未定义的列");
            values.forEach((key,value)->{
                if(value==null)return;String t=types.get(key);
                if(t.equals("boolean")){require(value instanceof Boolean,422,"VALUE_TYPE","布尔值错误");return;}
                require(value instanceof String&&((String)value).length()<=10000,422,"VALUE_TYPE","列值必须是字符串；高精度数值不能转 Number");
                if(t.equals("integerString"))require(((String)value).matches("-?[0-9]{1,80}"),422,"VALUE_TYPE","整数格式错误");
                if(t.equals("decimalString"))try{new BigDecimal((String)value);}catch(NumberFormatException e){throw new Fault(422,"VALUE_TYPE","小数格式错误");}
                if(t.equals("link"))safeUrl((String)value);
            });}
    }
    public static void selection(Map<String,Object> content,String kind,Map<String,Object> selection){
        String type=text(selection.get("type"),30,"selection.type");if(type.equals("whole"))return;
        String field,source,idField;
        switch(type){
            case "graph_nodes" -> {require(kind.equals("reactflow"),422,"REFERENCE_TYPE","引用类型不匹配");field="nodeIds";source="nodes";idField="id";}
            case "markdown_blocks" -> {require(kind.equals("markdown"),422,"REFERENCE_TYPE","引用类型不匹配");field="blockIds";source="blocks";idField="blockId";}
            case "table_rows" -> {require(kind.equals("table"),422,"REFERENCE_TYPE","引用类型不匹配");field="rowIds";source="rows";idField="rowId";}
            default -> throw new Fault(422,"REFERENCE_TYPE","未知选择类型");
        }
        Set<Object> allowed=new HashSet<>();for(Object item:list(content.get(source)))allowed.add(object(item).get(idField));
        List<?> selected=list(selection.get(field));require(!selected.isEmpty()&&selected.size()<=100&&allowed.containsAll(selected),422,"REFERENCE_SELECTION","选择不存在或过多");
    }
}
