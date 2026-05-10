package com.eclipseapp.pulse;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Music recognition using audio fingerprinting via audd.io API.
 * Records mic audio, sends to recognition service, returns song info.
 */
final class MusicRecognizer {
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String SHAZAM_URL = "https://shazam.p.rapidapi.com/songs/v2/detect?timezone=America%2FChicago&locale=en-US";
    private static final String RAPIDAPI_KEY = "5308a06fdfmshba162281edc87c2p13117bjsn24c1df88dca4";
    private static final int SAMPLE_RATE = 44100;
    private static final int RECORD_SECONDS = 7;

    interface RecognizeCallback {
        void onResult(String title, String artist, String status);
    }

    interface ProgressCallback {
        void onProgress(String message);
    }

    private MusicRecognizer() {}

    /**
     * Record audio from mic for RECORD_SECONDS, then send to audd.io for recognition.
     */
    static void recognize(ProgressCallback progress, RecognizeCallback callback) {
        new Thread(() -> {
            AudioRecord recorder = null;
            try {
                mainHandler.post(() -> progress.onProgress("🎵 Listening..."));

                int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (bufSize <= 0) bufSize = SAMPLE_RATE * 2;

                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    mainHandler.post(() -> callback.onResult(null, null, "Mic not available"));
                    return;
                }

                // Record raw PCM
                int totalBytes = SAMPLE_RATE * 2 * RECORD_SECONDS;
                byte[] pcmData = new byte[totalBytes];
                recorder.startRecording();

                int offset = 0;
                while (offset < totalBytes) {
                    int read = recorder.read(pcmData, offset, Math.min(bufSize, totalBytes - offset));
                    if (read <= 0) break;
                    offset += read;
                    int secsLeft = RECORD_SECONDS - (offset / (SAMPLE_RATE * 2));
                    int finalOffset = offset;
                    mainHandler.post(() -> progress.onProgress("🎵 Listening... " + (RECORD_SECONDS - finalOffset / (SAMPLE_RATE * 2)) + "s"));
                }

                recorder.stop();
                recorder.release();
                recorder = null;

                mainHandler.post(() -> progress.onProgress("🔍 Recognizing..."));

                // Send to Shazam RapidAPI (raw PCM base64 encoded)
                String base64Audio = Base64.encodeToString(pcmData, 0, offset, Base64.NO_WRAP);
                String result = sendToShazam(base64Audio);

                // Parse result
                JSONObject json = new JSONObject(result);
                JSONObject track = json.optJSONObject("track");
                if (track != null) {
                    String title = track.optString("title", "");
                    String artist = track.optString("subtitle", "");
                    if (!title.isEmpty()) {
                        mainHandler.post(() -> callback.onResult(title, artist, "Found!"));
                        return;
                    }
                }
                
                // If we get here, either no track found or error
                String message = json.optString("message", "Song not recognized");
                if (json.has("message") && message.contains("exceeded")) {
                    message = "API limit reached, try again later";
                }
                
                String finalMsg = message;
                mainHandler.post(() -> callback.onResult(null, null, finalMsg));

            } catch (Exception e) {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    try { recorder.release(); } catch (Exception ignored) {}
                }
                mainHandler.post(() -> callback.onResult(null, null, "Error: " + e.getMessage()));
            }
        }, "music-recognizer").start();
    }

    private static String sendToShazam(String base64Audio) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(SHAZAM_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("x-rapidapi-host", "shazam.p.rapidapi.com");
        conn.setRequestProperty("x-rapidapi-key", RAPIDAPI_KEY);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(base64Audio.getBytes());
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
