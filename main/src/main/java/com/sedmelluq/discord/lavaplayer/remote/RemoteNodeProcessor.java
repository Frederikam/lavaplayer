package com.sedmelluq.discord.lavaplayer.remote;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.remote.message.NodeStatisticsMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.RemoteMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.RemoteMessageMapper;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackExceptionMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackFrameDataMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackFrameRequestMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackStartRequestMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackStartResponseMessage;
import com.sedmelluq.discord.lavaplayer.remote.message.TrackStoppedMessage;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Processes one remote node.
 */
public class RemoteNodeProcessor implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(RemoteNodeProcessor.class);

  private static final int CONNECT_TIMEOUT = 1000;
  private static final int SOCKET_TIMEOUT = 2000;
  private static final int TRACK_KILL_THRESHOLD = 5000;

  private final DefaultAudioPlayerManager playerManager;
  private final String nodeAddress;
  private final ScheduledThreadPoolExecutor scheduledExecutor;
  private final BlockingQueue<RemoteMessage> queuedMessages;
  private final ConcurrentMap<Long, RemoteAudioTrackExecutor> playingTracks;
  private final RemoteMessageMapper mapper;
  private final HttpClientBuilder httpClientBuilder;
  private final AtomicBoolean threadRunning;
  private final AtomicInteger controlState;
  private volatile int aliveTickCounter;
  private volatile long lastAliveTime;
  private volatile NodeStatisticsMessage lastStatistics;

  /**
   * @param playerManager Audio player manager
   * @param nodeAddress Address of this node
   * @param scheduledExecutor Scheduler to use to schedule reconnects
   */
  public RemoteNodeProcessor(DefaultAudioPlayerManager playerManager, String nodeAddress, ScheduledThreadPoolExecutor scheduledExecutor) {
    this.playerManager = playerManager;
    this.nodeAddress = nodeAddress;
    this.scheduledExecutor = scheduledExecutor;
    queuedMessages = new LinkedBlockingQueue<>();
    playingTracks = new ConcurrentHashMap<>();
    mapper = new RemoteMessageMapper();
    httpClientBuilder = createHttpClientBuilder();
    threadRunning = new AtomicBoolean();
    controlState = new AtomicInteger(ControlState.OFFLINE.id());
  }

  /**
   * Start playing a track through this remote node.
   * @param executor The executor of the track
   */
  public void startPlaying(RemoteAudioTrackExecutor executor) {
    AudioTrack track = executor.getTrack();

    if (playingTracks.putIfAbsent(executor.getExecutorId(), executor) == null) {
      log.info("Sending request to play {} {}", track.getIdentifier(), executor.getExecutorId());

      queuedMessages.add(new TrackStartRequestMessage(executor.getExecutorId(), track.getInfo(), playerManager.encodeTrackDetails(track),
          executor.getVolume(), executor.getConfiguration()));
    }
  }

  /**
   * Clear the track from this node.
   * @param executor Executor of the track
   * @param notifyNode Whether it is necessary to notify the node
   */
  public void trackEnded(RemoteAudioTrackExecutor executor, boolean notifyNode) {
    if (playingTracks.remove(executor.getExecutorId()) != null) {
      log.info("Track {} removed from node {} (context {})", executor.getTrack().getIdentifier(), nodeAddress, executor.getExecutorId());

      if (notifyNode) {
        log.info("Notifying node {} of track stop for {} (context {})", nodeAddress, executor.getTrack().getIdentifier(), executor.getExecutorId());

        queuedMessages.add(new TrackStoppedMessage(executor.getExecutorId()));
      }

      executor.detach();
    }
  }

  @Override
  public void run() {
    if (!threadRunning.compareAndSet(false, true)) {
      log.debug("Not running node processor for {}, thread already active.", nodeAddress);
      return;
    }

    log.debug("Trying to connect to node {}.", nodeAddress);

    controlState.set(ControlState.PENDING.id());

    try (CloseableHttpClient httpClient = httpClientBuilder.build()) {
      while (processOneTick(httpClient)) {
        aliveTickCounter = Math.max(1, aliveTickCounter + 1);
        lastAliveTime = System.currentTimeMillis();
      }
    } catch (Throwable e) {
      if (aliveTickCounter > 0) {
        log.error("Node {} went offline with exception.", nodeAddress, e);
      } else {
        log.debug("Retry, node {} is still offline.", nodeAddress);
      }

      ExceptionTools.rethrowErrors(e);
    } finally {
      controlState.set(ControlState.OFFLINE.id());

      aliveTickCounter = Math.min(-1, aliveTickCounter - 1);
      threadRunning.set(false);

      scheduledExecutor.schedule(this, getScheduleDelay(), TimeUnit.MILLISECONDS);
    }
  }

  private long getScheduleDelay() {
    if (aliveTickCounter >= -5) {
      return 1000;
    } else if (aliveTickCounter >= -20) {
      return 3000;
    } else {
      return 10000;
    }
  }

  private boolean processOneTick(CloseableHttpClient httpClient) throws Exception {
    HttpPost post = new HttpPost("http://" + nodeAddress + "/tick");
    post.setEntity(new ByteArrayEntity(buildRequestBody()));

    try (CloseableHttpResponse response = httpClient.execute(post)) {
      if (response.getStatusLine().getStatusCode() != 200) {
        throw new IOException("Returned an unexpected response code " + response.getStatusLine().getStatusCode());
      }

      if (controlState.compareAndSet(ControlState.PENDING.id(), ControlState.ONLINE.id())) {
        log.info("Node {} came online.", nodeAddress);
      } else if (controlState.get() != ControlState.ONLINE.id()) {
        log.warn("Node {} received successful response, but had already lost control of its tracks.");
        return false;
      }

      lastAliveTime = System.currentTimeMillis();

      if (!handleResponseBody(response.getEntity().getContent())) {
        return false;
      }
    }

    Thread.sleep(500);
    return true;
  }

  private byte[] buildRequestBody() throws IOException {
    ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(outputBytes);

    List<RemoteMessage> messages = new ArrayList<>();
    int queuedCount = queuedMessages.drainTo(messages);

    if (queuedCount > 0) {
      log.debug("Including {} queued messages in the request to {}.", queuedCount, nodeAddress);
    }

    for (RemoteAudioTrackExecutor executor : playingTracks.values()) {
      long pendingSeek = executor.getPendingSeek();

      AudioFrameBuffer buffer = executor.getAudioBuffer();
      int neededFrames = pendingSeek == -1 ? buffer.getRemainingCapacity() : buffer.getFullCapacity();

      messages.add(new TrackFrameRequestMessage(executor.getExecutorId(), neededFrames, executor.getVolume(), pendingSeek));
    }

    for (RemoteMessage message : messages) {
      mapper.encode(output, message);
    }

    mapper.endOutput(output);
    return outputBytes.toByteArray();
  }

  private boolean handleResponseBody(InputStream inputStream) {
    DataInputStream input = new DataInputStream(inputStream);
    RemoteMessage message;

    try {
      while ((message = mapper.decode(input)) != null) {
        if (message instanceof TrackStartResponseMessage) {
          handleTrackStartResponse((TrackStartResponseMessage) message);
        } else if (message instanceof TrackFrameDataMessage) {
          handleTrackFrameData((TrackFrameDataMessage) message);
        } else if (message instanceof TrackExceptionMessage) {
          handleTrackException((TrackExceptionMessage) message);
        } else if (message instanceof NodeStatisticsMessage) {
          handleNodeStatistics((NodeStatisticsMessage) message);
        }
      }
    } catch (InterruptedException interruption) {
      log.error("Node processing thread was interrupted.");
      Thread.currentThread().interrupt();
      return false;
    } catch (Throwable e) {
      log.error("Error when processing response from node.", e);
      ExceptionTools.rethrowErrors(e);
    }

    return true;
  }

  private void handleTrackStartResponse(TrackStartResponseMessage message) {
    if (message.success) {
      log.debug("Successful start confirmation from node {} for executor {}.", nodeAddress, message.executorId);
    } else {
      RemoteAudioTrackExecutor executor = playingTracks.get(message.executorId);

      if (executor != null) {
        executor.dispatchException(new FriendlyException("Remote machine failed to start track: " + message.failureReason, SUSPICIOUS, null));
        executor.stop();
      } else {
        log.debug("Received failed track start for an already stopped executor {}.", message.executorId);
      }
    }
  }

  private void handleTrackFrameData(TrackFrameDataMessage message) throws Exception {
    RemoteAudioTrackExecutor executor = playingTracks.get(message.executorId);

    if (executor != null) {
      if (message.seekedPosition >= 0) {
        executor.clearSeek(message.seekedPosition);
      }

      AudioFrameBuffer buffer = executor.getAudioBuffer();
      executor.receivedData();

      for (AudioFrame frame : message.frames) {
        buffer.consume(frame);
      }

      if (message.finished) {
        buffer.setTerminateOnEmpty();
        trackEnded(executor, false);
      }
    }
  }

  private void handleTrackException(TrackExceptionMessage message) {
    RemoteAudioTrackExecutor executor = playingTracks.get(message.executorId);

    if (executor != null) {
      executor.dispatchException(message.exception);
    }
  }

  private void handleNodeStatistics(NodeStatisticsMessage message) {
    log.trace("Received stats from node: {} {} {} {}", message.playingTrackCount, message.totalTrackCount,
        message.processCpuUsage, message.systemCpuUsage);

    lastStatistics = message;
  }

  private static HttpClientBuilder createHttpClientBuilder() {
    RequestConfig.Builder requestBuilder = RequestConfig.custom();
    requestBuilder = requestBuilder.setConnectTimeout(CONNECT_TIMEOUT);
    requestBuilder = requestBuilder.setConnectionRequestTimeout(SOCKET_TIMEOUT);

    HttpClientBuilder builder = HttpClientBuilder.create();
    builder.setDefaultRequestConfig(requestBuilder.build());
    return builder;
  }

  /**
   * Check if there are any playing tracks on a node that has not shown signs of life in too long. In that case its
   * playing tracks will also be marked dead.
   *
   * @param terminate Whether to terminate without checking the threshold
   */
  public synchronized void processHealthCheck(boolean terminate) {
    if (playingTracks.isEmpty() || (!terminate && lastAliveTime >= System.currentTimeMillis() - TRACK_KILL_THRESHOLD)) {
      return;
    }

    controlState.set(ControlState.OFFLINE.id());

    // There may be some racing that manages to add a track after this, it will be dealt with on the next iteration
    for (Long executorId : new ArrayList<>(playingTracks.keySet())) {
      RemoteAudioTrackExecutor executor = playingTracks.remove(executorId);

      if (executor != null) {
        executor.dispatchException(new FriendlyException("The machine processing this song went offline.", SUSPICIOUS, null));
        executor.stop();
      }
    }
  }

  /**
   * @return The penalty for load balancing. Node with the lowest value will receive the next track.
   */
  public int getBalancerPenalty() {
    NodeStatisticsMessage statistics = lastStatistics;

    if (statistics == null || controlState.get() != ControlState.ONLINE.id()) {
      return Integer.MAX_VALUE;
    }

    int trackPenalty = statistics.totalTrackCount + statistics.playingTrackCount;
    int cpuPenalty = (int) Math.pow(statistics.systemCpuUsage + 0.7f, 10.0f);

    return trackPenalty + cpuPenalty;
  }

  private enum ControlState {
    PENDING,
    ONLINE,
    OFFLINE;

    private int id() {
      return ordinal();
    }
  }
}
