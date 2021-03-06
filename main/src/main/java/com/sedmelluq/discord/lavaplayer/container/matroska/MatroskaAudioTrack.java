package com.sedmelluq.discord.lavaplayer.container.matroska;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.ebml.matroska.MatroskaFileTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio track that handles the processing of MKV and WEBM formats
 */
public class MatroskaAudioTrack extends BaseAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(MatroskaAudioTrack.class);

  private final SeekableInputStream inputStream;

  /**
   * @param trackInfo Track info
   * @param inputStream Input stream for the file
   */
  public MatroskaAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
    super(trackInfo);

    this.inputStream = inputStream;
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) {
    MatroskaStreamingFile file = loadMatroskaFile();
    MatroskaTrackConsumer trackConsumer = loadAudioTrack(file, localExecutor.getProcessingContext());

    try {
      localExecutor.executeProcessingLoop(() -> {
        file.provideFrames(trackConsumer);
      }, position -> {
        file.seekToTimecode(trackConsumer.getTrack().getTrackNo(), position);
      });
    } finally {
      trackConsumer.close();
    }
  }

  private MatroskaStreamingFile loadMatroskaFile() {
    MatroskaStreamingFile file = new MatroskaStreamingFile(
        new MatroskaStreamDataSource(inputStream)
    );

    file.readFile();

    accurateDuration.set((int) file.getDuration());
    return file;
  }

  private MatroskaTrackConsumer loadAudioTrack(MatroskaStreamingFile file, AudioProcessingContext context) {
    MatroskaTrackConsumer trackConsumer = null;
    boolean success = false;

    try {
      trackConsumer = selectAudioTrack(file.getTrackList(), context);

      if (trackConsumer == null) {
        throw new IllegalStateException("No supported audio tracks in the file.");
      } else {
        log.debug("Starting to play track with codec {}", trackConsumer.getTrack().getCodecID());
      }

      trackConsumer.initialise();
      success = true;
    } finally {
      if (!success && trackConsumer != null) {
        trackConsumer.close();
      }
    }

    return trackConsumer;
  }

  private MatroskaTrackConsumer selectAudioTrack(MatroskaFileTrack[] tracks, AudioProcessingContext context) {
    MatroskaTrackConsumer trackConsumer = null;

    for (MatroskaFileTrack track : tracks) {
      if (track.getTrackType() == MatroskaFileTrack.TrackType.AUDIO) {
        if (MatroskaContainerProbe.OPUS_CODEC.equals(track.getCodecID())) {
          trackConsumer = new MatroskaOpusTrackConsumer(context, track);
          break;
        } else if (MatroskaContainerProbe.VORBIS_CODEC.equals(track.getCodecID())) {
          trackConsumer = new MatroskaVorbisTrackConsumer(context, track);
        } else if (MatroskaContainerProbe.AAC_CODEC.equals(track.getCodecID())) {
          trackConsumer = new MatroskaAacTrackConsumer(context, track);
        }
      }
    }

    return trackConsumer;
  }
}
