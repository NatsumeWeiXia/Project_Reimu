package io.reimu.core;
import java.util.function.Function;
import static io.reimu.core.Domain.*;
/** Transactions roll back on every exception and never leak mutable internal state. */
public interface Store extends AutoCloseable {
    <T> T read(Function<State,T> action);
    <T> T write(Function<State,T> action);
    default void close() throws java.io.IOException {}
}
