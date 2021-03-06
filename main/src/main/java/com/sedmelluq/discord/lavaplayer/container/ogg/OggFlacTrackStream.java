package com.sedmelluq.discord.lavaplayer.container.ogg;

import com.sedmelluq.discord.lavaplayer.container.flac.FlacTrackInfo;
import com.sedmelluq.discord.lavaplayer.container.flac.frame.FlacFrameReader;
import com.sedmelluq.discord.lavaplayer.filter.FilterChainBuilder;
import com.sedmelluq.discord.lavaplayer.filter.SplitShortPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.tools.io.BitStreamReader;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.io.IOException;

/**
 * OGG stream handler for FLAC codec.
 */
public class OggFlacTrackStream implements OggTrackStream {
  private final FlacTrackInfo info;
  private final OggPacketInputStream packetInputStream;
  private final BitStreamReader bitStreamReader;
  private final int[] decodingBuffer;
  private final int[][] rawSampleBuffers;
  private final short[][] sampleBuffers;
  private SplitShortPcmAudioFilter downstream;

  /**
   * @param info FLAC track info
   * @param packetInputStream OGG packet input stream
   */
  public OggFlacTrackStream(FlacTrackInfo info, OggPacketInputStream packetInputStream) {
    this.info = info;
    this.packetInputStream = packetInputStream;
    this.bitStreamReader = new BitStreamReader(packetInputStream);
    this.decodingBuffer = new int[FlacFrameReader.TEMPORARY_BUFFER_SIZE];
    this.rawSampleBuffers = new int[info.stream.channelCount][];
    this.sampleBuffers = new short[info.stream.channelCount][];

    for (int i = 0; i < rawSampleBuffers.length; i++) {
      rawSampleBuffers[i] = new int[info.stream.maximumBlockSize];
      sampleBuffers[i] = new short[info.stream.maximumBlockSize];
    }
  }

  @Override
  public void initialise(AudioProcessingContext context) {
    downstream = FilterChainBuilder.forSplitShortPcm(context, info.stream.sampleRate);
  }

  @Override
  public void provideFrames() throws InterruptedException {
    try {
      while (packetInputStream.startNewPacket()) {
        int sampleCount = readFlacFrame();

        if (sampleCount == 0) {
          throw new IllegalStateException("Not enough bytes in packet.");
        }

        downstream.process(sampleBuffers, 0, sampleCount);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int readFlacFrame() throws IOException {
    return FlacFrameReader.readFlacFrame(packetInputStream, bitStreamReader, info.stream, rawSampleBuffers, sampleBuffers, decodingBuffer);
  }

  @Override
  public void seekToTimecode(long timecode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    if (downstream != null) {
      downstream.close();
    }
  }
}
