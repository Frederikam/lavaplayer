package com.sedmelluq.discord.lavaplayer.source.local;

import com.sedmelluq.discord.lavaplayer.tools.io.ExtendedBufferedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Seekable input stream implementation for local files.
 */
public class LocalSeekableInputStream extends SeekableInputStream {
  private static final Logger log = LoggerFactory.getLogger(LocalSeekableInputStream.class);

  private final FileInputStream inputStream;
  private final FileChannel channel;
  private final ExtendedBufferedInputStream bufferedStream;

  /**
   * @param file File to create a stream for.
   */
  public LocalSeekableInputStream(File file) {
    super(file.length(), 0);

    try {
      inputStream = new FileInputStream(file);
      bufferedStream = new ExtendedBufferedInputStream(inputStream);
      channel = inputStream.getChannel();
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read() throws IOException {
    return bufferedStream.read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return bufferedStream.read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException {
    return bufferedStream.skip(n);
  }

  @Override
  public int available() throws IOException {
    return bufferedStream.available();
  }

  @Override
  public synchronized void reset() throws IOException {
    throw new IOException("mark/reset not supported");
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void close() throws IOException {
    try {
      channel.close();
    } catch (IOException e) {
      log.debug("Failed to close channel", e);
    }
  }

  @Override
  public long getPosition() {
    try {
      return channel.position() - bufferedStream.getBufferedByteCount();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void seekHard(long position) throws IOException {
    channel.position(position);
    bufferedStream.discardBuffer();
  }
}
