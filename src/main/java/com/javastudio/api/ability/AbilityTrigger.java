package com.javastudio.api.ability;

/**
 * Các loại kích hoạt (Trigger) cho kỹ năng vật phẩm hoặc vũ khí tùy biến.
 */
public enum AbilityTrigger {
    RIGHT_CLICK,        // Click chuột phải (vào không khí hoặc block)
    LEFT_CLICK,         // Click chuột trái (vào không khí hoặc block)
    SHIFT_RIGHT_CLICK,  // Giữ Shift + Click chuột phải
    SHIFT_LEFT_CLICK,   // Giữ Shift + Click chuột trái
    HIT_ENTITY,         // Chém trúng thực thể (Quái vật / Người chơi)
    SNEAK               // Bắt đầu ngồi xổm (Shift)
}
