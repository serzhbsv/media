/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.Math.floor;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import androidx.annotation.WorkerThread;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Forwards a video frame produced from a {@link Bitmap} to a {@link GlShaderProgram} for
 * consumption.
 *
 * <p>Methods in this class can be called from any thread.
 */
@UnstableApi
/* package */ final class InternalTextureManager implements GlShaderProgram.InputListener {
  private final GlShaderProgram shaderProgram;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;
  // The queue holds all bitmaps with one or more frames pending to be sent downstream.
  private final Queue<BitmapFrameSequenceInfo> pendingBitmaps;

  private @MonotonicNonNull TextureInfo currentTextureInfo;
  private int downstreamShaderProgramCapacity;
  private int framesToQueueForCurrentBitmap;
  private long currentPresentationTimeUs;
  private boolean inputEnded;
  private boolean useHdr;
  private boolean outputEnded;

  /**
   * Creates a new instance.
   *
   * @param shaderProgram The {@link GlShaderProgram} for which this {@code InternalTextureManager}
   *     will be set as the {@link GlShaderProgram.InputListener}.
   * @param videoFrameProcessingTaskExecutor The {@link VideoFrameProcessingTaskExecutor} that the
   *     methods of this class run on.
   */
  public InternalTextureManager(
      GlShaderProgram shaderProgram,
      VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor) {
    this.shaderProgram = shaderProgram;
    this.videoFrameProcessingTaskExecutor = videoFrameProcessingTaskExecutor;
    pendingBitmaps = new LinkedBlockingQueue<>();
  }

  @Override
  public void onReadyToAcceptInputFrame() {
    videoFrameProcessingTaskExecutor.submit(
        () -> {
          downstreamShaderProgramCapacity++;
          maybeQueueToShaderProgram();
        });
  }

  /**
   * Provides an input {@link Bitmap} to put into the video frames.
   *
   * @see VideoFrameProcessor#queueInputBitmap
   */
  public void queueInputBitmap(
      Bitmap inputBitmap, long durationUs, float frameRate, boolean useHdr) {
    videoFrameProcessingTaskExecutor.submit(
        () -> setupBitmap(inputBitmap, durationUs, frameRate, useHdr));
  }

  /**
   * Signals the end of the input.
   *
   * @see VideoFrameProcessor#signalEndOfInput()
   */
  public void signalEndOfInput() {
    videoFrameProcessingTaskExecutor.submit(
        () -> {
          inputEnded = true;
          maybeSignalEndOfOutput();
        });
  }

  @WorkerThread
  private void setupBitmap(Bitmap bitmap, long durationUs, float frameRate, boolean useHdr)
      throws VideoFrameProcessingException {
    this.useHdr = useHdr;
    if (inputEnded) {
      return;
    }
    int framesToAdd = (int) floor(frameRate * (durationUs / (float) C.MICROS_PER_SECOND));
    long frameDurationUs = (long) floor(C.MICROS_PER_SECOND / frameRate);
    pendingBitmaps.add(new BitmapFrameSequenceInfo(bitmap, frameDurationUs, framesToAdd));

    maybeQueueToShaderProgram();
  }

  @WorkerThread
  private void maybeQueueToShaderProgram() throws VideoFrameProcessingException {
    if (pendingBitmaps.isEmpty() || downstreamShaderProgramCapacity == 0) {
      return;
    }

    BitmapFrameSequenceInfo currentBitmapInfo = checkNotNull(pendingBitmaps.peek());
    if (framesToQueueForCurrentBitmap == 0) {
      Bitmap bitmap = currentBitmapInfo.bitmap;
      framesToQueueForCurrentBitmap = currentBitmapInfo.numberOfFrames;
      int currentTexId;
      try {
        if (currentTextureInfo != null) {
          GlUtil.deleteTexture(currentTextureInfo.texId);
        }
        currentTexId =
            GlUtil.createTexture(
                bitmap.getWidth(),
                bitmap.getHeight(),
                /* useHighPrecisionColorComponents= */ useHdr);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, /* level= */ 0, bitmap, /* border= */ 0);
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        throw VideoFrameProcessingException.from(e);
      }
      currentTextureInfo =
          new TextureInfo(
              currentTexId, /* fboId= */ C.INDEX_UNSET, bitmap.getWidth(), bitmap.getHeight());
    }

    framesToQueueForCurrentBitmap--;
    downstreamShaderProgramCapacity--;

    shaderProgram.queueInputFrame(checkNotNull(currentTextureInfo), currentPresentationTimeUs);

    currentPresentationTimeUs += currentBitmapInfo.frameDurationUs;
    if (framesToQueueForCurrentBitmap == 0) {
      pendingBitmaps.remove();
      maybeSignalEndOfOutput();
    }
  }

  @WorkerThread
  private void maybeSignalEndOfOutput() {
    if (framesToQueueForCurrentBitmap == 0
        && pendingBitmaps.isEmpty()
        && inputEnded
        && !outputEnded) {
      shaderProgram.signalEndOfCurrentInputStream();
      outputEnded = true;
    }
  }

  /** Information to generate all the frames associated with a specific {@link Bitmap}. */
  private static final class BitmapFrameSequenceInfo {
    public final Bitmap bitmap;
    public final long frameDurationUs;
    public final int numberOfFrames;

    public BitmapFrameSequenceInfo(Bitmap bitmap, long frameDurationUs, int numberOfFrames) {
      this.bitmap = bitmap;
      this.frameDurationUs = frameDurationUs;
      this.numberOfFrames = numberOfFrames;
    }
  }
}