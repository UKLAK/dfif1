package com.javastudio.api;

@FunctionalInterface
public interface DynamicScript {

    Object execute(JavaContext ctx) throws Throwable;
}
