package io.reimu.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Validates JSON-shaped values; canonical encoding is used only for idempotency hashes. */
public final class Data {
    private Data() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }
    public static String text(Object value, int max, String field) {
        Fault.require(value instanceof String,422,"VALIDATION",field+" 必须是字符串");
        String s=(String)value;
        Fault.require(!s.isBlank() && s.length()<=max,422,"VALIDATION",field+" 长度不合法");
        return s;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object v) {
        Fault.require(v instanceof Map<?,?>,422,"VALIDATION","需要对象");
        Map<?,?> m=(Map<?,?>)v;
        Fault.require(m.keySet().stream().allMatch(String.class::isInstance),422,"VALIDATION","对象键必须为字符串");
        return (Map<String,Object>)v;
    }
    public static List<?> list(Object v) { Fault.require(v instanceof List<?>,422,"VALIDATION","需要数组"); return (List<?>)v; }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) if(pairs[i+1]!=null) m.put((String)pairs[i],pairs[i+1]);
        return m;
    }
    public static String hash(Object v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(v).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String canonical(Object v) { return canonical(v,0); }
    private static String canonical(Object v,int depth) {
        Fault.require(depth<=32,422,"VALIDATION","内容嵌套过深");
        if(v==null) return "null";
        if(v instanceof String s) {
            StringBuilder b=new StringBuilder("\"");
            for(char c:s.toCharArray()) switch(c) {
                case '\\' -> b.append("\\\\"); case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
            }
            return b.append('"').toString();
        }
        if(v instanceof Boolean) return v.toString();
        if(v instanceof Number n) {
            Fault.require(Double.isFinite(n.doubleValue()),422,"VALIDATION","无效数值");
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        if(v instanceof Map<?,?>) {
            Map<String,Object> m=object(v); List<String> keys=new ArrayList<>(m.keySet()); Collections.sort(keys);
            return "{"+String.join(",",keys.stream().map(k->canonical(k,depth+1)+":"+canonical(m.get(k),depth+1)).toList())+"}";
        }
        if(v instanceof List<?> l) return "["+String.join(",",l.stream().map(x->canonical(x,depth+1)).toList())+"]";
        throw new Fault(422,"VALIDATION","不支持的内容类型");
    }
    public static byte[] encode(Serializable value) {
        try(var b=new ByteArrayOutputStream();var o=new ObjectOutputStream(b)){o.writeObject(value);o.flush();return b.toByteArray();}
        catch(IOException e){throw new IllegalStateException("内部快照序列化失败",e);}
    }
    public static <T> T decode(byte[] bytes,Class<T> type) {
        try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes))){
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter("maxdepth=60;maxrefs=1000000;maxbytes=67108864;io.reimu.core.*;java.base/*;!*"));
            return type.cast(in.readObject());
        }catch(IOException|ClassNotFoundException e){throw new IllegalStateException("内部快照无法恢复",e);}
    }
    @SuppressWarnings("unchecked") public static <T extends Serializable> T copy(T v) { return (T)decode(encode(v),v.getClass()); }
}
