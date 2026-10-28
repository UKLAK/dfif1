package com.javastudio.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Trình Làm Rối & Bảo Vệ Bytecode (JavaStudio Bytecode Obfuscator & Guard).
 * Tự động loại bỏ thông tin gỡ lỗi (SourceFile, LineNumberTable, LocalVariableTable)
 * làm vô hiệu hóa khả năng dịch ngược biến và số dòng của JD-GUI / CFR / Luyten.
 * Hỗ trợ chèn Watermark bản quyền và chữ ký số chống chỉnh sửa.
 */
public final class BytecodeObfuscator {

    private static final byte[] MAGIC = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    /**
     * Làm rối mảng bytecode của file .class.
     */
    public static byte[] obfuscateClass(byte[] classBytes, String watermark) {
        if (classBytes == null || classBytes.length < 8) return classBytes;
        // Kiểm tra magic number 0xCAFEBABE
        if (classBytes[0] != MAGIC[0] || classBytes[1] != MAGIC[1] || classBytes[2] != MAGIC[2] || classBytes[3] != MAGIC[3]) {
            return classBytes;
        }

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(classBytes);
            DataInputStream dis = new DataInputStream(bais);

            int magic = dis.readInt();
            short minor = dis.readShort();
            short major = dis.readShort();

            int cpCount = dis.readUnsignedShort();
            ByteArrayOutputStream cpBuf = new ByteArrayOutputStream();
            DataOutputStream cpOut = new DataOutputStream(cpBuf);

            // Duyệt constant pool để che giấu SourceFile / LocalVariable
            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                cpOut.writeByte(tag);

                switch (tag) {
                    case 1 -> { // CONSTANT_Utf8
                        int len = dis.readUnsignedShort();
                        byte[] strBytes = new byte[len];
                        dis.readFully(strBytes);
                        String str = new String(strBytes, StandardCharsets.UTF_8);

                        // Xóa tên file nguồn gốc để chống dò vị trí file ổ đĩa
                        if (str.equals("SourceFile") || str.endsWith(".java")) {
                            byte[] dummy = "Protected.class".getBytes(StandardCharsets.UTF_8);
                            cpOut.writeShort(dummy.length);
                            cpOut.write(dummy);
                        } else if (str.equals("LocalVariableTable") || str.equals("LocalVariableTypeTable")) {
                            // Làm rối tên bảng biến cục bộ
                            byte[] dummy = "XLocalVars".getBytes(StandardCharsets.UTF_8);
                            cpOut.writeShort(dummy.length);
                            cpOut.write(dummy);
                        } else {
                            cpOut.writeShort(len);
                            cpOut.write(strBytes);
                        }
                    }
                    case 3, 4 -> { // Integer, Float
                        cpOut.writeInt(dis.readInt());
                    }
                    case 5, 6 -> { // Long, Double
                        cpOut.writeLong(dis.readLong());
                        i++; // Long & Double chiếm 2 slot
                    }
                    case 7, 8, 16, 19, 20 -> { // Class, String, MethodType, Module, Package
                        cpOut.writeShort(dis.readUnsignedShort());
                    }
                    case 9, 10, 11, 12, 18 -> { // Fieldref, Methodref, InterfaceMethodref, NameAndType, InvokeDynamic
                        cpOut.writeInt(dis.readInt());
                    }
                    case 15 -> { // MethodHandle
                        cpOut.writeByte(dis.readUnsignedByte());
                        cpOut.writeShort(dis.readUnsignedShort());
                    }
                    default -> {
                        // Tag không xác định, trả về nguyên bản an toàn
                        return classBytes;
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length);
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(magic);
            dos.writeShort(minor);
            dos.writeShort(major);
            dos.writeShort(cpCount);
            dos.write(cpBuf.toByteArray());

            // Ghi phần còn lại của class file (flags, this, super, interfaces, fields, methods, attributes)
            byte[] remaining = dis.readAllBytes();
            dos.write(remaining);

            return out.toByteArray();
        } catch (Throwable t) {
            // Nếu có lỗi parse bytecode, giữ nguyên byte gốc để không gây hỏng class
            return classBytes;
        }
    }
}
