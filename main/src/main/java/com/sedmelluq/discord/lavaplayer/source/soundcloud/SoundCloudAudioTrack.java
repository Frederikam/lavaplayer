package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Audio track that handles processing SoundCloud tracks.
 */
public class SoundCloudAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(SoundCloudAudioTrack.class);

  private final SoundCloudAudioSourceManager sourceManager;
  private final String trackUrl;

  /**
   * @param trackInfo Track info
   * @param sourceManager Source manager which was used to find this track
   * @param trackUrl Base URL for the track (redirects to actual URL)
   */
  public SoundCloudAudioTrack(AudioTrackInfo trackInfo, SoundCloudAudioSourceManager sourceManager, String trackUrl) {
    super(trackInfo);

    this.sourceManager = sourceManager;
    this.trackUrl = trackUrl;
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    try (CloseableHttpClient httpClient = sourceManager.createHttpClient()) {
      log.debug("Starting SoundCloud track from URL: {}", trackUrl);

      try (PersistentHttpStream stream = new PersistentHttpStream(httpClient, new URI(trackUrl), null)) {
        processDelegate(new Mp3AudioTrack(trackInfo, stream), localExecutor);
      }
    }
  }

  @Override
  public AudioTrack makeClone() {
    return new SoundCloudAudioTrack(trackInfo, sourceManager, trackUrl);
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return sourceManager;
  }

  /**
   * @return The session independent URL for this track.
   */
  public String getTrackUrl() {
    return trackUrl;
  }
}
