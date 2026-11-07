package com.javastudio.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public final class LoopWatchdog {

    public static final long DEFAULT_MAX_LOOP_MS = 3000L;
    private static final String STEP_CALL = " com.javastudio.security.LoopWatchdog.step(); ";

    private static final Set<String> NON_RETURN_TYPE_WORDS = Set.of(
            "new", "class", "interface", "enum", "record", "return", "throw", "throws",
            "case", "else", "do", "if", "while", "for", "switch", "try", "catch", "finally",
            "public", "private", "protected", "static", "final", "synchronized", "abstract",
            "native", "strictfp", "default", "sealed", "non-sealed", "transient", "volatile",
            "package", "import", "extends", "implements", "permits", "assert", "yield"
    );

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "for", "while", "do", "switch", "try", "catch", "finally", "synchronized",
            "class", "interface", "enum", "record"
    );

    private static volatile boolean enabled = true;
    private static volatile long maxLoopDurationMs = DEFAULT_MAX_LOOP_MS;

    private LoopWatchdog() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static long getMaxLoopDurationMs() {
        return maxLoopDurationMs;
    }

    public static void setMaxLoopDurationMs(long ms) {
        maxLoopDurationMs = ms;
    }

    public static void step() {
        GasMeter.consume(1);
    }

    public static boolean checkCondition() {
        GasMeter.consume(1);
        return true;
    }

    public static void check(long startNano, long maxMs, String loopLabel) {
        if (!enabled) return;
        GasMeter.consume(1);
        boolean isMain = false;
        try {
            isMain = org.bukkit.Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {}

        long allowedMs = isMain ? Math.min(maxMs, com.javastudio.pipeline.ExecutionWatchdog.getMainThreadLimitMs()) : maxMs;
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        if (elapsedMs > allowedMs) {
            throw new LoopTimeoutException("Phát hiện vòng lặp [" + loopLabel + "] chiếm dụng " + (isMain ? "Main Thread" : "Thread") + " quá lâu: " + elapsedMs + "ms > " + allowedMs + "ms! Đã tự động ngắt để bảo vệ Server.");
        }
    }

    public static String instrument(String sourceCode) {
        if (!enabled || sourceCode == null || sourceCode.isBlank()) {
            return sourceCode;
        }
        if (sourceCode.contains("LoopWatchdog.step()") || sourceCode.contains("LoopWatchdog.check(") || sourceCode.contains("GasMeter.tick()")) {
            return sourceCode;
        }
        try {
            return transformLexical(sourceCode);
        } catch (Throwable ignored) {
            return sourceCode;
        }
    }

    private static String transformLexical(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len + 256);
        Deque<Boolean> doBraceStack = new ArrayDeque<>();
        int i = 0;

        while (i < len) {
            int skipped = skipLiteralOrComment(s, i);
            if (skipped > i) {
                out.append(s, i, skipped);
                i = skipped;
                continue;
            }

            char c = s.charAt(i);

            if (c == '{') {
                out.append('{');
                doBraceStack.push(Boolean.FALSE);
                i++;
                continue;
            }

            if (c == '}') {
                out.append('}');
                boolean wasDoBlock = !doBraceStack.isEmpty() && Boolean.TRUE.equals(doBraceStack.pop());
                i++;
                if (wasDoBlock) {
                    int wsEnd = skipWhitespaceAndComments(s, i);
                    if (matchesKeyword(s, wsEnd, "while")) {
                        int openParen = skipWhitespaceAndComments(s, wsEnd + 5);
                        if (openParen < len && s.charAt(openParen) == '(') {
                            int closeParen = findBalancedParenEnd(s, openParen);
                            if (closeParen != -1) {
                                out.append(s, i, closeParen + 1);
                                i = closeParen + 1;
                            }
                        }
                    }
                }
                continue;
            }

            if (Character.isJavaIdentifierStart(c) && (i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1)))) {
                int idEnd = scanIdentifierEnd(s, i);
                String word = s.substring(i, idEnd);

                if (word.equals("do") && !isPrecededByDot(s, i)) {
                    int afterDo = skipWhitespaceAndComments(s, idEnd);
                    if (afterDo < len && s.charAt(afterDo) == '{') {
                        out.append(s, i, afterDo + 1);
                        out.append(STEP_CALL);
                        doBraceStack.push(Boolean.TRUE);
                        i = afterDo + 1;
                        continue;
                    }
                    int simpleEnd = findSimpleStatementEnd(s, afterDo);
                    if (simpleEnd != -1) {
                        int afterStmt = skipWhitespaceAndComments(s, simpleEnd + 1);
                        if (matchesKeyword(s, afterStmt, "while")) {
                            int openP = skipWhitespaceAndComments(s, afterStmt + 5);
                            if (openP < len && s.charAt(openP) == '(') {
                                int closeP = findBalancedParenEnd(s, openP);
                                if (closeP != -1) {
                                    out.append("do {").append(STEP_CALL);
                                    out.append(s, afterDo, simpleEnd + 1);
                                    out.append(" } ");
                                    out.append(s, afterStmt, closeP + 1);
                                    i = closeP + 1;
                                    continue;
                                }
                            }
                        }
                    }
                }

                if ((word.equals("while") || word.equals("for")) && !isPrecededByDot(s, i)) {
                    int openParen = skipWhitespaceAndComments(s, idEnd);
                    if (openParen < len && s.charAt(openParen) == '(') {
                        int closeParen = findBalancedParenEnd(s, openParen);
                        if (closeParen != -1) {
                            int afterParen = skipWhitespaceAndComments(s, closeParen + 1);
                            if (afterParen < len) {
                                char nextChar = s.charAt(afterParen);
                                if (nextChar == '{') {
                                    out.append(s, i, afterParen + 1);
                                    out.append(STEP_CALL);
                                    doBraceStack.push(Boolean.FALSE);
                                    i = afterParen + 1;
                                    continue;
                                } else if (nextChar == ';') {
                                    out.append(s, i, closeParen + 1);
                                    out.append(" {").append(STEP_CALL).append("}");
                                    i = afterParen + 1;
                                    continue;
                                } else {
                                    int simpleEnd = findSimpleStatementEnd(s, afterParen);
                                    if (simpleEnd != -1) {
                                        out.append(s, i, closeParen + 1);
                                        out.append(" {").append(STEP_CALL);
                                        out.append(s, afterParen, simpleEnd + 1);
                                        out.append(" }");
                                        i = simpleEnd + 1;
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                }

                if (!CONTROL_KEYWORDS.contains(word) && !NON_RETURN_TYPE_WORDS.contains(word) && !isPrecededByDot(s, i)) {
                    int openParen = skipWhitespaceAndComments(s, idEnd);
                    if (openParen < len && s.charAt(openParen) == '(' && hasMethodReturnTypeBefore(s, i)) {
                        int closeParen = findBalancedParenEnd(s, openParen);
                        if (closeParen != -1) {
                            int braceIdx = skipOptionalThrowsToBrace(s, closeParen + 1);
                            if (braceIdx != -1 && !startsWithConstructorDelegation(s, braceIdx + 1)) {
                                out.append(s, i, braceIdx + 1);
                                out.append(STEP_CALL);
                                doBraceStack.push(Boolean.FALSE);
                                i = braceIdx + 1;
                                continue;
                            }
                        }
                    }
                }

                out.append(word);
                i = idEnd;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    private static boolean hasMethodReturnTypeBefore(String s, int methodNameStart) {
        int p = methodNameStart - 1;
        while (p >= 0 && Character.isWhitespace(s.charAt(p))) {
            p--;
        }
        if (p < 0) return false;
        char last = s.charAt(p);
        if (last == ']' || last == '>') {
            return true;
        }
        if (!Character.isJavaIdentifierPart(last)) {
            return false;
        }
        int wordEnd = p + 1;
        while (p >= 0 && Character.isJavaIdentifierPart(s.charAt(p))) {
            p--;
        }
        String prevWord = s.substring(p + 1, wordEnd);
        if (NON_RETURN_TYPE_WORDS.contains(prevWord) || CONTROL_KEYWORDS.contains(prevWord)) {
            return false;
        }
        int q = p;
        while (q >= 0) {
            while (q >= 0 && Character.isWhitespace(s.charAt(q))) q--;
            if (q >= 0 && s.charAt(q) == '.') {
                q--;
                while (q >= 0 && Character.isWhitespace(s.charAt(q))) q--;
                while (q >= 0 && Character.isJavaIdentifierPart(s.charAt(q))) q--;
                continue;
            }
            break;
        }
        if (q >= 0 && s.charAt(q) == '@') {
            return false;
        }
        return true;
    }

    private static int skipOptionalThrowsToBrace(String s, int idx) {
        int len = s.length();
        int p = skipWhitespaceAndComments(s, idx);
        if (p >= len) return -1;
        if (s.charAt(p) == '{') return p;
        if (!matchesKeyword(s, p, "throws")) return -1;
        p = skipWhitespaceAndComments(s, p + 6);
        while (p < len) {
            if (!Character.isJavaIdentifierStart(s.charAt(p))) return -1;
            p = scanIdentifierEnd(s, p);
            p = skipWhitespaceAndComments(s, p);
            while (p < len && s.charAt(p) == '.') {
                p = skipWhitespaceAndComments(s, p + 1);
                if (p >= len || !Character.isJavaIdentifierStart(s.charAt(p))) return -1;
                p = scanIdentifierEnd(s, p);
                p = skipWhitespaceAndComments(s, p);
            }
            if (p < len && s.charAt(p) == ',') {
                p = skipWhitespaceAndComments(s, p + 1);
                continue;
            }
            break;
        }
        return (p < len && s.charAt(p) == '{') ? p : -1;
    }

    private static boolean startsWithConstructorDelegation(String s, int afterBrace) {
        int p = skipWhitespaceAndComments(s, afterBrace);
        if (matchesKeyword(s, p, "super")) {
            int q = skipWhitespaceAndComments(s, p + 5);
            return q < s.length() && s.charAt(q) == '(';
        }
        if (matchesKeyword(s, p, "this")) {
            int q = skipWhitespaceAndComments(s, p + 4);
            return q < s.length() && s.charAt(q) == '(';
        }
        return false;
    }

    private static int findSimpleStatementEnd(String s, int start) {
        int len = s.length();
        int p = skipWhitespaceAndComments(s, start);
        if (p >= len) return -1;
        if (s.charAt(p) == ';') return p;
        int parenDepth = 0;
        int bracketDepth = 0;

        while (p < len) {
            int skipped = skipLiteralOrComment(s, p);
            if (skipped > p) {
                p = skipped;
                continue;
            }
            char ch = s.charAt(p);
            if (ch == '{' || ch == '}') {
                return -1;
            }
            if (ch == '(') {
                parenDepth++;
                p++;
                continue;
            }
            if (ch == ')') {
                parenDepth--;
                if (parenDepth < 0) return -1;
                p++;
                continue;
            }
            if (ch == '[') {
                bracketDepth++;
                p++;
                continue;
            }
            if (ch == ']') {
                bracketDepth--;
                if (bracketDepth < 0) return -1;
                p++;
                continue;
            }
            if (ch == ':' && parenDepth == 0 && bracketDepth == 0) {
                return -1;
            }
            if (ch == ';' && parenDepth == 0 && bracketDepth == 0) {
                return p;
            }
            if (parenDepth == 0 && bracketDepth == 0 && Character.isJavaIdentifierStart(ch)
                    && (p == 0 || !Character.isJavaIdentifierPart(s.charAt(p - 1)))) {
                int idEnd = scanIdentifierEnd(s, p);
                String kw = s.substring(p, idEnd);
                if (CONTROL_KEYWORDS.contains(kw)) {
                    return -1;
                }
                p = idEnd;
                continue;
            }
            p++;
        }
        return -1;
    }

    private static int findBalancedParenEnd(String s, int openParenIdx) {
        int len = s.length();
        int depth = 1;
        int p = openParenIdx + 1;
        while (p < len) {
            int skipped = skipLiteralOrComment(s, p);
            if (skipped > p) {
                p = skipped;
                continue;
            }
            char ch = s.charAt(p);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return p;
                }
            }
            p++;
        }
        return -1;
    }

    private static int skipWhitespaceAndComments(String s, int idx) {
        int len = s.length();
        int p = idx;
        while (p < len) {
            if (Character.isWhitespace(s.charAt(p))) {
                p++;
                continue;
            }
            if (p + 1 < len && s.charAt(p) == '/') {
                char next = s.charAt(p + 1);
                if (next == '/' || next == '*') {
                    p = skipLiteralOrComment(s, p);
                    continue;
                }
            }
            break;
        }
        return p;
    }

    private static int skipLiteralOrComment(String s, int idx) {
        int len = s.length();
        if (idx >= len) return idx;
        char c = s.charAt(idx);

        if (c == '"' && idx + 2 < len && s.charAt(idx + 1) == '"' && s.charAt(idx + 2) == '"') {
            int p = idx + 3;
            while (p < len) {
                if (s.charAt(p) == '\\' && p + 1 < len) {
                    p += 2;
                    continue;
                }
                if (p + 2 < len && s.charAt(p) == '"' && s.charAt(p + 1) == '"' && s.charAt(p + 2) == '"') {
                    return p + 3;
                }
                p++;
            }
            return len;
        }

        if (c == '"') {
            int p = idx + 1;
            while (p < len) {
                char ch = s.charAt(p);
                if (ch == '\\' && p + 1 < len) {
                    p += 2;
                    continue;
                }
                if (ch == '"' || ch == '\n') {
                    return p + 1;
                }
                p++;
            }
            return len;
        }

        if (c == '\'') {
            int p = idx + 1;
            while (p < len) {
                char ch = s.charAt(p);
                if (ch == '\\' && p + 1 < len) {
                    p += 2;
                    continue;
                }
                if (ch == '\'' || ch == '\n') {
                    return p + 1;
                }
                p++;
            }
            return len;
        }

        if (c == '/' && idx + 1 < len) {
            char next = s.charAt(idx + 1);
            if (next == '/') {
                int p = idx + 2;
                while (p < len && s.charAt(p) != '\n') {
                    p++;
                }
                return p < len ? p + 1 : len;
            }
            if (next == '*') {
                int p = idx + 2;
                while (p + 1 < len) {
                    if (s.charAt(p) == '*' && s.charAt(p + 1) == '/') {
                        return p + 2;
                    }
                    p++;
                }
                return len;
            }
        }

        return idx;
    }

    private static int scanIdentifierEnd(String s, int start) {
        int p = start;
        int len = s.length();
        while (p < len && Character.isJavaIdentifierPart(s.charAt(p))) {
            p++;
        }
        return p;
    }

    private static boolean matchesKeyword(String s, int idx, String kw) {
        int len = s.length();
        int kwLen = kw.length();
        if (idx < 0 || idx + kwLen > len) return false;
        if (idx > 0 && Character.isJavaIdentifierPart(s.charAt(idx - 1))) return false;
        if (!s.regionMatches(idx, kw, 0, kwLen)) return false;
        return idx + kwLen == len || !Character.isJavaIdentifierPart(s.charAt(idx + kwLen));
    }

    private static boolean isPrecededByDot(String s, int idx) {
        int p = idx - 1;
        while (p >= 0 && Character.isWhitespace(s.charAt(p))) {
            p--;
        }
        return p >= 0 && s.charAt(p) == '.';
    }

    public static class LoopTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LoopTimeoutException(String message) {
            super(message);
        }
    }
}
