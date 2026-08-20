package com.edwares;

public class StreamFormatter {

    public static class FormatState {
        public int indentLevel = 0;
        public boolean inString = false;
        public boolean escapeNext = false;
        public char lastAppended = 0;

        // For XML/HTML
        public boolean inTag = false;
        public boolean inEndTag = false;
        public boolean isSpecialTag = false; // <? or <!
        public boolean isSelfClosing = false;
    }

    public static String formatJsonChunk(String chunk, FormatState state) {
        StringBuilder sb = new StringBuilder((int) (chunk.length() * 1.2));
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);

            // Ignore JSON formatting for text outside of JSON structures (objects/arrays)
            if (state.indentLevel == 0 && c != '{' && c != '[') {
                state.inString = false;
                state.escapeNext = false;
                appendChar(sb, c, state);
                continue;
            }

            if (state.escapeNext) {
                appendChar(sb, c, state);
                state.escapeNext = false;
                continue;
            }
            if (c == '\\') {
                appendChar(sb, c, state);
                state.escapeNext = true;
                continue;
            }
            if (c == '"') {
                state.inString = !state.inString;
                appendChar(sb, c, state);
                continue;
            }
            if (state.inString) {
                appendChar(sb, c, state);
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (state.indentLevel == 0) {
                    appendChar(sb, c, state);
                }
                continue;
            }

            if (c == '{' || c == '[') {
                appendChar(sb, c, state);
                state.indentLevel++;
                appendNewlineIndent(sb, state);
            } else if (c == '}' || c == ']') {
                state.indentLevel = Math.max(0, state.indentLevel - 1);
                if (state.lastAppended != '{' && state.lastAppended != '[') {
                    appendNewlineIndent(sb, state);
                }
                appendChar(sb, c, state);
            } else if (c == ',') {
                appendChar(sb, c, state);
                if (state.indentLevel > 0) {
                    appendNewlineIndent(sb, state);
                }
            } else if (c == ':') {
                appendChar(sb, c, state);
                if (state.indentLevel > 0) {
                    sb.append(' ');
                }
            } else {
                appendChar(sb, c, state);
            }
        }
        return sb.toString();
    }

    public static String formatXmlChunk(String chunk, FormatState state) {
        StringBuilder sb = new StringBuilder((int) (chunk.length() * 1.2));
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);

            if (!state.inTag && c == '<') {
                state.inTag = true;
                state.inEndTag = false;
                state.isSpecialTag = false;
                state.isSelfClosing = false;

                if (i + 1 < chunk.length()) {
                    char next = chunk.charAt(i + 1);
                    if (next == '/') {
                        state.inEndTag = true;
                        state.indentLevel = Math.max(0, state.indentLevel - 1);
                    } else if (next == '?' || next == '!') {
                        state.isSpecialTag = true;
                    }
                }

                if (state.indentLevel > 0 && state.lastAppended == '>') {
                    appendNewlineIndent(sb, state);
                } else if (state.inEndTag && state.lastAppended == '>') {
                    appendNewlineIndent(sb, state);
                }

                appendChar(sb, c, state);
                continue;
            }

            if (state.inTag && c == '>') {
                state.inTag = false;
                appendChar(sb, c, state);

                if (!state.inEndTag && !state.isSpecialTag && !state.isSelfClosing) {
                    state.indentLevel++;
                }
                continue;
            }

            if (state.inTag && c == '/' && i + 1 < chunk.length() && chunk.charAt(i + 1) == '>') {
                state.isSelfClosing = true;
            }

            if (Character.isWhitespace(c) && !state.inTag && state.indentLevel > 0) {
                continue; // Skip whitespaces between tags
            }

            appendChar(sb, c, state);
        }
        return sb.toString();
    }

    private static void appendChar(StringBuilder sb, char c, FormatState state) {
        sb.append(c);
        if (!Character.isWhitespace(c)) {
            state.lastAppended = c;
        }
    }

    private static void appendNewlineIndent(StringBuilder sb, FormatState state) {
        sb.append('\n');
        for (int i = 0; i < state.indentLevel; i++) {
            sb.append("  ");
        }
    }
}
