package com.javastudio.pipeline;

@FunctionalInterface
public interface Middleware {
    void handle(EventContext ctx) throws Throwable;
}
