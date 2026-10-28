package com.javastudio.api;

/**
 * Interface cho các script Java chạy độc lập.
 */
@FunctionalInterface
public interface DynamicScript {

    /**
     * Thực thi script với context môi trường được cung cấp.
     *
     * @param ctx Context chứa player, sender, server, plugin,...
     * @return Kết quả trả về (nếu có, sẽ hiển thị ra chat nếu khác null)
     * @throws Throwable Ngoại lệ xảy ra trong quá trình chạy
     */
    Object execute(JavaContext ctx) throws Throwable;
}
