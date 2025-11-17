package org.example.db;

public record OperationResult(boolean success, String message) {
    public static OperationResult ok(String message) {
        return new OperationResult(true, message);
    }

    public static OperationResult error(String message) {
        return new OperationResult(false, message);
    }
}

