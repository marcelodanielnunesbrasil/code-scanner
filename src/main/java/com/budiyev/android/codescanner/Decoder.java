/*
 * MIT License
 *
 * Copyright (c) 2017 Yuriy Budiyev [yuriy.budiyev@yandex.ru]
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.budiyev.android.codescanner;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;

final class Decoder {
    private final BlockingQueue<DecodeTask> mDecodeQueue = new SynchronousQueue<>();
    private final MultiFormatReader mReader;
    private final DecoderThread mDecoderThread;
    private final StateListener mStateListener;
    private final Map<DecodeHintType, Object> mHints;
    private volatile DecodeCallback mCallback;
    private volatile State mState;

    public Decoder(@NonNull final StateListener stateListener, @NonNull final List<BarcodeFormat> formats,
            @Nullable final DecodeCallback callback) {
        mReader = new MultiFormatReader();
        mDecoderThread = new DecoderThread();
        mHints = new EnumMap<>(DecodeHintType.class);
        mHints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        mReader.setHints(mHints);
        mCallback = callback;
        mStateListener = stateListener;
        mState = State.INITIALIZED;
    }

    public void setFormats(@NonNull final List<BarcodeFormat> formats) {
        mHints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        mReader.setHints(mHints);
    }

    public void setCallback(@Nullable final DecodeCallback callback) {
        mCallback = callback;
    }

    public void decode(@NonNull final DecodeTask task) {
        mDecodeQueue.offer(task);
    }

    public void start() {
        if (mState != State.INITIALIZED) {
            throw new IllegalStateException("Illegal decoder state");
        }
        mDecoderThread.start();
    }

    public void shutdown() {
        mDecoderThread.interrupt();
        mDecodeQueue.clear();
    }

    @NonNull
    public State getState() {
        return mState;
    }

    private boolean setState(@NonNull final State state) {
        mState = state;
        return mStateListener.onStateChanged(state);
    }

    private final class DecoderThread extends Thread {
        public DecoderThread() {
            super("cs-decoder");
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            for (; ; ) {
                try {
                    setState(Decoder.State.IDLE);
                    Result result = null;
                    try {
                        final DecodeTask task = mDecodeQueue.take();
                        setState(Decoder.State.DECODING);
                        result = task.decode(mReader);
                    } catch (final ReaderException ignored) {
                    } finally {
                        if (result != null) {
                            mDecodeQueue.clear();
                            if (setState(Decoder.State.DECODED)) {
                                final DecodeCallback callback = mCallback;
                                if (callback != null) {
                                    callback.onDecoded(result);
                                }
                            }
                        }
                    }
                } catch (final InterruptedException e) {
                    setState(Decoder.State.STOPPED);
                    break;
                }
            }
        }
    }

    public interface StateListener {
        boolean onStateChanged(@NonNull State state);
    }

    public enum State {
        INITIALIZED,
        IDLE,
        DECODING,
        DECODED,
        STOPPED
    }
}
