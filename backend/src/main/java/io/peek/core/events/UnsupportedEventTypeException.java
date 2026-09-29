package io.peek.core.events;

public class UnsupportedEventTypeException extends RuntimeException {
    public UnsupportedEventTypeException(String type) {
        super("Unsupported event type: " + type);
    }
}
