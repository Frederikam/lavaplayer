package com.sedmelluq.discord.lavaplayer.container.mpeg;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles reading parts of an MP4 file
 */
public class MpegReader {
  /**
   * The input as a DataInput
   */
  public final DataInput data;

  /**
   * The input as a seekable stream
   */
  public final SeekableInputStream seek;

  private final byte[] fourCcBuffer;

  /**
   * @param inputStream Input as a seekable stream
   */
  public MpegReader(SeekableInputStream inputStream) {
    seek = inputStream;
    data = new DataInputStream(inputStream);
    fourCcBuffer = new byte[4];
  }

  /**
   * Reads the header of the next child element. Assumes position is at the start of a header or at the end of the section.
   * @param parent The section from which to read child sections from
   * @return The element if there were any more child elements
   */
  public MpegSectionInfo nextChild(MpegSectionInfo parent) {
    if (parent.offset + parent.length <= seek.getPosition()) {
      return null;
    }

    try {
      long offset = seek.getPosition();
      long length = data.readInt();
      return new MpegSectionInfo(offset, length, readFourCC());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Skip to the end of a section.
   * @param section The section to skip
   */
  public void skip(MpegSectionInfo section) {
    try {
      seek.seek(section.offset + section.length);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Read a FourCC as a string
   * @return The FourCC string
   * @throws IOException When reading the bytes from input fails
   */
  public String readFourCC() throws IOException {
    data.readFully(fourCcBuffer);
    return new String(fourCcBuffer, "ISO-8859-1");
  }

  /**
   * Parse the flags and version for the specified section
   * @param section The section where the flags and version should be parsed
   * @return The section info with version info
   * @throws IOException On a read error
   */
  public MpegVersionedSectionInfo parseFlags(MpegSectionInfo section) throws IOException {
    return parseFlagsForSection(data, section);
  }

  private static MpegVersionedSectionInfo parseFlagsForSection(DataInput in, MpegSectionInfo section) throws IOException {
    int versionAndFlags = in.readInt();
    return new MpegVersionedSectionInfo(section, versionAndFlags >>> 24, versionAndFlags & 0xffffff);
  }

  /**
   * Start a child element handling chain
   * @param parent The parent chain
   * @return The chain
   */
  public Chain in(MpegSectionInfo parent) {
    return new Chain(parent, this);
  }

  /**
   * Child element processing helper class.
   */
  public static class Chain {
    private final MpegSectionInfo parent;
    private final List<Handler> handlers;
    private final MpegReader reader;

    private Chain(MpegSectionInfo parent, MpegReader reader) {
      this.parent = parent;
      this.reader = reader;
      handlers = new ArrayList<>();
    }

    /**
     * @param type The FourCC of the section for which a handler is specified
     * @param handler The handler
     * @return this
     */
    public Chain handle(String type, MpegSectionHandler handler) {
      handle(type, false, handler);
      return this;
    }

    /**
     * @param type The FourCC of the section for which a handler is specified
     * @param finish Whether to stop reading after this section
     * @param handler The handler
     * @return this
     */
    public Chain handle(String type, boolean finish, MpegSectionHandler handler) {
      handlers.add(new Handler(type, finish, handler));
      return this;
    }

    /**
     * @param type The FourCC of the section for which a handler is specified
     * @param handler The handler which expects versioned section info
     * @return this
     */
    public Chain handleVersioned(String type, MpegVersionedSectionHandler handler) {
      handlers.add(new Handler(type, false, handler));
      return this;
    }

    /**
     * @param type The FourCC of the section for which a handler is specified
     * @param finish Whether to stop reading after this section
     * @param handler The handler which expects versioned section info
     * @return this
     */
    public Chain handleVersioned(String type, boolean finish, MpegVersionedSectionHandler handler) {
      handlers.add(new Handler(type, finish, handler));
      return this;
    }

    /**
     * Process the current section with all the handlers specified so far
     * @throws IOException On read error
     */
    public void run() throws IOException {
      MpegSectionInfo child;
      boolean finished = false;

      while (!finished && (child = reader.nextChild(parent)) != null) {
        for (Handler handler : handlers) {
          if (handler.type.equals(child.type) && !handleSection(child, handler)) {
            finished = true;
            break;
          }
        }

        reader.skip(child);
      }
    }

    private boolean handleSection(MpegSectionInfo child, Handler handler) throws IOException {
      if (handler.sectionHandler instanceof MpegVersionedSectionHandler) {
        MpegVersionedSectionInfo versioned = parseFlagsForSection(reader.data, child);
        ((MpegVersionedSectionHandler) handler.sectionHandler).handle(versioned);
      } else {
        ((MpegSectionHandler) handler.sectionHandler).handle(child);
      }

      return !handler.terminator;
    }
  }

  private static class Handler {
    private final String type;
    private final boolean terminator;
    private final Object sectionHandler;

    private Handler(String type, boolean terminator, Object sectionHandler) {
      this.type = type;
      this.terminator = terminator;
      this.sectionHandler = sectionHandler;
    }
  }
}
