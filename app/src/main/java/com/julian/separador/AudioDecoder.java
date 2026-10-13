package com.julian.separador;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/** Decodifica cualquier audio que entienda Android (mp3, m4a, ogg, flac, wav...) a PCM 16 bit estéreo. */
final class AudioDecoder {

    interface Progress {
        void onProgress(float fraction);
    }

    static final class Result {
        short[] left;
        short[] right;
        int sampleRate;
        int frames;
        boolean sourceWasMono;
    }

    private static final class ShortList {
        short[] data;
        int size;

        ShortList(int capacity) {
            data = new short[Math.max(capacity, 1024)];
        }

        void add(short v) {
            if (size == data.length) {
                long bigger = (long) data.length * 3 / 2 + 1024;
                data = Arrays.copyOf(data, (int) Math.min(Integer.MAX_VALUE - 8, bigger));
            }
            data[size++] = v;
        }
    }

    private AudioDecoder() {
    }

    static Result decode(Context context, Uri uri, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);

            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                throw new IOException("El archivo no tiene audio");
            }
            extractor.selectTrack(trackIndex);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            long cap = durationUs > 0
                    ? durationUs * sampleRate / 1000000L + 8192
                    : (long) sampleRate * 240;
            int capacity = (int) Math.min(Integer.MAX_VALUE / 4, cap);
            ShortList left = new ShortList(capacity);
            ShortList right = new ShortList(capacity);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            boolean pcmFloat = false;
            int outChannels = channels;
            int outRate = sampleRate;
            int guard = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, sampleTime, 0);
                            extractor.advance();
                            if (progress != null && durationUs > 0 && sampleTime >= 0) {
                                progress.onProgress(Math.min(1f, (float) sampleTime / durationUs));
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    guard = 0;
                    ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        appendPcm(outBuf, outChannels, pcmFloat, left, right);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Si ya no hay entrada y el decodificador no devuelve nada durante mucho rato, se corta.
                    if (++guard > 500) {
                        outputDone = true;
                    }
                }
            }

            Result r = new Result();
            r.frames = left.size;
            r.left = left.size == left.data.length ? left.data : Arrays.copyOf(left.data, left.size);
            r.right = right.size == right.data.length ? right.data : Arrays.copyOf(right.data, right.size);
            r.sampleRate = outRate > 0 ? outRate : sampleRate;
            r.sourceWasMono = outChannels == 1;
            if (r.frames == 0) {
                throw new IOException("No se pudo decodificar el audio");
            }
            return r;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static void appendPcm(ByteBuffer buf, int channels, boolean isFloat,
                                  ShortList left, ShortList right) {
        buf.order(ByteOrder.nativeOrder());
        int ch = Math.max(1, channels);
        if (isFloat) {
            FloatBuffer fb = buf.asFloatBuffer();
            int n = fb.remaining() / ch;
            for (int i = 0; i < n; i++) {
                float l = fb.get();
                float r = l;
                if (ch >= 2) {
                    r = fb.get();
                }
                for (int c = 2; c < ch; c++) {
                    fb.get();
                }
                left.add(floatToShort(l));
                right.add(floatToShort(r));
            }
        } else {
            ShortBuffer sb = buf.asShortBuffer();
            int n = sb.remaining() / ch;
            for (int i = 0; i < n; i++) {
                short l = sb.get();
                short r = l;
                if (ch >= 2) {
                    r = sb.get();
                }
                for (int c = 2; c < ch; c++) {
                    sb.get();
                }
                left.add(l);
                right.add(r);
            }
        }
    }

    private static short floatToShort(float v) {
        int s = Math.round(v * 32768f);
        if (s > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (s < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) s;
    }
}
