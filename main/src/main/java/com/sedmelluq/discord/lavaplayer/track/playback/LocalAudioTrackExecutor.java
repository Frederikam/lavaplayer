package com.sedmelluq.discord.lavaplayer.track.playback;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioLoop;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Handles the execution and output buffering of an audio track.
 */
public class LocalAudioTrackExecutor implements AudioTrackExecutor {
  private static final Logger log = LoggerFactory.getLogger(LocalAudioTrackExecutor.class);

  private final InternalAudioTrack audioTrack;
  private final AudioProcessingContext processingContext;
  private final boolean useSeekGhosting;
  private final AudioFrameBuffer frameBuffer;
  private final AtomicReference<Thread> playingThread = new AtomicReference<>();
  private final AtomicBoolean isStopping = new AtomicBoolean(false);
  private final AtomicLong pendingSeek = new AtomicLong(-1);
  private final AtomicLong lastFrameTimecode = new AtomicLong(0);
  private final AtomicReference<AudioTrackState> state = new AtomicReference<>(AudioTrackState.INACTIVE);
  private final Object actionSynchronizer = new Object();
  private volatile AudioLoop audioLoop;

  /**
   * @param audioTrack The audio track that this executor executes
   * @param configuration Configuration to use for audio processing
   * @param volumeLevel Mutable volume level to use when executing the track
   * @param useSeekGhosting Whether to keep providing old frames continuing from the previous position during a seek
   *                        until frames from the new position arrive.
   * @param bufferDuration The size of the frame buffer in milliseconds
   */
  public LocalAudioTrackExecutor(InternalAudioTrack audioTrack, AudioConfiguration configuration, AtomicInteger volumeLevel,
                                 boolean useSeekGhosting, int bufferDuration) {

    this.audioTrack = audioTrack;
    this.frameBuffer = new AudioFrameBuffer(bufferDuration);
    this.processingContext = new AudioProcessingContext(configuration, frameBuffer, volumeLevel);
    this.useSeekGhosting = useSeekGhosting;
  }

  public AudioProcessingContext getProcessingContext() {
    return processingContext;
  }

  @Override
  public AudioFrameBuffer getAudioBuffer() {
    return frameBuffer;
  }

  @Override
  public void execute(TrackStateListener listener) {
    if (playingThread.compareAndSet(null, Thread.currentThread())) {
      state.set(AudioTrackState.LOADING);

      try {
        audioTrack.process(this);

        log.info("Playing track {} finished or was stopped.", audioTrack.getIdentifier());
      } catch (Throwable e) {
        frameBuffer.setTerminateOnEmpty();

        FriendlyException exception = ExceptionTools.wrapUnfriendlyExceptions("Something broke when playing the track.", FAULT, e);
        ExceptionTools.log(log, exception, "playback of " + audioTrack.getIdentifier());

        listener.onTrackException(audioTrack, exception);

        ExceptionTools.rethrowErrors(e);
      } finally {
        synchronized (actionSynchronizer) {
          Thread.interrupted();

          playingThread.compareAndSet(Thread.currentThread(), null);

          state.set(AudioTrackState.FINISHED);
        }
      }
    } else {
      log.warn("Tried to start an already playing track {}", audioTrack.getIdentifier());
    }
  }

  @Override
  public void stop() {
    synchronized (actionSynchronizer) {
      Thread thread = playingThread.get();

      if (thread != null) {
        log.debug("Requesting stop for track {}", audioTrack.getIdentifier());

        isStopping.compareAndSet(false, true);
        thread.interrupt();
      } else {
        log.debug("Tried to stop track {} which is not playing.", audioTrack.getIdentifier());
      }
    }
  }

  /**
   * @return True if the track has been scheduled to stop and then clears the scheduled stop bit.
   */
  public boolean checkStopped() {
    return isStopping.compareAndSet(true, false);
  }

  /**
   * Wait until all the frames from the frame buffer have been consumed. Keeps the buffering thread alive to keep it
   * interruptible for seeking until buffer is empty.
   */
  public void waitOnEnd() throws InterruptedException {
    frameBuffer.setTerminateOnEmpty();
    frameBuffer.waitForTermination();
  }

  /**
   * Interrupt the buffering thread, either stop or seek should have been set beforehand.
   * @return True if there was a thread to interrupt.
   */
  public boolean interrupt() {
    synchronized (actionSynchronizer) {
      Thread thread = playingThread.get();

      if (thread != null) {
        thread.interrupt();
        return true;
      }

      return false;
    }
  }

