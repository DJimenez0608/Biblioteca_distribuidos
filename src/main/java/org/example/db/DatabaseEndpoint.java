package org.example.db;

public record DatabaseEndpoint(String name, String url, String user, String password) {
    @Override
    public String toString() {
        return name + " (" + url + ")";
    }
}

