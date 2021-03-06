package com.sedmelluq.discord.lavaplayer.container.playlists;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection.checkNextBytes;

/**
 * Probe for M3U playlist.
 */
public class M3uPlaylistContainerProbe implements MediaContainerProbe {
  private static final Logger log = LoggerFactory.getLogger(M3uPlaylistContainerProbe.class);

  private static final int[] M3U_HEADER_TAG = new int[] { '#', 'E', 'X', 'T', 'M', '3', 'U' };
  private static final int[] M3U_ENTRY_TAG = new int[] { '#', 'E', 'X', 'T', 'I', 'N', 'F' };

  private static final Pattern lineSplitPattern = Pattern.compile("[\\r\\n\\s]*\\n[\\r\\n\\s]*");

  @Override
  public String getName() {
    return "m3u";
  }

  @Override
  public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) throws IOException {
    if (!checkNextBytes(inputStream, M3U_HEADER_TAG) && !checkNextBytes(inputStream, M3U_ENTRY_TAG)) {
      return null;
    }

    log.debug("Track {} is an M3U playlist file.", reference.identifier);

    String text = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
    return loadFromLines(lineSplitPattern.split(text));
  }

  private MediaContainerDetectionResult loadFromLines(String[] lines) {
    String trackTitle = null;

    for (String line : lines) {
      if (line.startsWith("#EXTINF")) {
        trackTitle = extractTitleFromInfo(line);
      } else if (!line.startsWith("#") && line.length() > 0) {
        if (line.startsWith("http://") || line.startsWith("https://")) {
          return new MediaContainerDetectionResult(this, new AudioReference(line.trim(), trackTitle));
        }

        trackTitle = null;
      }
    }

    return new MediaContainerDetectionResult(this, "The playlist file contains no links.");
  }

  private String extractTitleFromInfo(String infoLine) {
    String[] splitInfo = infoLine.split(",", 2);
    return splitInfo.length == 2 ? splitInfo[1] : null;
  }

  @Override
  public AudioTrack createTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
    throw new UnsupportedOperationException();
  }
}
