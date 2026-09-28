package io.reimu.core;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.function.Function;
import static io.reimu.core.Domain.*;

/** Offline test/local reference store. Production adapter is JdbcStore. Never import untrusted snapshots. */
public final class FileStore implements Store {
    private State state; private final Path path;private final FileChannel channel;private final FileLock lock;
    public FileStore(Path path) throws IOException {
        this.path=path.toAbsolutePath();Files.createDirectories(this.path.getParent());
        channel=FileChannel.open(this.path.resolveSibling(this.path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try { lock=channel.tryLock(); if(lock==null)throw new IOException("另一个进程正在使用存储"); }
        catch(IOException|RuntimeException e){channel.close();throw e;}
        try { state=Files.exists(path)?Data.decode(Files.readAllBytes(path),State.class):new State(); }
        catch(RuntimeException|IOException e){lock.release();channel.close();throw e;}
    }
    public synchronized <T>T read(Function<State,T>a){return a.apply(Data.copy(state));}
    public synchronized <T>T write(Function<State,T>a){
        State next=Data.copy(state);T result=a.apply(next);byte[] bytes=Data.encode(next);
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try {
            try(var out=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
                var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);
            }
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            // No silent non-atomic fallback: unsupported filesystems fail the commit.
            state=Data.decode(bytes,State.class);return result;
        }catch(IOException e){throw new IllegalStateException("事务快照提交失败",e);}
    }
    public synchronized void close() throws IOException {lock.release();channel.close();}
}
