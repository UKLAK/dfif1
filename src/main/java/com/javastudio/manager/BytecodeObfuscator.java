package com.javastudio.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class BytecodeObfuscator {

    private static final byte[] MAGIC = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    public static byte[] obfuscateClass(byte[] classBytes, String watermark) {
        if (classBytes == null || classBytes.length < 8) return classBytes;
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

            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                cpOut.writeByte(tag);

                switch (tag) {
                    case 1 -> {
                        int len = dis.readUnsignedShort();
                        byte[] strBytes = new byte[len];
                        dis.readFully(strBytes);
                        String str = new String(strBytes, StandardCharsets.UTF_8);

                        if (str.equals("SourceFile") || str.endsWith(".java")) {
                            byte[] dummy = "Protected.class".getBytes(StandardCharsets.UTF_8);
                            cpOut.writeShort(dummy.length);
                            cpOut.write(dummy);
                        } else if (str.equals("LocalVariableTable") || str.equals("LocalVariableTypeTable")) {
                            byte[] dummy = "XLocalVars".getBytes(StandardCharsets.UTF_8);
                            cpOut.writeShort(dummy.length);
                            cpOut.write(dummy);
                        } else {
                            cpOut.writeShort(len);
                            cpOut.write(strBytes);
                        }
                    }
                    case 3, 4 -> {
                        cpOut.writeInt(dis.readInt());
                    }
                    case 5, 6 -> {
                        cpOut.writeLong(dis.readLong());
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> {
                        cpOut.writeShort(dis.readUnsignedShort());
                    }
                    case 9, 10, 11, 12, 18 -> {
                        cpOut.writeInt(dis.readInt());
                    }
                    case 15 -> {
                        cpOut.writeByte(dis.readUnsignedByte());
                        cpOut.writeShort(dis.readUnsignedShort());
                    }
                    default -> {
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

            byte[] remaining = dis.readAllBytes();
            dos.write(remaining);

            return out.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }
}
