/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.codecs.lucene90.compressing;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.compressing.MatchingReaders;
import org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsReader.SerializedDocument;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DocIDMerger;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.packed.PackedInts;

/**
 * {@link StoredFieldsWriter} impl for {@link Lucene90CompressingStoredFieldsFormat}.
 *
 * @lucene.experimental
 */
public final class Lucene90CompressingStoredFieldsWriter extends StoredFieldsWriter {

  /** Extension of stored fields file */
  public static final String FIELDS_EXTENSION = "fdt";
  /** Extension of stored fields index */
  public static final String INDEX_EXTENSION = "fdx";
  /** Extension of stored fields meta */
  public static final String META_EXTENSION = "fdm";
  /** Codec name for the index. */
  public static final String INDEX_CODEC_NAME = "Lucene90FieldsIndex";

  static final int STRING = 0x00;
  static final int BYTE_ARR = 0x01;
  static final int NUMERIC_INT = 0x02;
  static final int NUMERIC_FLOAT = 0x03;
  static final int NUMERIC_LONG = 0x04;
  static final int NUMERIC_DOUBLE = 0x05;

  static final int TYPE_BITS = PackedInts.bitsRequired(NUMERIC_DOUBLE);
  static final int TYPE_MASK = (int) PackedInts.maxValue(TYPE_BITS);

  static final int VERSION_START = 1;
  static final int VERSION_CURRENT = VERSION_START;
  static final int META_VERSION_START = 0;

  private final String segment;
  private FieldsIndexWriter indexWriter;
  private IndexOutput metaStream, fieldsStream;

  private Compressor compressor;
  private final CompressionMode compressionMode;
  private final int chunkSize;
  private final int maxDocsPerChunk;

  private final ByteBuffersDataOutput bufferedDocs;
  private int[] numStoredFields; // number of stored fields
  private int[] endOffsets; // end offsets in bufferedDocs
  private int docBase; // doc ID at the beginning of the chunk
  private int numBufferedDocs; // docBase + numBufferedDocs == current doc ID

  private long numChunks;
  private long numDirtyChunks; // number of incomplete compressed blocks written
  private long numDirtyDocs; // cumulative number of missing docs in incomplete chunks