  @Override
  public long getPosition() {
    long seek = pendingSeek.get();
    return seek != -1 ? seek : lastFrameTimecode.get();
  }

  @Override
  public void setPosition(long timecode) {
    if (!audioTrack.isSeekable()) {
      return;
    }

    synchronized (actionSynchronizer) {
      if (timecode < 0) {
        timecode = 0;
      }

      pendingSeek.set(timecode);

      if (!useSeekGhosting) {
        frameBuffer.clear();
      }

      if (interrupt()) {
        log.debug("Interrupting playing thread to perform a seek {}", audioTrack.getIdentifier());
      } else {
        log.debug("Seeking on a track which is not playing {}", audioTrack.getIdentifier());
      }
    }
  }

  @Override
  public AudioTrackState getState() {
    return state.get();
  }

  /**
   * @return True if this track is currently in the middle of a seek.
   */
  private boolean isPerformingSeek() {
    return pendingSeek.get() != -1 || (useSeekGhosting && frameBuffer.hasClearOnInsert());
  }

  @Override
  public void setLoop(AudioLoop loop) {
    if (!audioTrack.isSeekable()) {
      return;
    }

    this.audioLoop = loop;

    if (loop != null) {
      log.debug("Setting loop between {} and {} on track {}", loop.startPosition, loop.endPosition, audioTrack.getIdentifier());
      setPosition(loop.startPosition);
    }
  }

  /**
   * Execute the read and seek loop for the track.
   * @param readExecutor Callback for reading the track
   * @param seekExecutor Callback for performing a seek on the track, may be null on a non-seekable track
   */
  public void executeProcessingLoop(ReadExecutor readExecutor, SeekExecutor seekExecutor) {
    boolean proceed = true;

    checkPendingSeek(seekExecutor);

    while (proceed) {
      state.set(AudioTrackState.PLAYING);
      proceed = false;

      try {
        readExecutor.performRead();

        // Must not finish before terminator frame has been consumed the user may still want to perform seeks until then
        waitOnEnd();
      } catch (InterruptedException interruption) {
        Thread.interrupted();

        if (checkStopped()) {
          proceed = false;
        } else if (checkPendingSeek(seekExecutor)) {
          proceed = true;
        } else {
          throw new FriendlyException("The track was unexpectedly terminated.", SUSPICIOUS, interruption);
        }
      } catch (Exception e) {
        throw ExceptionTools.wrapUnfriendlyExceptions("Something went wrong when decoding the track.", FAULT, e);
      }
    }
  }

  /**
   * Performs a seek if it scheduled.
   * @param seekExecutor Callback for performing a seek on the track
   * @return True if a seek was performed
   */
  private boolean checkPendingSeek(SeekExecutor seekExecutor) {
    if (!audioTrack.isSeekable()) {
      return false;
    }

    synchronized (actionSynchronizer) {
      long seekPosition = pendingSeek.get();

      if (seekPosition != -1) {
        log.debug("Track {} interrupted for seeking to {}.", audioTrack.getIdentifier(), seekPosition);

        try {
          performSeek(seekExecutor, seekPosition);
        } catch (Exception e) {
          throw ExceptionTools.wrapUnfriendlyExceptions("Something went wrong when seeking to a position.", FAULT, e);
        }

        return true;
      }
    }

    return false;
  }

  private void performSeek(SeekExecutor seekExecutor, long seekPosition) {
    state.set(AudioTrackState.SEEKING);

    if (useSeekGhosting) {
      frameBuffer.setClearOnInsert();
    } else {
      frameBuffer.clear();
    }

    seekExecutor.performSeek(seekPosition);
    pendingSeek.set(-1);
  }

  @Override
  public AudioFrame provide() {
    AudioFrame frame = frameBuffer.provide();

    if (frame != null && !frame.isTerminator()) {
      AudioLoop loop = audioLoop;
      if (loop != null && frame.timecode >= loop.endPosition && !isPerformingSeek()) {
        setPosition(loop.startPosition);
      }

      lastFrameTimecode.set(frame.timecode);
    }

    return frame;
  }

  /**
   * Read executor, see method description
   */
  public interface ReadExecutor {
    /**
     * Reads until interrupted or EOF
     * @throws InterruptedException
     */
    void performRead() throws InterruptedException;
  }

  /**
   * Seek executor, see method description
   */
  public interface SeekExecutor {
    /**
     * Perform a seek to the specified position
     * @param position Position in milliseconds
     */
    void performSeek(long position);
  }
}
