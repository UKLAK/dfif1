package com.javastudio.pipeline;

/**
 * Giao diện Middleware cho hệ thống Event Pipeline.
 * Mỗi middleware nhận một {@link EventContext} và có thể:
 * <ul>
 *     <li>Gọi {@code ctx.next()} để chuyển tiếp đến middleware tiếp theo</li>
 *     <li>Gọi {@code ctx.stop()} để dừng toàn bộ chuỗi middleware</li>
 *     <li>Thêm/đọc dữ liệu vào context qua {@code ctx.set()} và {@code ctx.get()}</li>
 * </ul>
 */
@FunctionalInterface
public interface Middleware {
    /**
     * Xử lý sự kiện trong pipeline middleware.
     *
     * @param ctx EventContext chứa thông tin sự kiện và dữ liệu chia sẻ
     * @throws Throwable Nếu có lỗi, pipeline sẽ cô lập lỗi và tiếp tục
     */
    void handle(EventContext ctx) throws Throwable;
}
