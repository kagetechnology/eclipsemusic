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
    private static final String AUDD_URL = "https://api.audd.io/";
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

                // Convert PCM to WAV
                byte[] wavData = pcmToWav(pcmData, offset, SAMPLE_RATE, 1, 16);

                // Send to audd.io
                String base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP);
                String result = sendToAudd(base64Audio);

                // Parse result
                JSONObject json = new JSONObject(result);
                String status = json.optString("status", "error");
                if ("success".equals(status)) {
                    JSONObject songResult = json.optJSONObject("result");
                    if (songResult != null) {
                        String title = songResult.optString("title", "");
                        String artist = songResult.optString("artist", "");
                        if (!title.isEmpty()) {
                            mainHandler.post(() -> callback.onResult(title, artist, "Found!"));
                            return;
                        }
                    }
                    mainHandler.post(() -> callback.onResult(null, null, "Song not recognized"));
                } else {
                    String errMsg = json.optString("error", "Unknown error");
                    if (errMsg.contains("limit")) {
                        mainHandler.post(() -> callback.onResult(null, null, "API limit reached, try again later"));
                    } else {
                        mainHandler.post(() -> callback.onResult(null, null, "Recognition failed"));
                    }
                }
            } catch (Exception e) {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    try { recorder.release(); } catch (Exception ignored) {}
                }
                mainHandler.post(() -> callback.onResult(null, null, "Error: " + e.getMessage()));
            }
        }, "music-recognizer").start();
    }

    private static String sendToAudd(String base64Audio) throws Exception {
        String boundary = "----EclipseBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(AUDD_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            // api_token field
            writeField(os, boundary, "api_token", "test");
            // return field
            writeField(os, boundary, "return", "apple_music,spotify");
            // audio as base64
            writeField(os, boundary, "audio", base64Audio);
            // Close boundary
            os.write(("--" + boundary + "--\r\n").getBytes());
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

    private static void writeField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        os.write((value + "\r\n").getBytes());
    }

    private static byte[] pcmToWav(byte[] pcm, int pcmLen, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLen = pcmLen;
        int totalLen = 36 + dataLen;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // RIFF header
            out.write("RIFF".getBytes());
            writeInt(out, totalLen);
            out.write("WAVE".getBytes());
            // fmt chunk
            out.write("fmt ".getBytes());
            writeInt(out, 16);
            writeShort(out, 1); // PCM
            writeShort(out, channels);
            writeInt(out, sampleRate);
            writeInt(out, byteRate);
            writeShort(out, blockAlign);
            writeShort(out, bitsPerSample);
            // data chunk
            out.write("data".getBytes());
            writeInt(out, dataLen);
            out.write(pcm, 0, pcmLen);
        } catch (IOException e) {
            // won't happen with ByteArrayOutputStream
        }
        return out.toByteArray();
    }

    private static void writeInt(OutputStream os, int v) throws IOException {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
        os.write((v >> 16) & 0xFF);
        os.write((v >> 24) & 0xFF);
    }

    private static void writeShort(OutputStream os, int v) throws IOException {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
    }
}
