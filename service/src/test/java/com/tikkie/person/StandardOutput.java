package com.tikkie.person;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.function.Supplier;

// Lambda ships both the JSON log lines and the EMF metrics on stdout, so tests read them from there.
public final class StandardOutput {

    public record Captured<T>(T result, String output) {
    }

    private StandardOutput() {
    }

    public static <T> Captured<T> capture(Supplier<T> action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, UTF_8));
        try {
            T result = action.get();
            return new Captured<>(result, buffer.toString(UTF_8));
        } finally {
            System.setOut(original);
        }
    }
}
