package com.eclipseapp.pulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PlayerQueueStore {
    // Loop: 0=off, 1=loop all, 2=loop one
    private static final List<MainActivity.Track> queue = new ArrayList<>();
    private static int currentIndex;
    private static boolean shuffleOn = false;
    private static int loopMode = 0; // 0=off, 1=all, 2=one
    private static final java.util.Random rng = new java.util.Random();

    private PlayerQueueStore() {}

    static boolean isShuffleOn() { return shuffleOn; }
    static void toggleShuffle() { shuffleOn = !shuffleOn; }
    static int getLoopMode() { return loopMode; }
    static void cycleLoop() { loopMode = (loopMode + 1) % 3; }

    static synchronized void setQueue(MainActivity.Track[] tracks, MainActivity.Track selected) {
        queue.clear();
        if (tracks != null) {
            Collections.addAll(queue, tracks);
        }
        if (queue.isEmpty() && selected != null) {
            queue.add(selected);
        }
        currentIndex = resolveSelectedIndex(selected);
    }

    static synchronized void setQueue(List<MainActivity.Track> tracks, MainActivity.Track selected) {
        queue.clear();
        if (tracks != null) {
            queue.addAll(tracks);
        }
        if (queue.isEmpty() && selected != null) {
            queue.add(selected);
        }
        currentIndex = resolveSelectedIndex(selected);
    }

    static synchronized MainActivity.Track current() {
        if (queue.isEmpty()) {
            return null;
        }
        currentIndex = clampIndex(currentIndex);
        return queue.get(currentIndex);
    }

    static synchronized MainActivity.Track next() {
        if (queue.isEmpty()) return null;
        if (loopMode == 2) return queue.get(clampIndex(currentIndex)); // loop one
        if (shuffleOn && queue.size() > 1) {
            int next;
            do { next = rng.nextInt(queue.size()); } while (next == currentIndex);
            currentIndex = next;
        } else {
            currentIndex = (currentIndex + 1) % queue.size();
        }
        // If loop off and wrapped to 0, stop
        if (loopMode == 0 && currentIndex == 0 && !shuffleOn) return null;
        return queue.get(currentIndex);
    }

    static synchronized MainActivity.Track previous() {
        if (queue.isEmpty()) {
            return null;
        }
        currentIndex = currentIndex == 0 ? queue.size() - 1 : currentIndex - 1;
        return queue.get(currentIndex);
    }

    static synchronized MainActivity.Track playAt(int index) {
        if (queue.isEmpty()) {
            return null;
        }
        currentIndex = clampIndex(index);
        return queue.get(currentIndex);
    }

    static synchronized List<MainActivity.Track> snapshot() {
        return new ArrayList<>(queue);
    }

    static synchronized int currentIndex() {
        return clampIndex(currentIndex);
    }

    static synchronized void updateCurrent(MainActivity.Track track) {
        if (track == null) {
            return;
        }
        if (queue.isEmpty()) {
            queue.add(track);
            currentIndex = 0;
            return;
        }
        currentIndex = clampIndex(currentIndex);
        queue.set(currentIndex, track);
    }

    /** Append radio/related tracks (from Metrolist-style autoplay) without replacing queue */
    static synchronized void appendRadio(List<MainActivity.Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (MainActivity.Track t : queue) {
            if (t.sourceId != null && !t.sourceId.isEmpty()) existing.add(t.sourceId);
        }
        for (MainActivity.Track t : tracks) {
            if (t.sourceId != null && !t.sourceId.isEmpty() && !existing.contains(t.sourceId)) {
                queue.add(t);
                existing.add(t.sourceId);
            }
        }
    }

    static synchronized boolean needsRadio() {
        return queue.size() <= 1 || currentIndex >= queue.size() - 2;
    }

    /** Reorder queue: move item at fromIndex to toIndex (for drag-drop) */
    static synchronized void moveTrack(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= queue.size()) return;
        if (toIndex < 0 || toIndex >= queue.size()) return;
        if (fromIndex == toIndex) return;
        MainActivity.Track moved = queue.remove(fromIndex);
        queue.add(toIndex, moved);
        // Adjust currentIndex so the playing track stays consistent
        if (currentIndex == fromIndex) {
            currentIndex = toIndex;
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            currentIndex--;
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            currentIndex++;
        }
    }

    /** Peek at the next track without advancing currentIndex */
    static synchronized MainActivity.Track peekNext() {
        if (queue.isEmpty()) return null;
        if (loopMode == 2) return queue.get(clampIndex(currentIndex));
        int nextIdx = (currentIndex + 1) % queue.size();
        if (loopMode == 0 && nextIdx == 0 && !shuffleOn) return null;
        return queue.get(nextIdx);
    }

    /** Peek at a specific index without changing currentIndex */
    static synchronized MainActivity.Track peekAt(int index) {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    private static int clampIndex(int index) {
        if (queue.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(index, queue.size() - 1));
    }

    private static int resolveSelectedIndex(MainActivity.Track selected) {
        if (selected == null) {
            return 0;
        }
        int index = queue.indexOf(selected);
        if (index >= 0) {
            return index;
        }
        queue.add(0, selected);
        return 0;
    }
}
