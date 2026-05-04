package com.eclipseapp.pulse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses LRC synced lyrics like Metrolist. */
final class SyncedLyricsParser {
    static final Pattern LRC = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)");

    static class Line {
        final long timeMs;
        final String text;
        Line(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }

    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return lines;
        for (String raw : lrc.split("\n")) {
            Matcher m = LRC.matcher(raw.trim());
            if (m.matches()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                String msStr = m.group(3);
                int ms = Integer.parseInt(msStr);
                if (msStr.length() == 2) ms *= 10;
                long timeMs = min * 60000L + sec * 1000L + ms;
                String text = m.group(4).trim();
                if (!text.isEmpty()) lines.add(new Line(timeMs, text));
            }
        }
        return lines;
    }

    static int findActive(List<Line> lines, long posMs) {
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= posMs) active = i;
            else break;
        }
        return active;
    }
}
