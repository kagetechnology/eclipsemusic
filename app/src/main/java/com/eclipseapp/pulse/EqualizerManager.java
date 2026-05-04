package com.eclipseapp.pulse;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;

/**
 * Manages Android audio effects (Equalizer, Bass Boost, Virtualizer).
 * Attach to a MediaPlayer's audio session ID after onPrepared.
 * Settings are persisted to SharedPreferences via LocalStorageManager.
 */
final class EqualizerManager {
    private static final EqualizerManager INSTANCE = new EqualizerManager();

    static final String PREF_EQ_ENABLED   = "eq_enabled";
    static final String PREF_EQ_BANDS     = "eq_bands";      // JSON array of band levels in millibels
    static final String PREF_BASS_BOOST   = "eq_bass_boost"; // 0-1000
    static final String PREF_VIRTUALIZER  = "eq_virtualizer"; // 0-1000

    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;

    private EqualizerManager() {}

    static EqualizerManager get() { return INSTANCE; }

    /** Attach all effects to the given audio session. Call after MediaPlayer.onPrepared(). */
    void attach(Context context, int audioSessionId) {
        release();
        try {
            eq = new Equalizer(0, audioSessionId);
            bass = new BassBoost(0, audioSessionId);
            virt = new Virtualizer(0, audioSessionId);

            boolean enabled = isEnabled(context);
            eq.setEnabled(enabled);
            bass.setEnabled(enabled);
            virt.setEnabled(enabled);

            if (enabled) {
                applyStoredSettings(context);
            }
        } catch (Exception e) {
            release();
        }
    }

    /** Release all audio effects. Call when MediaPlayer is released. */
    void release() {
        try { if (eq != null) { eq.release(); eq = null; } } catch (Exception ignored) {}
        try { if (bass != null) { bass.release(); bass = null; } } catch (Exception ignored) {}
        try { if (virt != null) { virt.release(); virt = null; } } catch (Exception ignored) {}
    }

    boolean isEnabled(Context context) {
        return LocalStorageManager.getSettings(context).optBoolean(PREF_EQ_ENABLED, false);
    }

    void setEnabled(Context context, boolean enabled) {
        LocalStorageManager.saveSetting(context, PREF_EQ_ENABLED, enabled);
        if (eq != null) eq.setEnabled(enabled);
        if (bass != null) bass.setEnabled(enabled);
        if (virt != null) virt.setEnabled(enabled);
        if (enabled) applyStoredSettings(context);
    }

    int getNumberOfBands() {
        return eq != null ? eq.getNumberOfBands() : 5;
    }

    /** Returns band center frequency in milliHz for display (e.g. 60000 → 60 Hz) */
    int getBandCenterFreq(int band) {
        if (eq == null) return 0;
        return eq.getCenterFreq((short) band) / 1000; // convert milliHz -> Hz
    }

    short[] getBandLevelRange() {
        if (eq == null) return new short[]{-1500, 1500};
        return new short[]{eq.getBandLevelRange()[0], eq.getBandLevelRange()[1]};
    }

    /** Get current band level in millibels */
    short getBandLevel(Context context, int band) {
        if (eq == null) {
            try {
                org.json.JSONArray bands = new org.json.JSONArray(
                        LocalStorageManager.getSettings(context).optString(PREF_EQ_BANDS, "[]"));
                if (band < bands.length()) return (short) bands.getInt(band);
            } catch (Exception ignored) {}
            return 0;
        }
        return eq.getBandLevel((short) band);
    }

    /** Set a single band level in millibels and persist */
    void setBandLevel(Context context, int band, short levelMb) {
        if (eq != null) {
            try { eq.setBandLevel((short) band, levelMb); } catch (Exception ignored) {}
        }
        // Persist entire array
        try {
            int totalBands = getNumberOfBands();
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < totalBands; i++) {
                if (i == band) arr.put(levelMb);
                else arr.put(getBandLevel(context, i));
            }
            LocalStorageManager.saveSetting(context, PREF_EQ_BANDS, arr);
        } catch (Exception ignored) {}
    }

    short getBassBoostStrength(Context context) {
        return (short) LocalStorageManager.getSettings(context).optInt(PREF_BASS_BOOST, 0);
    }

    void setBassBoostStrength(Context context, short strength) {
        LocalStorageManager.saveSetting(context, PREF_BASS_BOOST, (int) strength);
        if (bass != null) {
            try { bass.setStrength(strength); } catch (Exception ignored) {}
        }
    }

    short getVirtualizerStrength(Context context) {
        return (short) LocalStorageManager.getSettings(context).optInt(PREF_VIRTUALIZER, 0);
    }

    void setVirtualizerStrength(Context context, short strength) {
        LocalStorageManager.saveSetting(context, PREF_VIRTUALIZER, (int) strength);
        if (virt != null) {
            try { virt.setStrength(strength); } catch (Exception ignored) {}
        }
    }

    void resetAll(Context context) {
        int bands = getNumberOfBands();
        for (int i = 0; i < bands; i++) {
            setBandLevel(context, i, (short) 0);
        }
        setBassBoostStrength(context, (short) 0);
        setVirtualizerStrength(context, (short) 0);
    }

    private void applyStoredSettings(Context context) {
        try {
            // Apply EQ bands
            org.json.JSONArray bands = new org.json.JSONArray(
                    LocalStorageManager.getSettings(context).optString(PREF_EQ_BANDS, "[]"));
            if (eq != null) {
                for (int i = 0; i < bands.length() && i < eq.getNumberOfBands(); i++) {
                    eq.setBandLevel((short) i, (short) bands.getInt(i));
                }
            }
            // Apply bass boost
            short bb = getBassBoostStrength(context);
            if (bass != null && bb > 0) bass.setStrength(bb);
            // Apply virtualizer
            short vv = getVirtualizerStrength(context);
            if (virt != null && vv > 0) virt.setStrength(vv);
        } catch (Exception ignored) {}
    }
}
