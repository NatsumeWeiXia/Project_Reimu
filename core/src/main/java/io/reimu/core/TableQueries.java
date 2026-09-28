package io.reimu.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static io.reimu.core.Data.*;
import static io.reimu.core.Domain.*;
import static io.reimu.core.Fault.*;
import static io.reimu.core.Workspace.*;

/** Immutable, owner-bound table query snapshots; no model-generated SQL. */
public final class TableQueries {
    private final Store store;
    public TableQueries(Store store){this.store=store;}
    public Map<String,Object> create(Identity who,String aid,int revision,String sortColumn,boolean desc,String filter,int size){
        require(size>=1&&size<=200,422,"PAGE_SIZE","每页需在 1–200 行之间");require(filter==null||filter.length()<=1000,422,"FILTER","筛选内容过长");
        return store.write(s->{Artifact a=artifact(s,who,aid);require(a.kind.equals("table")&&revision>0&&revision<=a.revisions.size(),422,"TABLE_REVISION","表格版本错误");var c=a.revisions.get(revision-1).content;
            TableQuery q=new TableQuery();q.scope=who.scope();q.artifactId=aid;q.revision=revision;q.pageSize=size;q.expiresAt=Instant.now().plusSeconds(1800).toString();
            String term=filter==null?"":filter.toLowerCase(Locale.ROOT);
            for(Object row:list(c.get("rows"))){var r=object(row);if(object(r.get("values")).values().stream().anyMatch(v->v!=null&&v.toString().toLowerCase(Locale.ROOT).contains(term)))q.rows.add(r);}
            if(sortColumn!=null&&!sortColumn.isBlank()){
                Map<String,Object> column=list(c.get("columns")).stream().map(Data::object).filter(x->sortColumn.equals(x.get("id"))).findFirst().orElseThrow(()->new Fault(422,"SORT_COLUMN","排序列不存在"));
                String type=(String)column.get("dataType");Comparator<Map<String,Object>> comparator=(x,y)->compare(object(x.get("values")).get(sortColumn),object(y.get("values")).get(sortColumn),type);
                if(desc)comparator=comparator.reversed();q.rows.sort(comparator.thenComparing(x->(String)x.get("rowId")));
            }
            s.tableQueries.values().removeIf(old->Instant.parse(old.expiresAt).isBefore(Instant.now()));
            require(s.tableQueries.values().stream().filter(old->old.scope.equals(who.scope())).count()<100,429,"QUERY_LIMIT","查询快照过多，请稍后重试");s.tableQueries.put(q.id,q);
            return map("queryId",q.id,"querySnapshotId",q.id,"artifactId",aid,"revision",revision,"asOf",q.asOf,"expiresAt",q.expiresAt);
        });
    }
    private static int compare(Object a,Object b,String type){if(a==null)return b==null?0:1;if(b==null)return -1;
        if(type.equals("decimalString")||type.equals("integerString"))return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        if(type.equals("boolean"))return Boolean.compare((Boolean)a,(Boolean)b);return a.toString().compareTo(b.toString());}
    public Map<String,Object> rows(Identity who,String aid,String qid,String cursor){return store.read(s->{var q=query(s,who,aid,qid);int index=0;if(cursor!=null&&!cursor.isBlank())try{index=Integer.parseInt(cursor,36);}catch(NumberFormatException e){throw new Fault(422,"CURSOR","分页游标错误");}
        require(index>=0&&index<=q.rows.size(),422,"CURSOR","分页游标越界");int end=Math.min(index+q.pageSize,q.rows.size());return map("queryId",q.id,"querySnapshotId",q.id,"artifactId",aid,"revision",q.revision,"asOf",q.asOf,"rows",q.rows.subList(index,end),"totalRows",Integer.toString(q.rows.size()),"nextCursor",end<q.rows.size()?Integer.toString(end,36):null);});}
    public String csv(Identity who,String aid,String qid){return store.read(s->{var q=query(s,who,aid,qid);var a=artifact(s,who,aid);var columns=list(a.revisions.get(q.revision-1).content.get("columns")).stream().map(Data::object).toList();StringBuilder b=new StringBuilder("\ufeff");
        b.append(String.join(",",columns.stream().map(c->cell((String)c.get("title"))).toList())).append("\r\n");
        for(var row:q.rows){var values=object(row.get("values"));b.append(String.join(",",columns.stream().map(c->cell(Objects.toString(values.get(c.get("id")),""))).toList())).append("\r\n");}return b.toString();});}
    public static String cell(String s){String stripped=s.stripLeading();if(!stripped.isEmpty()&&"=+-@\t\r\n".indexOf(stripped.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private static TableQuery query(State s,Identity who,String aid,String qid){artifact(s,who,aid);TableQuery q=s.tableQueries.get(qid);visible(q,q!=null&&q.scope.equals(who.scope())&&q.artifactId.equals(aid));require(Instant.parse(q.expiresAt).isAfter(Instant.now()),410,"QUERY_EXPIRED","表格查询已过期");return q;}
}