  /** Sole constructor. */
  Lucene90CompressingStoredFieldsWriter(
      Directory directory,
      SegmentInfo si,
      String segmentSuffix,
      IOContext context,
      String formatName,
      CompressionMode compressionMode,
      int chunkSize,
      int maxDocsPerChunk,
      int blockShift)
      throws IOException {
    assert directory != null;
    this.segment = si.name;
    this.compressionMode = compressionMode;
    this.compressor = compressionMode.newCompressor();
    this.chunkSize = chunkSize;
    this.maxDocsPerChunk = maxDocsPerChunk;
    this.docBase = 0;
    this.bufferedDocs = ByteBuffersDataOutput.newResettableInstance();
    this.numStoredFields = new int[16];
    this.endOffsets = new int[16];
    this.numBufferedDocs = 0;

    boolean success = false;
    try {
      metaStream =
          directory.createOutput(
              IndexFileNames.segmentFileName(segment, segmentSuffix, META_EXTENSION), context);
      CodecUtil.writeIndexHeader(
          metaStream, INDEX_CODEC_NAME + "Meta", VERSION_CURRENT, si.getId(), segmentSuffix);
      assert CodecUtil.indexHeaderLength(INDEX_CODEC_NAME + "Meta", segmentSuffix)
          == metaStream.getFilePointer();

      fieldsStream =
          directory.createOutput(
              IndexFileNames.segmentFileName(segment, segmentSuffix, FIELDS_EXTENSION), context);
      CodecUtil.writeIndexHeader(
          fieldsStream, formatName, VERSION_CURRENT, si.getId(), segmentSuffix);
      assert CodecUtil.indexHeaderLength(formatName, segmentSuffix)
          == fieldsStream.getFilePointer();

      indexWriter =
          new FieldsIndexWriter(
              directory,
              segment,
              segmentSuffix,
              INDEX_EXTENSION,
              INDEX_CODEC_NAME,
              si.getId(),
              blockShift,
              context);

      metaStream.writeVInt(chunkSize);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(metaStream, fieldsStream, indexWriter);
      }
    }
  }

  @Override
  public void close() throws IOException {
    try {
      IOUtils.close(metaStream, fieldsStream, indexWriter, compressor);
    } finally {
      metaStream = null;
      fieldsStream = null;
      indexWriter = null;
      compressor = null;
    }
  }

  private int numStoredFieldsInDoc;

  @Override
  public void startDocument() throws IOException {}

  @Override
  public void finishDocument() throws IOException {
    if (numBufferedDocs == this.numStoredFields.length) {
      final int newLength = ArrayUtil.oversize(numBufferedDocs + 1, 4);
      this.numStoredFields = ArrayUtil.growExact(this.numStoredFields, newLength);
      endOffsets = ArrayUtil.growExact(endOffsets, newLength);
    }
    this.numStoredFields[numBufferedDocs] = numStoredFieldsInDoc;
    numStoredFieldsInDoc = 0;
    endOffsets[numBufferedDocs] = Math.toIntExact(bufferedDocs.size());
    ++numBufferedDocs;
    if (triggerFlush()) {
      flush();
    }
  }

  private static void saveInts(int[] values, int length, DataOutput out) throws IOException {
    if (length == 1) {
      out.writeVInt(values[0]);
    } else {
      StoredFieldsInts.writeInts(values, 0, length, out);
    }
  }

  private void writeHeader(
      int docBase, int numBufferedDocs, int[] numStoredFields, int[] lengths, boolean sliced)
      throws IOException {
    final int slicedBit = sliced ? 1 : 0;

    // save docBase and numBufferedDocs
    fieldsStream.writeVInt(docBase);
    fieldsStream.writeVInt((numBufferedDocs) << 1 | slicedBit);

    // save numStoredFields
    saveInts(numStoredFields, numBufferedDocs, fieldsStream);

    // save lengths
    saveInts(lengths, numBufferedDocs, fieldsStream);
  }

  private boolean triggerFlush() {
    return bufferedDocs.size() >= chunkSize
        || // chunks of at least chunkSize bytes
        numBufferedDocs >= maxDocsPerChunk;
  }

  private void flush() throws IOException {
    numChunks++;
    indexWriter.writeIndex(numBufferedDocs, fieldsStream.getFilePointer());

    // transform end offsets into lengths
    final int[] lengths = endOffsets;
    for (int i = numBufferedDocs - 1; i > 0; --i) {
      lengths[i] = endOffsets[i] - endOffsets[i - 1];
      assert lengths[i] >= 0;
    }
    final boolean sliced = bufferedDocs.size() >= 2 * chunkSize;
    writeHeader(docBase, numBufferedDocs, numStoredFields, lengths, sliced);

    // compress stored fields to fieldsStream.
    //
    // TODO: do we need to slice it since we already have the slices in the buffer? Perhaps
    // we should use max-block-bits restriction on the buffer itself, then we won't have to check it
    // here.
    byte[] content = bufferedDocs.toArrayCopy();
    bufferedDocs.reset();

    if (sliced) {
      // big chunk, slice it
      for (int compressed = 0; compressed < content.length; compressed += chunkSize) {
        compressor.compress(
            content, compressed, Math.min(chunkSize, content.length - compressed), fieldsStream);
      }
    } else {
      compressor.compress(content, 0, content.length, fieldsStream);
    }

    // reset
    docBase += numBufferedDocs;
    numBufferedDocs = 0;
    bufferedDocs.reset();
  }

  @Override
  public void writeField(FieldInfo info, IndexableField field) throws IOException {

    ++numStoredFieldsInDoc;

    int bits = 0;
    final BytesRef bytes;
    final String string;

    Number number = field.numericValue();
    if (number != null) {
      if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
        bits = NUMERIC_INT;
      } else if (number instanceof Long) {
        bits = NUMERIC_LONG;
      } else if (number instanceof Float) {
        bits = NUMERIC_FLOAT;
      } else if (number instanceof Double) {
        bits = NUMERIC_DOUBLE;
      } else {
        throw new IllegalArgumentException("cannot store numeric type " + number.getClass());
      }
      string = null;
      bytes = null;
    } else {
      bytes = field.binaryValue();
      if (bytes != null) {
        bits = BYTE_ARR;
        string = null;
      } else {
        bits = STRING;
        string = field.stringValue();
        if (string == null) {
          throw new IllegalArgumentException(
              "field "
                  + field.name()
                  + " is stored but does not have binaryValue, stringValue nor numericValue");
        }
      }
    }

    final long infoAndBits = (((long) info.number) << TYPE_BITS) | bits;
    bufferedDocs.writeVLong(infoAndBits);

    if (bytes != null) {
      bufferedDocs.writeVInt(bytes.length);
      bufferedDocs.writeBytes(bytes.bytes, bytes.offset, bytes.length);
    } else if (string != null) {
      bufferedDocs.writeString(string);
    } else {
      if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
        bufferedDocs.writeZInt(number.intValue());
      } else if (number instanceof Long) {
        writeTLong(bufferedDocs, number.longValue());
      } else if (number instanceof Float) {
        writeZFloat(bufferedDocs, number.floatValue());
      } else if (number instanceof Double) {
        writeZDouble(bufferedDocs, number.doubleValue());
      } else {
        throw new AssertionError("Cannot get here");
      }
    }
  }

  // -0 isn't compressed.
  static final int NEGATIVE_ZERO_FLOAT = Float.floatToIntBits(-0f);
  static final long NEGATIVE_ZERO_DOUBLE = Double.doubleToLongBits(-0d);

  // for compression of timestamps
  static final long SECOND = 1000L;
  static final long HOUR = 60 * 60 * SECOND;
  static final long DAY = 24 * HOUR;
  static final int SECOND_ENCODING = 0x40;
  static final int HOUR_ENCODING = 0x80;
  static final int DAY_ENCODING = 0xC0;

  /**
   * Writes a float in a variable-length format. Writes between one and five bytes. Small integral
   * values typically take fewer bytes.
   *
   * <p>ZFloat --&gt; Header, Bytes*?
   *
   * <ul>
   *   <li>Header --&gt; {@link DataOutput#writeByte Uint8}. When it is equal to 0xFF then the value
   *       is negative and stored in the next 4 bytes. Otherwise if the first bit is set then the
   *       other bits in the header encode the value plus one and no other bytes are read.
   *       Otherwise, the value is a positive float value whose first byte is the header, and 3
   *       bytes need to be read to complete it.
   *   <li>Bytes --&gt; Potential additional bytes to read depending on the header.
   * </ul>
   */
  static void writeZFloat(DataOutput out, float f) throws IOException {
    int intVal = (int) f;
    final int floatBits = Float.floatToIntBits(f);

    if (f == intVal && intVal >= -1 && intVal <= 0x7D && floatBits != NEGATIVE_ZERO_FLOAT) {
      // small integer value [-1..125]: single byte
      out.writeByte((byte) (0x80 | (1 + intVal)));
    } else if ((floatBits >>> 31) == 0) {
      // other positive floats: 4 bytes
      out.writeByte((byte) (floatBits >> 24));
      out.writeShort((short) (floatBits >>> 8));
      out.writeByte((byte) floatBits);
    } else {
      // other negative float: 5 bytes
      out.writeByte((byte) 0xFF);
      out.writeInt(floatBits);
    }
  }

  /**
   * Writes a float in a variable-length format. Writes between one and five bytes. Small integral
   * values typically take fewer bytes.
   *
   * <p>ZFloat --&gt; Header, Bytes*?
   *
   * <ul>
   *   <li>Header --&gt; {@link DataOutput#writeByte Uint8}. When it is equal to 0xFF then the value
   *       is negative and stored in the next 8 bytes. When it is equal to 0xFE then the value is
   *       stored as a float in the next 4 bytes. Otherwise if the first bit is set then the other
   *       bits in the header encode the value plus one and no other bytes are read. Otherwise, the
   *       value is a positive float value whose first byte is the header, and 7 bytes need to be
   *       read to complete it.
   *   <li>Bytes --&gt; Potential additional bytes to read depending on the header.
   * </ul>
   */
  static void writeZDouble(DataOutput out, double d) throws IOException {
    int intVal = (int) d;
    final long doubleBits = Double.doubleToLongBits(d);

    if (d == intVal && intVal >= -1 && intVal <= 0x7C && doubleBits != NEGATIVE_ZERO_DOUBLE) {
      // small integer value [-1..124]: single byte
      out.writeByte((byte) (0x80 | (intVal + 1)));
      return;
    } else if (d == (float) d) {
      // d has an accurate float representation: 5 bytes
      out.writeByte((byte) 0xFE);
      out.writeInt(Float.floatToIntBits((float) d));
    } else if ((doubleBits >>> 63) == 0) {
      // other positive doubles: 8 bytes
      out.writeByte((byte) (doubleBits >> 56));
      out.writeInt((int) (doubleBits >>> 24));
      out.writeShort((short) (doubleBits >>> 8));
      out.writeByte((byte) (doubleBits));
    } else {
      // other negative doubles: 9 bytes
      out.writeByte((byte) 0xFF);
      out.writeLong(doubleBits);
    }
  }

  /**
   * Writes a long in a variable-length format. Writes between one and ten bytes. Small values or
   * values representing timestamps with day, hour or second precision typically require fewer
   * bytes.
   *
   * <p>ZLong --&gt; Header, Bytes*?
   *
   * <ul>
   *   <li>Header --&gt; The first two bits indicate the compression scheme:
   *       <ul>
   *         <li>00 - uncompressed
   *         <li>01 - multiple of 1000 (second)
   *         <li>10 - multiple of 3600000 (hour)
   *         <li>11 - multiple of 86400000 (day)
   *       </ul>
   *       Then the next bit is a continuation bit, indicating whether more bytes need to be read,
   *       and the last 5 bits are the lower bits of the encoded value. In order to reconstruct the
   *       value, you need to combine the 5 lower bits of the header with a vLong in the next bytes
   *       (if the continuation bit is set to 1). Then {@link BitUtil#zigZagDecode(int)
   *       zigzag-decode} it and finally multiply by the multiple corresponding to the compression
   *       scheme.
   *   <li>Bytes --&gt; Potential additional bytes to read depending on the header.
   * </ul>
   */
  // T for "timestamp"
  static void writeTLong(DataOutput out, long l) throws IOException {
    int header;
    if (l % SECOND != 0) {
      header = 0;
    } else if (l % DAY == 0) {
      // timestamp with day precision
      header = DAY_ENCODING;
      l /= DAY;
    } else if (l % HOUR == 0) {
      // timestamp with hour precision, or day precision with a timezone
      header = HOUR_ENCODING;
      l /= HOUR;
    } else {
      // timestamp with second precision
      header = SECOND_ENCODING;
      l /= SECOND;
    }

    final long zigZagL = BitUtil.zigZagEncode(l);
    header |= (zigZagL & 0x1F); // last 5 bits
    final long upperBits = zigZagL >>> 5;
    if (upperBits != 0) {
      header |= 0x20;
    }
    out.writeByte((byte) header);
    if (upperBits != 0) {
      out.writeVLong(upperBits);
    }
  }

  @Override
  public void finish(FieldInfos fis, int numDocs) throws IOException {
    if (numBufferedDocs > 0) {
      numDirtyChunks++; // incomplete: we had to force this flush
      numDirtyDocs += numBufferedDocs;
      flush();
    } else {
      assert bufferedDocs.size() == 0;
    }
    if (docBase != numDocs) {
      throw new RuntimeException(
          "Wrote " + docBase + " docs, finish called with numDocs=" + numDocs);
    }
    indexWriter.finish(numDocs, fieldsStream.getFilePointer(), metaStream);
    metaStream.writeVLong(numChunks);
    metaStream.writeVLong(numDirtyChunks);
    metaStream.writeVLong(numDirtyDocs);
    CodecUtil.writeFooter(metaStream);
    CodecUtil.writeFooter(fieldsStream);
    assert bufferedDocs.size() == 0;
  }

  // bulk merge is scary: its caused corruption bugs in the past.
  // we try to be extra safe with this impl, but add an escape hatch to
  // have a workaround for undiscovered bugs.
  static final String BULK_MERGE_ENABLED_SYSPROP =
      Lucene90CompressingStoredFieldsWriter.class.getName() + ".enableBulkMerge";
  static final boolean BULK_MERGE_ENABLED;

  static {
    boolean v = true;
    try {
      v = Boolean.parseBoolean(System.getProperty(BULK_MERGE_ENABLED_SYSPROP, "true"));
    } catch (
        @SuppressWarnings("unused")
        SecurityException ignored) {
    }
    BULK_MERGE_ENABLED = v;
  }

  @Override
  public int merge(MergeState mergeState) throws IOException {
    int docCount = 0;
    int numReaders = mergeState.maxDocs.length;

    MatchingReaders matching = new MatchingReaders(mergeState);
    if (mergeState.needsIndexSort) {
      /**
       * If all readers are compressed and they have the same fieldinfos then we can merge the
       * serialized document directly.
       */
      List<CompressingStoredFieldsMergeSub> subs = new ArrayList<>();
      for (int i = 0; i < mergeState.storedFieldsReaders.length; i++) {
        if (matching.matchingReaders[i]
            && mergeState.storedFieldsReaders[i] instanceof Lucene90CompressingStoredFieldsReader) {
          Lucene90CompressingStoredFieldsReader storedFieldsReader =
              (Lucene90CompressingStoredFieldsReader) mergeState.storedFieldsReaders[i];
          storedFieldsReader.checkIntegrity();
          subs.add(
              new CompressingStoredFieldsMergeSub(
                  storedFieldsReader, mergeState.docMaps[i], mergeState.maxDocs[i]));
        } else {
          return super.merge(mergeState);
        }
      }

      final DocIDMerger<CompressingStoredFieldsMergeSub> docIDMerger = DocIDMerger.of(subs, true);
      while (true) {
        CompressingStoredFieldsMergeSub sub = docIDMerger.next();
        if (sub == null) {
          break;
        }
        assert sub.mappedDocID == docCount;
        SerializedDocument doc = sub.reader.document(sub.docID);
        startDocument();
        bufferedDocs.copyBytes(doc.in, doc.length);
        numStoredFieldsInDoc = doc.numStoredFields;
        finishDocument();
        ++docCount;
      }
      finish(mergeState.mergeFieldInfos, docCount);
      return docCount;
    }

    for (int readerIndex = 0; readerIndex < numReaders; readerIndex++) {
      MergeVisitor visitor = new MergeVisitor(mergeState, readerIndex);
      Lucene90CompressingStoredFieldsReader matchingFieldsReader = null;
      if (matching.matchingReaders[readerIndex]) {
        final StoredFieldsReader fieldsReader = mergeState.storedFieldsReaders[readerIndex];
        // we can only bulk-copy if the matching reader is also a CompressingStoredFieldsReader
        if (fieldsReader != null && fieldsReader instanceof Lucene90CompressingStoredFieldsReader) {
          matchingFieldsReader = (Lucene90CompressingStoredFieldsReader) fieldsReader;
        }
      }

      final int maxDoc = mergeState.maxDocs[readerIndex];
      final Bits liveDocs = mergeState.liveDocs[readerIndex];

      // if its some other format, or an older version of this format, or safety switch:
      if (matchingFieldsReader == null
          || matchingFieldsReader.getVersion() != VERSION_CURRENT
          || BULK_MERGE_ENABLED == false) {
        // naive merge...
        StoredFieldsReader storedFieldsReader = mergeState.storedFieldsReaders[readerIndex];
        if (storedFieldsReader != null) {
          storedFieldsReader.checkIntegrity();
        }
        for (int docID = 0; docID < maxDoc; docID++) {
          if (liveDocs != null && liveDocs.get(docID) == false) {
            continue;
          }
          startDocument();
          storedFieldsReader.visitDocument(docID, visitor);
          finishDocument();
          ++docCount;
        }
      } else if (matchingFieldsReader.getCompressionMode() == compressionMode
          && matchingFieldsReader.getChunkSize() == chunkSize
          && liveDocs == null
          && !tooDirty(matchingFieldsReader)) {
        // optimized merge, raw byte copy
        // its not worth fine-graining this if there are deletions.

        // if the format is older, its always handled by the naive merge case above
        assert matchingFieldsReader.getVersion() == VERSION_CURRENT;
        matchingFieldsReader.checkIntegrity();

        // flush any pending chunks
        if (numBufferedDocs > 0) {
          numDirtyChunks++; // incomplete: we had to force this flush
          numDirtyDocs += numBufferedDocs;
          flush();
        }

        // iterate over each chunk. we use the stored fields index to find chunk boundaries,
        // read the docstart + doccount from the chunk header (we write a new header, since doc
        // numbers will change),
        // and just copy the bytes directly.
        IndexInput rawDocs = matchingFieldsReader.getFieldsStream();
        FieldsIndex index = matchingFieldsReader.getIndexReader();
        rawDocs.seek(index.getStartPointer(0));
        int docID = 0;
        while (docID < maxDoc) {
          // read header
          int base = rawDocs.readVInt();
          if (base != docID) {
            throw new CorruptIndexException(
                "invalid state: base=" + base + ", docID=" + docID, rawDocs);
          }
          int code = rawDocs.readVInt();

          // write a new index entry and new header for this chunk.
          int bufferedDocs = code >>> 1;
          indexWriter.writeIndex(bufferedDocs, fieldsStream.getFilePointer());
          fieldsStream.writeVInt(docBase); // rebase
          fieldsStream.writeVInt(code);
          docID += bufferedDocs;
          docBase += bufferedDocs;
          docCount += bufferedDocs;

          if (docID > maxDoc) {
            throw new CorruptIndexException(
                "invalid state: base=" + base + ", count=" + bufferedDocs + ", maxDoc=" + maxDoc,
                rawDocs);
          }

          // copy bytes until the next chunk boundary (or end of chunk data).
          // using the stored fields index for this isn't the most efficient, but fast enough
          // and is a source of redundancy for detecting bad things.
          final long end;
          if (docID == maxDoc) {
            end = matchingFieldsReader.getMaxPointer();
          } else {
            end = index.getStartPointer(docID);
          }
          fieldsStream.copyBytes(rawDocs, end - rawDocs.getFilePointer());
        }

        if (rawDocs.getFilePointer() != matchingFieldsReader.getMaxPointer()) {
          throw new CorruptIndexException(
              "invalid state: pos="
                  + rawDocs.getFilePointer()
                  + ", max="
                  + matchingFieldsReader.getMaxPointer(),
              rawDocs);
        }

        // since we bulk merged all chunks, we inherit any dirty ones from this segment.
        numChunks += matchingFieldsReader.getNumChunks();
        numDirtyChunks += matchingFieldsReader.getNumDirtyChunks();
        numDirtyDocs += matchingFieldsReader.getNumDirtyDocs();
      } else {
        // optimized merge, we copy serialized (but decompressed) bytes directly
        // even on simple docs (1 stored field), it seems to help by about 20%

        // if the format is older, its always handled by the naive merge case above
        assert matchingFieldsReader.getVersion() == VERSION_CURRENT;
        matchingFieldsReader.checkIntegrity();

        for (int docID = 0; docID < maxDoc; docID++) {
          if (liveDocs != null && liveDocs.get(docID) == false) {
            continue;
          }
          SerializedDocument doc = matchingFieldsReader.document(docID);
          startDocument();
          bufferedDocs.copyBytes(doc.in, doc.length);
          numStoredFieldsInDoc = doc.numStoredFields;
          finishDocument();
          ++docCount;
        }
      }
    }
    finish(mergeState.mergeFieldInfos, docCount);
    return docCount;
  }

  /**
   * Returns true if we should recompress this reader, even though we could bulk merge compressed
   * data
   *
   * <p>The last chunk written for a segment is typically incomplete, so without recompressing, in
   * some worst-case situations (e.g. frequent reopen with tiny flushes), over time the compression
   * ratio can degrade. This is a safety switch.
   */
  boolean tooDirty(Lucene90CompressingStoredFieldsReader candidate) {
    // A segment is considered dirty only if it has enough dirty docs to make a full block
    // AND more than 1% blocks are dirty.
    return candidate.getNumDirtyDocs() > maxDocsPerChunk
        && candidate.getNumDirtyChunks() * 100 > candidate.getNumChunks();
  }

  private static class CompressingStoredFieldsMergeSub extends DocIDMerger.Sub {
    private final Lucene90CompressingStoredFieldsReader reader;
    private final int maxDoc;
    int docID = -1;

    CompressingStoredFieldsMergeSub(
        Lucene90CompressingStoredFieldsReader reader, MergeState.DocMap docMap, int maxDoc) {
      super(docMap);
      this.maxDoc = maxDoc;
      this.reader = reader;
    }

    @Override
    public int nextDoc() {
      docID++;
      if (docID == maxDoc) {
        return NO_MORE_DOCS;
      } else {
        return docID;
      }
    }
  }

  @Override
  public long ramBytesUsed() {
    return bufferedDocs.ramBytesUsed()
        + numStoredFields.length * Integer.BYTES
        + endOffsets.length * Integer.BYTES;
  }
}
