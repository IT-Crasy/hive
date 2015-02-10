/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.orc;

import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_ZEROCOPY;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.DiskRange;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.llap.Consumer;
import org.apache.hadoop.hive.llap.DebugUtils;
import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch;
import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch.StreamBuffer;
import org.apache.hadoop.hive.llap.io.api.cache.LlapMemoryBuffer;
import org.apache.hadoop.hive.llap.io.api.cache.LowLevelCache;
import org.apache.hadoop.hive.llap.io.api.orc.OrcBatchKey;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampUtils;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.StringExpr;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.ColumnEncoding;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndex;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndexEntry;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.Stream;
import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument.TruthValue;
import org.apache.hadoop.hive.ql.plan.ExprNodeConstantDesc;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.HiveCharWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.io.HiveVarcharWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.hive.serde2.io.TimestampWritable;
import org.apache.hadoop.hive.serde2.typeinfo.HiveDecimalUtils;
import org.apache.hadoop.hive.shims.HadoopShims.ByteBufferPoolShim;
import org.apache.hadoop.hive.shims.HadoopShims.ZeroCopyReaderShim;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;

public class RecordReaderImpl implements RecordReader {

  private static final Log LOG = LogFactory.getLog(RecordReaderImpl.class);
  private static final boolean isLogTraceEnabled = LOG.isTraceEnabled();

  private final String fileName;
  private final FSDataInputStream file;
  private final long firstRow;
  private final List<StripeInformation> stripes =
    new ArrayList<StripeInformation>();
  private OrcProto.StripeFooter stripeFooter;
  private final long totalRowCount;
  private final CompressionCodec codec;
  private final List<OrcProto.Type> types;
  private final int bufferSize;
  private final boolean[] included;
  private final long rowIndexStride;
  private long rowInStripe = 0;
  private int currentStripe = -1;
  private long rowBaseInStripe = 0;
  private long rowCountInStripe = 0;
  private final Map<StreamName, InStream> streams =
      new HashMap<StreamName, InStream>();
  List<DiskRange> bufferChunks = new ArrayList<DiskRange>(0);
  private final TreeReader reader;
  private final OrcProto.RowIndex[] indexes;
  private List<OrcProto.ColumnEncoding> encodings;
  private List<OrcProto.Stream> streamList;
  private final SargApplier sargApp;
  // an array about which row groups aren't skipped
  private boolean[] includedRowGroups = null;
  private final Configuration conf;

  private final ByteBufferAllocatorPool pool = new ByteBufferAllocatorPool();
  private final ZeroCopyReaderShim zcr;

  // this is an implementation copied from ElasticByteBufferPool in hadoop-2,
  // which lacks a clear()/clean() operation
  public final static class ByteBufferAllocatorPool implements ByteBufferPoolShim {
    private static final class Key implements Comparable<Key> {
      private final int capacity;
      private final long insertionGeneration;

      Key(int capacity, long insertionGeneration) {
        this.capacity = capacity;
        this.insertionGeneration = insertionGeneration;
      }

      @Override
      public int compareTo(Key other) {
        return ComparisonChain.start().compare(capacity, other.capacity)
            .compare(insertionGeneration, other.insertionGeneration).result();
      }

      @Override
      public boolean equals(Object rhs) {
        if (rhs == null) {
          return false;
        }
        try {
          Key o = (Key) rhs;
          return (compareTo(o) == 0);
        } catch (ClassCastException e) {
          return false;
        }
      }

      @Override
      public int hashCode() {
        return new HashCodeBuilder().append(capacity).append(insertionGeneration)
            .toHashCode();
      }
    }

    private final TreeMap<Key, ByteBuffer> buffers = new TreeMap<Key, ByteBuffer>();

    private final TreeMap<Key, ByteBuffer> directBuffers = new TreeMap<Key, ByteBuffer>();

    private long currentGeneration = 0;

    private final TreeMap<Key, ByteBuffer> getBufferTree(boolean direct) {
      return direct ? directBuffers : buffers;
    }

    public void clear() {
      buffers.clear();
      directBuffers.clear();
    }

    @Override
    public ByteBuffer getBuffer(boolean direct, int length) {
      TreeMap<Key, ByteBuffer> tree = getBufferTree(direct);
      Map.Entry<Key, ByteBuffer> entry = tree.ceilingEntry(new Key(length, 0));
      if (entry == null) {
        return direct ? ByteBuffer.allocateDirect(length) : ByteBuffer
            .allocate(length);
      }
      tree.remove(entry.getKey());
      return entry.getValue();
    }

    @Override
    public void putBuffer(ByteBuffer buffer) {
      TreeMap<Key, ByteBuffer> tree = getBufferTree(buffer.isDirect());
      while (true) {
        Key key = new Key(buffer.capacity(), currentGeneration++);
        if (!tree.containsKey(key)) {
          tree.put(key, buffer);
          return;
        }
        // Buffers are indexed by (capacity, generation).
        // If our key is not unique on the first try, we try again
      }
    }
  }

  /**
   * Given a list of column names, find the given column and return the index.
   * @param columnNames the list of potential column names
   * @param columnName the column name to look for
   * @param rootColumn offset the result with the rootColumn
   * @return the column number or -1 if the column wasn't found
   */
  static int findColumns(String[] columnNames,
                         String columnName,
                         int rootColumn) {
    for(int i=0; i < columnNames.length; ++i) {
      if (columnName.equals(columnNames[i])) {
        return i + rootColumn;
      }
    }
    return -1;
  }

  /**
   * Find the mapping from predicate leaves to columns.
   * @param sargLeaves the search argument that we need to map
   * @param columnNames the names of the columns
   * @param rootColumn the offset of the top level row, which offsets the
   *                   result
   * @return an array mapping the sarg leaves to concrete column numbers
   */
  static int[] mapSargColumns(List<PredicateLeaf> sargLeaves,
                             String[] columnNames,
                             int rootColumn) {
    int[] result = new int[sargLeaves.size()];
    Arrays.fill(result, -1);
    for(int i=0; i < result.length; ++i) {
      String colName = sargLeaves.get(i).getColumnName();
      result[i] = findColumns(columnNames, colName, rootColumn);
    }
    return result;
  }

  protected RecordReaderImpl(List<StripeInformation> stripes,
      FileSystem fileSystem,
      Path path,
      Reader.Options options,
      List<OrcProto.Type> types,
      CompressionCodec codec,
      int bufferSize,
      long strideRate,
      Configuration conf
  ) throws IOException {
    this.fileName = path.toString().intern(); // should we normalize this, like DFS would?
    this.file = fileSystem.open(path);
    this.codec = codec;
    this.types = types;
    this.bufferSize = bufferSize;
    this.included = options.getInclude();
    this.conf = conf;
    this.rowIndexStride = strideRate;
    SearchArgument sarg = options.getSearchArgument();
    if (sarg != null && strideRate != 0) {
      sargApp = new SargApplier(sarg, options.getColumnNames(), strideRate);
    } else {
      sargApp = null;
    }
    long rows = 0;
    long skippedRows = 0;
    long offset = options.getOffset();
    long maxOffset = options.getMaxOffset();
    for(StripeInformation stripe: stripes) {
      long stripeStart = stripe.getOffset();
      if (offset > stripeStart) {
        skippedRows += stripe.getNumberOfRows();
      } else if (stripeStart < maxOffset) {
        this.stripes.add(stripe);
        rows += stripe.getNumberOfRows();
      }
    }

    final boolean zeroCopy = (conf != null)
        && (HiveConf.getBoolVar(conf, HIVE_ORC_ZEROCOPY));

    if (zeroCopy
        && (codec == null || ((codec instanceof DirectDecompressionCodec)
            && ((DirectDecompressionCodec) codec).isAvailable()))) {
      /* codec is null or is available */
      this.zcr = ShimLoader.getHadoopShims().getZeroCopyReader(file, pool);
    } else {
      this.zcr = null;
    }

    firstRow = skippedRows;
    totalRowCount = rows;
    reader = createTreeReader(path, 0, types, included, conf);
    indexes = new OrcProto.RowIndex[types.size()];
    advanceToNextRow(reader, 0L, true);
  }

  public static final class PositionProviderImpl implements PositionProvider {
    private final OrcProto.RowIndexEntry entry;
    private int index;

    public PositionProviderImpl(OrcProto.RowIndexEntry entry) {
      this(entry, 0);
    }

    public PositionProviderImpl(OrcProto.RowIndexEntry entry, int startPos) {
      this.entry = entry;
      this.index = startPos;
    }

    @Override
    public long getNext() {
      return entry.getPositions(index++);
    }
  }

  private abstract static class TreeReader {
    protected final Path path;
    protected final int columnId;
    private BitFieldReader present = null;
    protected boolean valuePresent = false;
    protected final Configuration conf;

    TreeReader(Path path, int columnId, Configuration conf) {
      this.path = path;
      this.columnId = columnId;
      this.conf = conf;
    }

    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    IntegerReader createIntegerReader(OrcProto.ColumnEncoding.Kind kind,
        InStream in,
        boolean signed) throws IOException {
      switch (kind) {
      case DIRECT_V2:
      case DICTIONARY_V2:
        boolean skipCorrupt = HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_ORC_SKIP_CORRUPT_DATA);
        return new RunLengthIntegerReaderV2(in, signed, skipCorrupt);
      case DIRECT:
      case DICTIONARY:
        return new RunLengthIntegerReader(in, signed);
      default:
        throw new IllegalArgumentException("Unknown encoding " + kind);
      }
    }

    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encoding
                    ) throws IOException {
      checkEncoding(encoding.get(columnId));
      InStream in = streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.PRESENT));
      if (in == null) {
        present = null;
        valuePresent = true;
      } else {
        present = new BitFieldReader(in, 1);
      }
    }

    /**
     * Seek to the given position.
     * @param index the indexes loaded from the file
     * @throws IOException
     */
    void seek(PositionProvider[] index) throws IOException {
      if (present != null) {
        present.seek(index[columnId]);
      }
    }

    protected long countNonNulls(long rows) throws IOException {
      if (present != null) {
        long result = 0;
        for(long c=0; c < rows; ++c) {
          if (present.next() == 1) {
            result += 1;
          }
        }
        return result;
      } else {
        return rows;
      }
    }

    abstract void skipRows(long rows) throws IOException;

    Object next(Object previous) throws IOException {
      if (present != null) {
        valuePresent = present.next() == 1;
      }
      return previous;
    }
    /**
     * Populates the isNull vector array in the previousVector object based on
     * the present stream values. This function is called from all the child
     * readers, and they all set the values based on isNull field value.
     * @param previousVector The columnVector object whose isNull value is populated
     * @param batchSize Size of the column vector
     * @return
     * @throws IOException
     */
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      ColumnVector result = (ColumnVector) previousVector;
      if (present != null) {
        // Set noNulls and isNull vector of the ColumnVector based on
        // present stream
        result.noNulls = true;
        for (int i = 0; i < batchSize; i++) {
          result.isNull[i] = (present.next() != 1);
          if (result.noNulls && result.isNull[i]) {
            result.noNulls = false;
          }
        }
      } else {
        // There is not present stream, this means that all the values are
        // present.
        result.noNulls = true;
        for (int i = 0; i < batchSize; i++) {
          result.isNull[i] = false;
        }
      }
      return previousVector;
    }
  }

  private static class BooleanTreeReader extends TreeReader {
    private BitFieldReader reader = null;

    BooleanTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                     ) throws IOException {
      super.startStripe(streams, encodings);
      reader = new BitFieldReader(streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)), 1);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      BooleanWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new BooleanWritable();
        } else {
          result = (BooleanWritable) previous;
        }
        result.set(reader.next() == 1);
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }
  }

  private static class ByteTreeReader extends TreeReader{
    private RunLengthByteReader reader = null;

    ByteTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      reader = new RunLengthByteReader(streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      ByteWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new ByteWritable();
        } else {
          result = (ByteWritable) previous;
        }
        result.set(reader.next());
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class ShortTreeReader extends TreeReader{
    private IntegerReader reader = null;

    ShortTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encodings.get(columnId).getKind(), streams.get(name), true);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      ShortWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new ShortWritable();
        } else {
          result = (ShortWritable) previous;
        }
        result.set((short) reader.next());
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class IntTreeReader extends TreeReader{
    private IntegerReader reader = null;

    IntTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encodings.get(columnId).getKind(), streams.get(name), true);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      IntWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new IntWritable();
        } else {
          result = (IntWritable) previous;
        }
        result.set((int) reader.next());
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class LongTreeReader extends TreeReader{
    private IntegerReader reader = null;

    LongTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encodings.get(columnId).getKind(), streams.get(name), true);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      LongWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new LongWritable();
        } else {
          result = (LongWritable) previous;
        }
        result.set(reader.next());
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class FloatTreeReader extends TreeReader{
    private InStream stream;
    private final SerializationUtils utils;

    FloatTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
      this.utils = new SerializationUtils();
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      stream.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      FloatWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new FloatWritable();
        } else {
          result = (FloatWritable) previous;
        }
        result.set(utils.readFloat(stream));
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      DoubleColumnVector result = null;
      if (previousVector == null) {
        result = new DoubleColumnVector();
      } else {
        result = (DoubleColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      for (int i = 0; i < batchSize; i++) {
        if (!result.isNull[i]) {
          result.vector[i] = utils.readFloat(stream);
        } else {

          // If the value is not present then set NaN
          result.vector[i] = Double.NaN;
        }
      }

      // Set isRepeating flag
      result.isRepeating = true;
      for (int i = 0; (i < batchSize - 1 && result.isRepeating); i++) {
        if (result.vector[i] != result.vector[i + 1]) {
          result.isRepeating = false;
        }
      }
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for(int i=0; i < items; ++i) {
        utils.readFloat(stream);
      }
    }
  }

  private static class DoubleTreeReader extends TreeReader{
    private InStream stream;
    private final SerializationUtils utils;

    DoubleTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
      this.utils = new SerializationUtils();
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name =
        new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      stream.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      DoubleWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new DoubleWritable();
        } else {
          result = (DoubleWritable) previous;
        }
        result.set(utils.readDouble(stream));
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      DoubleColumnVector result = null;
      if (previousVector == null) {
        result = new DoubleColumnVector();
      } else {
        result = (DoubleColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      for (int i = 0; i < batchSize; i++) {
        if (!result.isNull[i]) {
          result.vector[i] = utils.readDouble(stream);
        } else {
          // If the value is not present then set NaN
          result.vector[i] = Double.NaN;
        }
      }

      // Set isRepeating flag
      result.isRepeating = true;
      for (int i = 0; (i < batchSize - 1 && result.isRepeating); i++) {
        if (result.vector[i] != result.vector[i + 1]) {
          result.isRepeating = false;
        }
      }
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      stream.skip(items * 8);
    }
  }

  private static class BinaryTreeReader extends TreeReader{
    protected InStream stream;
    protected IntegerReader lengths = null;

    protected final LongColumnVector scratchlcv;

    BinaryTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
      scratchlcv = new LongColumnVector();
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
      lengths = createIntegerReader(encodings.get(columnId).getKind(), streams.get(new
          StreamName(columnId, OrcProto.Stream.Kind.LENGTH)), false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      stream.seek(index[columnId]);
      lengths.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      BytesWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new BytesWritable();
        } else {
          result = (BytesWritable) previous;
        }
        int len = (int) lengths.next();
        result.setSize(len);
        int offset = 0;
        while (len > 0) {
          int written = stream.read(result.getBytes(), offset, len);
          if (written < 0) {
            throw new EOFException("Can't finish byte read from " + stream);
          }
          len -= written;
          offset += written;
        }
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      BytesColumnVector result = null;
      if (previousVector == null) {
        result = new BytesColumnVector();
      } else {
        result = (BytesColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      BytesColumnVectorUtil.readOrcByteArrays(stream, lengths, scratchlcv, result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for(int i=0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }
      stream.skip(lengthToSkip);
    }
  }

  private static class TimestampTreeReader extends TreeReader {
    private IntegerReader data = null;
    private IntegerReader nanos = null;
    private final LongColumnVector nanoVector = new LongColumnVector();

    TimestampTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      data = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.DATA)), true);
      nanos = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.SECONDARY)), false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      data.seek(index[columnId]);
      nanos.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      TimestampWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new TimestampWritable();
        } else {
          result = (TimestampWritable) previous;
        }
        Timestamp ts = new Timestamp(0);
        long millis = (data.next() + WriterImpl.BASE_TIMESTAMP) *
            WriterImpl.MILLIS_PER_SECOND;
        int newNanos = parseNanos(nanos.next());
        // fix the rounding when we divided by 1000.
        if (millis >= 0) {
          millis += newNanos / 1000000;
        } else {
          millis -= newNanos / 1000000;
        }
        ts.setTime(millis);
        ts.setNanos(newNanos);
        result.set(ts);
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      result.reset();
      Object obj = null;
      for (int i = 0; i < batchSize; i++) {
        obj = next(obj);
        if (obj == null) {
          result.noNulls = false;
          result.isNull[i] = true;
        } else {
          TimestampWritable writable = (TimestampWritable) obj;
          Timestamp  timestamp = writable.getTimestamp();
          result.vector[i] = TimestampUtils.getTimeNanoSec(timestamp);
        }
      }

      return result;
    }

    private static int parseNanos(long serialized) {
      int zeros = 7 & (int) serialized;
      int result = (int) (serialized >>> 3);
      if (zeros != 0) {
        for(int i =0; i <= zeros; ++i) {
          result *= 10;
        }
      }
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      data.skip(items);
      nanos.skip(items);
    }
  }

  private static class DateTreeReader extends TreeReader{
    private IntegerReader reader = null;

    DateTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encodings.get(columnId).getKind(), streams.get(name), true);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      DateWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new DateWritable();
        } else {
          result = (DateWritable) previous;
        }
        result.set((int) reader.next());
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      LongColumnVector result = null;
      if (previousVector == null) {
        result = new LongColumnVector();
      } else {
        result = (LongColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      reader.nextVector(result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class DecimalTreeReader extends TreeReader{
    private InStream valueStream;
    private IntegerReader scaleStream = null;
    private LongColumnVector scratchScaleVector = new LongColumnVector(VectorizedRowBatch.DEFAULT_SIZE);

    private final int precision;
    private final int scale;

    DecimalTreeReader(Path path, int columnId, int precision, int scale, Configuration conf) {
      super(path, columnId, conf);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
    ) throws IOException {
      super.startStripe(streams, encodings);
      valueStream = streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA));
      scaleStream = createIntegerReader(encodings.get(columnId).getKind(), streams.get(
          new StreamName(columnId, OrcProto.Stream.Kind.SECONDARY)), true);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      valueStream.seek(index[columnId]);
      scaleStream.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      HiveDecimalWritable result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new HiveDecimalWritable();
        } else {
          result = (HiveDecimalWritable) previous;
        }
        result.set(HiveDecimal.create(SerializationUtils.readBigInteger(valueStream),
            (int) scaleStream.next()));
        return HiveDecimalUtils.enforcePrecisionScale(result, precision, scale);
      }
      return null;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      DecimalColumnVector result = null;
      if (previousVector == null) {
        result = new DecimalColumnVector(precision, scale);
      } else {
        result = (DecimalColumnVector) previousVector;
      }

      // Save the reference for isNull in the scratch vector
      boolean [] scratchIsNull = scratchScaleVector.isNull;

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      // Read value entries based on isNull entries
      if (result.isRepeating) {
        if (!result.isNull[0]) {
          BigInteger bInt = SerializationUtils.readBigInteger(valueStream);
          short scaleInData = (short) scaleStream.next();
          HiveDecimal dec = HiveDecimal.create(bInt, scaleInData);
          dec = HiveDecimalUtils.enforcePrecisionScale(dec, precision, scale);
          result.set(0, dec);
        }
      } else {
        // result vector has isNull values set, use the same to read scale vector.
        scratchScaleVector.isNull = result.isNull;
        scaleStream.nextVector(scratchScaleVector, batchSize);
        for (int i = 0; i < batchSize; i++) {
          if (!result.isNull[i]) {
            BigInteger bInt = SerializationUtils.readBigInteger(valueStream);
            short scaleInData = (short) scratchScaleVector.vector[i];
            HiveDecimal dec = HiveDecimal.create(bInt, scaleInData);
            dec = HiveDecimalUtils.enforcePrecisionScale(dec, precision, scale);
            result.set(i, dec);
          }
        }
      }
      // Switch back the null vector.
      scratchScaleVector.isNull = scratchIsNull;
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for(int i=0; i < items; i++) {
        SerializationUtils.readBigInteger(valueStream);
      }
      scaleStream.skip(items);
    }
  }

  /**
   * A tree reader that will read string columns. At the start of the
   * stripe, it creates an internal reader based on whether a direct or
   * dictionary encoding was used.
   */
  private static class StringTreeReader extends TreeReader {
    private TreeReader reader;

    StringTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      reader.checkEncoding(encoding);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      // For each stripe, checks the encoding and initializes the appropriate
      // reader
      switch (encodings.get(columnId).getKind()) {
        case DIRECT:
        case DIRECT_V2:
          reader = new StringDirectTreeReader(path, columnId, conf);
          break;
        case DICTIONARY:
        case DICTIONARY_V2:
          reader = new StringDictionaryTreeReader(path, columnId, conf);
          break;
        default:
          throw new IllegalArgumentException("Unsupported encoding " +
              encodings.get(columnId).getKind());
      }
      reader.startStripe(streams, encodings);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      reader.seek(index);
    }

    @Override
    Object next(Object previous) throws IOException {
      return reader.next(previous);
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      return reader.nextVector(previousVector, batchSize);
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skipRows(items);
    }
  }

  // This class collects together very similar methods for reading an ORC vector of byte arrays and
  // creating the BytesColumnVector.
  //
   public static class BytesColumnVectorUtil {

    private static byte[] commonReadByteArrays(InStream stream, IntegerReader lengths, LongColumnVector scratchlcv,
            BytesColumnVector result, long batchSize) throws IOException {
      // Read lengths
      scratchlcv.isNull = result.isNull;  // Notice we are replacing the isNull vector here...
      lengths.nextVector(scratchlcv, batchSize);
      int totalLength = 0;
      if (!scratchlcv.isRepeating) {
        for (int i = 0; i < batchSize; i++) {
          if (!scratchlcv.isNull[i]) {
            totalLength += (int) scratchlcv.vector[i];
          }
        }
      } else {
        if (!scratchlcv.isNull[0]) {
          totalLength = (int) (batchSize * scratchlcv.vector[0]);
        }
      }

      // Read all the strings for this batch
      byte[] allBytes = new byte[totalLength];
      int offset = 0;
      int len = totalLength;
      while (len > 0) {
        int bytesRead = stream.read(allBytes, offset, len);
        if (bytesRead < 0) {
          throw new EOFException("Can't finish byte read from " + stream);
        }
        len -= bytesRead;
        offset += bytesRead;
      } 

      return allBytes;
    }

    // This method has the common code for reading in bytes into a BytesColumnVector.
    public static void readOrcByteArrays(InStream stream, IntegerReader lengths, LongColumnVector scratchlcv,
            BytesColumnVector result, long batchSize) throws IOException {

      byte[] allBytes = commonReadByteArrays(stream, lengths, scratchlcv, result, batchSize);

      // Too expensive to figure out 'repeating' by comparisons.
      result.isRepeating = false;
      int offset = 0;
      if (!scratchlcv.isRepeating) {
        for (int i = 0; i < batchSize; i++) {
          if (!scratchlcv.isNull[i]) {
            result.setRef(i, allBytes, offset, (int) scratchlcv.vector[i]);
            offset += scratchlcv.vector[i];
          } else {
            result.setRef(i, allBytes, 0, 0);
          }
        }
      } else {
        for (int i = 0; i < batchSize; i++) {
          if (!scratchlcv.isNull[i]) {
            result.setRef(i, allBytes, offset, (int) scratchlcv.vector[0]);
            offset += scratchlcv.vector[0];
          } else {
            result.setRef(i, allBytes, 0, 0);
          }
        }
      }
    }
  }

  /**
   * A reader for string columns that are direct encoded in the current
   * stripe.
   */
  private static class StringDirectTreeReader extends TreeReader {
    private InStream stream;
    private IntegerReader lengths;

    private final LongColumnVector scratchlcv;

    StringDirectTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
      scratchlcv = new LongColumnVector();
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DATA);
      stream = streams.get(name);
      lengths = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(new StreamName(columnId, OrcProto.Stream.Kind.LENGTH)),
          false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      stream.seek(index[columnId]);
      lengths.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      Text result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new Text();
        } else {
          result = (Text) previous;
        }
        int len = (int) lengths.next();
        int offset = 0;
        byte[] bytes = new byte[len];
        while (len > 0) {
          int written = stream.read(bytes, offset, len);
          if (written < 0) {
            throw new EOFException("Can't finish byte read from " + stream);
          }
          len -= written;
          offset += written;
        }
        result.set(bytes);
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      BytesColumnVector result = null;
      if (previousVector == null) {
        result = new BytesColumnVector();
      } else {
        result = (BytesColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      BytesColumnVectorUtil.readOrcByteArrays(stream, lengths, scratchlcv, result, batchSize);
      return result;
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long lengthToSkip = 0;
      for(int i=0; i < items; ++i) {
        lengthToSkip += lengths.next();
      }
      stream.skip(lengthToSkip);
    }
  }

  /**
   * A reader for string columns that are dictionary encoded in the current
   * stripe.
   */
  private static class StringDictionaryTreeReader extends TreeReader {
    private DynamicByteArray dictionaryBuffer;
    private int[] dictionaryOffsets;
    private IntegerReader reader;

    private byte[] dictionaryBufferInBytesCache = null;
    private final LongColumnVector scratchlcv;

    StringDictionaryTreeReader(Path path, int columnId, Configuration conf) {
      super(path, columnId, conf);
      scratchlcv = new LongColumnVector();
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY &&
          encoding.getKind() != OrcProto.ColumnEncoding.Kind.DICTIONARY_V2) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);

      // read the dictionary blob
      int dictionarySize = encodings.get(columnId).getDictionarySize();
      StreamName name = new StreamName(columnId,
          OrcProto.Stream.Kind.DICTIONARY_DATA);
      InStream in = streams.get(name);
      if (in != null) { // Guard against empty dictionary stream.
        if (in.available() > 0) {
          dictionaryBuffer = new DynamicByteArray(64, in.available());
          dictionaryBuffer.readAll(in);
          // Since its start of strip invalidate the cache.
          dictionaryBufferInBytesCache = null;
        }
        in.close();
      } else {
        dictionaryBuffer = null;
      }

      // read the lengths
      name = new StreamName(columnId, OrcProto.Stream.Kind.LENGTH);
      in = streams.get(name);
      if (in != null) { // Guard against empty LENGTH stream.
        IntegerReader lenReader = createIntegerReader(encodings.get(columnId)
            .getKind(), in, false);
        int offset = 0;
        if (dictionaryOffsets == null ||
            dictionaryOffsets.length < dictionarySize + 1) {
          dictionaryOffsets = new int[dictionarySize + 1];
        }
        for (int i = 0; i < dictionarySize; ++i) {
          dictionaryOffsets[i] = offset;
          offset += (int) lenReader.next();
        }
        dictionaryOffsets[dictionarySize] = offset;
        in.close();
      }

      // set up the row reader
      name = new StreamName(columnId, OrcProto.Stream.Kind.DATA);
      reader = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(name), false);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      reader.seek(index[columnId]);
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      Text result = null;
      if (valuePresent) {
        int entry = (int) reader.next();
        if (previous == null) {
          result = new Text();
        } else {
          result = (Text) previous;
        }
        int offset = dictionaryOffsets[entry];
        int length = getDictionaryEntryLength(entry, offset);
        // If the column is just empty strings, the size will be zero,
        // so the buffer will be null, in that case just return result
        // as it will default to empty
        if (dictionaryBuffer != null) {
          dictionaryBuffer.setText(result, offset, length);
        } else {
          result.clear();
        }
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      BytesColumnVector result = null;
      int offset = 0, length = 0;
      if (previousVector == null) {
        result = new BytesColumnVector();
      } else {
        result = (BytesColumnVector) previousVector;
      }

      // Read present/isNull stream
      super.nextVector(result, batchSize);

      if (dictionaryBuffer != null) {

        // Load dictionaryBuffer into cache.
        if (dictionaryBufferInBytesCache == null) {
          dictionaryBufferInBytesCache = dictionaryBuffer.get();
        }

        // Read string offsets
        scratchlcv.isNull = result.isNull;
        reader.nextVector(scratchlcv, batchSize);
        if (!scratchlcv.isRepeating) {

          // The vector has non-repeating strings. Iterate thru the batch
          // and set strings one by one
          for (int i = 0; i < batchSize; i++) {
            if (!scratchlcv.isNull[i]) {
              offset = dictionaryOffsets[(int) scratchlcv.vector[i]];
              length = getDictionaryEntryLength((int) scratchlcv.vector[i], offset);
              result.setRef(i, dictionaryBufferInBytesCache, offset, length);
            } else {
              // If the value is null then set offset and length to zero (null string)
              result.setRef(i, dictionaryBufferInBytesCache, 0, 0);
            }
          }
        } else {
          // If the value is repeating then just set the first value in the
          // vector and set the isRepeating flag to true. No need to iterate thru and
          // set all the elements to the same value
          offset = dictionaryOffsets[(int) scratchlcv.vector[0]];
          length = getDictionaryEntryLength((int) scratchlcv.vector[0], offset);
          result.setRef(0, dictionaryBufferInBytesCache, offset, length);
        }
        result.isRepeating = scratchlcv.isRepeating;
      } else {
        // Entire stripe contains null strings.
        result.isRepeating = true;
        result.noNulls = false;
        result.isNull[0] = true;
        result.setRef(0, "".getBytes(), 0, 0);
      }
      return result;
    }

    int getDictionaryEntryLength(int entry, int offset) {
      int length = 0;
      // if it isn't the last entry, subtract the offsets otherwise use
      // the buffer length.
      if (entry < dictionaryOffsets.length - 1) {
        length = dictionaryOffsets[entry + 1] - offset;
      } else {
        length = dictionaryBuffer.size() - offset;
      }
      return length;
    }

    @Override
    void skipRows(long items) throws IOException {
      reader.skip(countNonNulls(items));
    }
  }

  private static class CharTreeReader extends StringTreeReader {
    int maxLength;

    CharTreeReader(Path path, int columnId, int maxLength, Configuration conf) {
      super(path, columnId, conf);
      this.maxLength = maxLength;
    }

    @Override
    Object next(Object previous) throws IOException {
      HiveCharWritable result = null;
      if (previous == null) {
        result = new HiveCharWritable();
      } else {
        result = (HiveCharWritable) previous;
      }
      // Use the string reader implementation to populate the internal Text value
      Object textVal = super.next(result.getTextValue());
      if (textVal == null) {
        return null;
      }
      // result should now hold the value that was read in.
      // enforce char length
      result.enforceMaxLength(maxLength);
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      // Get the vector of strings from StringTreeReader, then make a 2nd pass to
      // adjust down the length (right trim and truncate) if necessary.
      BytesColumnVector result = (BytesColumnVector) super.nextVector(previousVector, batchSize);

      int adjustedDownLen;
      if (result.isRepeating) {
        if (result.noNulls || !result.isNull[0]) {
          adjustedDownLen = StringExpr.rightTrimAndTruncate(result.vector[0], result.start[0], result.length[0], maxLength);
          if (adjustedDownLen < result.length[0]) {
            result.setRef(0, result.vector[0], result.start[0], adjustedDownLen);
          }
        }
      } else {
        if (result.noNulls){ 
          for (int i = 0; i < batchSize; i++) {
            adjustedDownLen = StringExpr.rightTrimAndTruncate(result.vector[i], result.start[i], result.length[i], maxLength);
            if (adjustedDownLen < result.length[i]) {
              result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
            }
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!result.isNull[i]) {
              adjustedDownLen = StringExpr.rightTrimAndTruncate(result.vector[i], result.start[i], result.length[i], maxLength);
              if (adjustedDownLen < result.length[i]) {
                result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
              }
            }
          }
        }
      }
      return result;
    }
  }

  private static class VarcharTreeReader extends StringTreeReader {
    int maxLength;

    VarcharTreeReader(Path path, int columnId, int maxLength, Configuration conf) {
      super(path, columnId, conf);
      this.maxLength = maxLength;
    }

    @Override
    Object next(Object previous) throws IOException {
      HiveVarcharWritable result = null;
      if (previous == null) {
        result = new HiveVarcharWritable();
      } else {
        result = (HiveVarcharWritable) previous;
      }
      // Use the string reader implementation to populate the internal Text value
      Object textVal = super.next(result.getTextValue());
      if (textVal == null) {
        return null;
      }
      // result should now hold the value that was read in.
      // enforce varchar length
      result.enforceMaxLength(maxLength);
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      // Get the vector of strings from StringTreeReader, then make a 2nd pass to
      // adjust down the length (truncate) if necessary.
      BytesColumnVector result = (BytesColumnVector) super.nextVector(previousVector, batchSize);

      int adjustedDownLen;
      if (result.isRepeating) {
      if (result.noNulls || !result.isNull[0]) {
          adjustedDownLen = StringExpr.truncate(result.vector[0], result.start[0], result.length[0], maxLength);
          if (adjustedDownLen < result.length[0]) {
            result.setRef(0, result.vector[0], result.start[0], adjustedDownLen);
          }
        }
      } else {
        if (result.noNulls){ 
          for (int i = 0; i < batchSize; i++) {
            adjustedDownLen = StringExpr.truncate(result.vector[i], result.start[i], result.length[i], maxLength);
            if (adjustedDownLen < result.length[i]) {
              result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
            }
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!result.isNull[i]) {
              adjustedDownLen = StringExpr.truncate(result.vector[i], result.start[i], result.length[i], maxLength);
              if (adjustedDownLen < result.length[i]) {
                result.setRef(i, result.vector[i], result.start[i], adjustedDownLen);
              }
            }
          }
        }
      }
      return result;
    }
  }

  private static class StructTreeReader extends TreeReader {
    private final TreeReader[] fields;
    private final String[] fieldNames;
    private final List<TreeReader> readers;

    StructTreeReader(Path path, int columnId,
                     List<OrcProto.Type> types,
                     boolean[] included, Configuration conf) throws IOException {
      super(path, columnId, conf);
      OrcProto.Type type = types.get(columnId);
      int fieldCount = type.getFieldNamesCount();
      this.fields = new TreeReader[fieldCount];
      this.fieldNames = new String[fieldCount];
      this.readers = new ArrayList<TreeReader>();
      for(int i=0; i < fieldCount; ++i) {
        int subtype = type.getSubtypes(i);
        if (included == null || included[subtype]) {
          this.fields[i] = createTreeReader(path, subtype, types, included, conf);
          readers.add(this.fields[i]);
        }
        this.fieldNames[i] = type.getFieldNames(i);
      }
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      for(TreeReader kid: fields) {
        if (kid != null) {
          kid.seek(index);
        }
      }
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      OrcStruct result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new OrcStruct(fields.length);
        } else {
          result = (OrcStruct) previous;

          // If the input format was initialized with a file with a
          // different number of fields, the number of fields needs to
          // be updated to the correct number
          if (result.getNumFields() != fields.length) {
            result.setNumFields(fields.length);
          }
        }
        for(int i=0; i < fields.length; ++i) {
          if (fields[i] != null) {
            result.setFieldValue(i, fields[i].next(result.getFieldValue(i)));
          }
        }
      }
      return result;
    }

    /**
     * @return Total count of <b>non-null</b> field readers.
     */
    int getReaderCount() {
      return readers.size();
    }

    /**
     * @param readerIndex Index among <b>non-null</b> readers. Not a column index!
     * @return The readerIndex-s <b>non-null</b> field reader.
     */
    TreeReader getColumnReader(int readerIndex) {
      return readers.get(readerIndex);
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      ColumnVector[] result = null;
      if (previousVector == null) {
        result = new ColumnVector[fields.length];
      } else {
        result = (ColumnVector[]) previousVector;
      }

      // Read all the members of struct as column vectors
      for (int i = 0; i < fields.length; i++) {
        if (fields[i] != null) {
          if (result[i] == null) {
            result[i] = (ColumnVector) fields[i].nextVector(null, batchSize);
          } else {
            fields[i].nextVector(result[i], batchSize);
          }
        }
      }
      return result;
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      for(TreeReader field: fields) {
        if (field != null) {
          field.startStripe(streams, encodings);
        }
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      for(TreeReader field: fields) {
        if (field != null) {
          field.skipRows(items);
        }
      }
    }
  }

  private static class UnionTreeReader extends TreeReader {
    private final TreeReader[] fields;
    private RunLengthByteReader tags;

    UnionTreeReader(Path path, int columnId,
                    List<OrcProto.Type> types,
                    boolean[] included, Configuration conf) throws IOException {
      super(path, columnId, conf);
      OrcProto.Type type = types.get(columnId);
      int fieldCount = type.getSubtypesCount();
      this.fields = new TreeReader[fieldCount];
      for(int i=0; i < fieldCount; ++i) {
        int subtype = type.getSubtypes(i);
        if (included == null || included[subtype]) {
          this.fields[i] = createTreeReader(path, subtype, types, included, conf);
        }
      }
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      tags.seek(index[columnId]);
      for(TreeReader kid: fields) {
        kid.seek(index);
      }
    }

    @Override
    Object next(Object previous) throws IOException {
      super.next(previous);
      OrcUnion result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new OrcUnion();
        } else {
          result = (OrcUnion) previous;
        }
        byte tag = tags.next();
        Object previousVal = result.getObject();
        result.set(tag, fields[tag].next(tag == result.getTag() ?
            previousVal : null));
      }
      return result;
    }

    @Override
    Object nextVector(Object previousVector, long batchSize) throws IOException {
      throw new UnsupportedOperationException(
          "NextVector is not supported operation for Union type");
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                     ) throws IOException {
      super.startStripe(streams, encodings);
      tags = new RunLengthByteReader(streams.get(new StreamName(columnId,
          OrcProto.Stream.Kind.DATA)));
      for(TreeReader field: fields) {
        if (field != null) {
          field.startStripe(streams, encodings);
        }
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long[] counts = new long[fields.length];
      for(int i=0; i < items; ++i) {
        counts[tags.next()] += 1;
      }
      for(int i=0; i < counts.length; ++i) {
        fields[i].skipRows(counts[i]);
      }
    }
  }

  private static class ListTreeReader extends TreeReader {
    private final TreeReader elementReader;
    private IntegerReader lengths = null;

    ListTreeReader(Path path, int columnId,
                   List<OrcProto.Type> types,
                   boolean[] included, Configuration conf) throws IOException {
      super(path, columnId, conf);
      OrcProto.Type type = types.get(columnId);
      elementReader = createTreeReader(path, type.getSubtypes(0), types,
          included, conf);
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      lengths.seek(index[columnId]);
      elementReader.seek(index);
    }

    @Override
    @SuppressWarnings("unchecked")
    Object next(Object previous) throws IOException {
      super.next(previous);
      List<Object> result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new ArrayList<Object>();
        } else {
          result = (ArrayList<Object>) previous;
        }
        int prevLength = result.size();
        int length = (int) lengths.next();
        // extend the list to the new length
        for(int i=prevLength; i < length; ++i) {
          result.add(null);
        }
        // read the new elements into the array
        for(int i=0; i< length; i++) {
          result.set(i, elementReader.next(i < prevLength ?
              result.get(i) : null));
        }
        // remove any extra elements
        for(int i=prevLength - 1; i >= length; --i) {
          result.remove(i);
        }
      }
      return result;
    }

    @Override
    Object nextVector(Object previous, long batchSize) throws IOException {
      throw new UnsupportedOperationException(
          "NextVector is not supported operation for List type");
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      lengths = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.LENGTH)), false);
      if (elementReader != null) {
        elementReader.startStripe(streams, encodings);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long childSkip = 0;
      for(long i=0; i < items; ++i) {
        childSkip += lengths.next();
      }
      elementReader.skipRows(childSkip);
    }
  }

  private static class MapTreeReader extends TreeReader {
    private final TreeReader keyReader;
    private final TreeReader valueReader;
    private IntegerReader lengths = null;

    MapTreeReader(Path path,
                  int columnId,
                  List<OrcProto.Type> types,
                  boolean[] included, Configuration conf) throws IOException {
      super(path, columnId, conf);
      OrcProto.Type type = types.get(columnId);
      int keyColumn = type.getSubtypes(0);
      int valueColumn = type.getSubtypes(1);
      if (included == null || included[keyColumn]) {
        keyReader = createTreeReader(path, keyColumn, types, included, conf);
      } else {
        keyReader = null;
      }
      if (included == null || included[valueColumn]) {
        valueReader = createTreeReader(path, valueColumn, types, included, conf);
      } else {
        valueReader = null;
      }
    }

    @Override
    void seek(PositionProvider[] index) throws IOException {
      super.seek(index);
      lengths.seek(index[columnId]);
      keyReader.seek(index);
      valueReader.seek(index);
    }

    @Override
    @SuppressWarnings("unchecked")
    Object next(Object previous) throws IOException {
      super.next(previous);
      Map<Object, Object> result = null;
      if (valuePresent) {
        if (previous == null) {
          result = new LinkedHashMap<Object, Object>();
        } else {
          result = (LinkedHashMap<Object, Object>) previous;
        }
        // for now just clear and create new objects
        result.clear();
        int length = (int) lengths.next();
        // read the new elements into the array
        for(int i=0; i< length; i++) {
          result.put(keyReader.next(null), valueReader.next(null));
        }
      }
      return result;
    }

    @Override
    Object nextVector(Object previous, long batchSize) throws IOException {
      throw new UnsupportedOperationException(
          "NextVector is not supported operation for Map type");
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      if ((encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT) &&
          (encoding.getKind() != OrcProto.ColumnEncoding.Kind.DIRECT_V2)) {
        throw new IOException("Unknown encoding " + encoding + " in column " +
            columnId + " of " + path);
      }
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
                     List<OrcProto.ColumnEncoding> encodings
                    ) throws IOException {
      super.startStripe(streams, encodings);
      lengths = createIntegerReader(encodings.get(columnId).getKind(),
          streams.get(new StreamName(columnId,
              OrcProto.Stream.Kind.LENGTH)), false);
      if (keyReader != null) {
        keyReader.startStripe(streams, encodings);
      }
      if (valueReader != null) {
        valueReader.startStripe(streams, encodings);
      }
    }

    @Override
    void skipRows(long items) throws IOException {
      items = countNonNulls(items);
      long childSkip = 0;
      for(long i=0; i < items; ++i) {
        childSkip += lengths.next();
      }
      keyReader.skipRows(childSkip);
      valueReader.skipRows(childSkip);
    }
  }

  private static TreeReader createTreeReader(Path path,
                                             int columnId,
                                             List<OrcProto.Type> types,
                                             boolean[] included,
                                             Configuration conf
                                            ) throws IOException {
    OrcProto.Type type = types.get(columnId);
    switch (type.getKind()) {
      case BOOLEAN:
        return new BooleanTreeReader(path, columnId, conf);
      case BYTE:
        return new ByteTreeReader(path, columnId, conf);
      case DOUBLE:
        return new DoubleTreeReader(path, columnId, conf);
      case FLOAT:
        return new FloatTreeReader(path, columnId, conf);
      case SHORT:
        return new ShortTreeReader(path, columnId, conf);
      case INT:
        return new IntTreeReader(path, columnId, conf);
      case LONG:
        return new LongTreeReader(path, columnId, conf);
      case STRING:
        return new StringTreeReader(path, columnId, conf);
      case CHAR:
        if (!type.hasMaximumLength()) {
          throw new IllegalArgumentException("ORC char type has no length specified");
        }
        return new CharTreeReader(path, columnId, type.getMaximumLength(), conf);
      case VARCHAR:
        if (!type.hasMaximumLength()) {
          throw new IllegalArgumentException("ORC varchar type has no length specified");
        }
        return new VarcharTreeReader(path, columnId, type.getMaximumLength(), conf);
      case BINARY:
        return new BinaryTreeReader(path, columnId, conf);
      case TIMESTAMP:
        return new TimestampTreeReader(path, columnId, conf);
      case DATE:
        return new DateTreeReader(path, columnId, conf);
      case DECIMAL:
        int precision = type.hasPrecision() ? type.getPrecision() : HiveDecimal.SYSTEM_DEFAULT_PRECISION;
        int scale =  type.hasScale()? type.getScale() : HiveDecimal.SYSTEM_DEFAULT_SCALE;
        return new DecimalTreeReader(path, columnId, precision, scale, conf);
      case STRUCT:
        return new StructTreeReader(path, columnId, types, included, conf);
      case LIST:
        return new ListTreeReader(path, columnId, types, included, conf);
      case MAP:
        return new MapTreeReader(path, columnId, types, included, conf);
      case UNION:
        return new UnionTreeReader(path, columnId, types, included, conf);
      default:
        throw new IllegalArgumentException("Unsupported type " +
          type.getKind());
    }
  }

  OrcProto.StripeFooter readStripeFooter(StripeInformation stripe
                                         ) throws IOException {
    long offset = stripe.getOffset() + stripe.getIndexLength() +
        stripe.getDataLength();
    int tailLength = (int) stripe.getFooterLength();

    // read the footer
    ByteBuffer tailBuf = ByteBuffer.allocate(tailLength);
    file.seek(offset);
    file.readFully(tailBuf.array(), tailBuf.arrayOffset(), tailLength);
    return OrcProto.StripeFooter.parseFrom(InStream.create(null, "footer",
        Lists.<DiskRange>newArrayList(new BufferChunk(tailBuf, 0)),
        tailLength, codec, bufferSize, null));
  }

  static enum Location {
    BEFORE, MIN, MIDDLE, MAX, AFTER
  }

  /**
   * Given a point and min and max, determine if the point is before, at the
   * min, in the middle, at the max, or after the range.
   * @param point the point to test
   * @param min the minimum point
   * @param max the maximum point
   * @param <T> the type of the comparision
   * @return the location of the point
   */
  static <T> Location compareToRange(Comparable<T> point, T min, T max) {
    int minCompare = point.compareTo(min);
    if (minCompare < 0) {
      return Location.BEFORE;
    } else if (minCompare == 0) {
      return Location.MIN;
    }
    int maxCompare = point.compareTo(max);
    if (maxCompare > 0) {
      return Location.AFTER;
    } else if (maxCompare == 0) {
      return Location.MAX;
    }
    return Location.MIDDLE;
  }

  /**
   * Get the maximum value out of an index entry.
   * @param index
   *          the index entry
   * @return the object for the maximum value or null if there isn't one
   */
  static Object getMax(ColumnStatistics index) {
    if (index instanceof IntegerColumnStatistics) {
      return ((IntegerColumnStatistics) index).getMaximum();
    } else if (index instanceof DoubleColumnStatistics) {
      return ((DoubleColumnStatistics) index).getMaximum();
    } else if (index instanceof StringColumnStatistics) {
      return ((StringColumnStatistics) index).getMaximum();
    } else if (index instanceof DateColumnStatistics) {
      return ((DateColumnStatistics) index).getMaximum();
    } else if (index instanceof DecimalColumnStatistics) {
      return ((DecimalColumnStatistics) index).getMaximum();
    } else if (index instanceof TimestampColumnStatistics) {
      return ((TimestampColumnStatistics) index).getMaximum();
    } else if (index instanceof BooleanColumnStatistics) {
      if (((BooleanColumnStatistics)index).getTrueCount()!=0) {
        return "true";
      } else {
        return "false";
      }
    } else {
      return null;
    }
  }

  /**
   * Get the minimum value out of an index entry.
   * @param index
   *          the index entry
   * @return the object for the minimum value or null if there isn't one
   */
  static Object getMin(ColumnStatistics index) {
    if (index instanceof IntegerColumnStatistics) {
      return ((IntegerColumnStatistics) index).getMinimum();
    } else if (index instanceof DoubleColumnStatistics) {
      return ((DoubleColumnStatistics) index).getMinimum();
    } else if (index instanceof StringColumnStatistics) {
      return ((StringColumnStatistics) index).getMinimum();
    } else if (index instanceof DateColumnStatistics) {
      return ((DateColumnStatistics) index).getMinimum();
    } else if (index instanceof DecimalColumnStatistics) {
      return ((DecimalColumnStatistics) index).getMinimum();
    } else if (index instanceof TimestampColumnStatistics) {
      return ((TimestampColumnStatistics) index).getMinimum();
    } else if (index instanceof BooleanColumnStatistics) {
      if (((BooleanColumnStatistics)index).getFalseCount()!=0) {
        return "false";
      } else {
        return "true";
      }
    } else {
      return null;
    }
  }

  /**
   * Evaluate a predicate with respect to the statistics from the column
   * that is referenced in the predicate.
   * @param statsProto the statistics for the column mentioned in the predicate
   * @param predicate the leaf predicate we need to evaluation
   * @return the set of truth values that may be returned for the given
   *   predicate.
   */
  static TruthValue evaluatePredicate(OrcProto.ColumnStatistics statsProto,
                                      PredicateLeaf predicate) {
    ColumnStatistics cs = ColumnStatisticsImpl.deserialize(statsProto);
    Object minValue = getMin(cs);
    Object maxValue = getMax(cs);
    return evaluatePredicateRange(predicate, minValue, maxValue, cs.hasNull());
  }

  /**
   * Evaluate a predicate with respect to the statistics from the column
   * that is referenced in the predicate.
   * @param stats the statistics for the column mentioned in the predicate
   * @param predicate the leaf predicate we need to evaluation
   * @return the set of truth values that may be returned for the given
   *   predicate.
   */
  static TruthValue evaluatePredicate(ColumnStatistics stats,
      PredicateLeaf predicate) {
    Object minValue = getMin(stats);
    Object maxValue = getMax(stats);
    return evaluatePredicateRange(predicate, minValue, maxValue, stats.hasNull());
  }

  static TruthValue evaluatePredicateRange(PredicateLeaf predicate, Object min,
      Object max, boolean hasNull) {
    // if we didn't have any values, everything must have been null
    if (min == null) {
      if (predicate.getOperator() == PredicateLeaf.Operator.IS_NULL) {
        return TruthValue.YES;
      } else {
        return TruthValue.NULL;
      }
    }

    Location loc;
    try {
      // Predicate object and stats object can be one of the following base types
      // LONG, DOUBLE, STRING, DATE, DECIMAL
      // Out of these DATE is not implicitly convertible to other types and rest
      // others are implicitly convertible. In cases where DATE cannot be converted
      // the stats object is converted to text and comparison is performed.
      // When STRINGs are converted to other base types, NumberFormat exception
      // can occur in which case TruthValue.YES_NO_NULL value is returned
      Object baseObj = predicate.getLiteral(PredicateLeaf.FileFormat.ORC);
      Object minValue = getConvertedStatsObj(min, baseObj);
      Object maxValue = getConvertedStatsObj(max, baseObj);
      Object predObj = getBaseObjectForComparison(baseObj, minValue);

      switch (predicate.getOperator()) {
      case NULL_SAFE_EQUALS:
        loc = compareToRange((Comparable) predObj, minValue, maxValue);
        if (loc == Location.BEFORE || loc == Location.AFTER) {
          return TruthValue.NO;
        } else {
          return TruthValue.YES_NO;
        }
      case EQUALS:
        loc = compareToRange((Comparable) predObj, minValue, maxValue);
        if (minValue.equals(maxValue) && loc == Location.MIN) {
          return hasNull ? TruthValue.YES_NULL : TruthValue.YES;
        } else if (loc == Location.BEFORE || loc == Location.AFTER) {
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        } else {
          return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
        }
      case LESS_THAN:
        loc = compareToRange((Comparable) predObj, minValue, maxValue);
        if (loc == Location.AFTER) {
          return hasNull ? TruthValue.YES_NULL : TruthValue.YES;
        } else if (loc == Location.BEFORE || loc == Location.MIN) {
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        } else {
          return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
        }
      case LESS_THAN_EQUALS:
        loc = compareToRange((Comparable) predObj, minValue, maxValue);
        if (loc == Location.AFTER || loc == Location.MAX) {
          return hasNull ? TruthValue.YES_NULL : TruthValue.YES;
        } else if (loc == Location.BEFORE) {
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        } else {
          return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
        }
      case IN:
        if (minValue.equals(maxValue)) {
          // for a single value, look through to see if that value is in the
          // set
          for (Object arg : predicate.getLiteralList(PredicateLeaf.FileFormat.ORC)) {
            predObj = getBaseObjectForComparison(arg, minValue);
            loc = compareToRange((Comparable) predObj, minValue, maxValue);
            if (loc == Location.MIN) {
              return hasNull ? TruthValue.YES_NULL : TruthValue.YES;
            }
          }
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        } else {
          // are all of the values outside of the range?
          for (Object arg : predicate.getLiteralList(PredicateLeaf.FileFormat.ORC)) {
            predObj = getBaseObjectForComparison(arg, minValue);
            loc = compareToRange((Comparable) predObj, minValue, maxValue);
            if (loc == Location.MIN || loc == Location.MIDDLE ||
                loc == Location.MAX) {
              return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
            }
          }
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        }
      case BETWEEN:
        List<Object> args = predicate.getLiteralList(PredicateLeaf.FileFormat.ORC);
        Object predObj1 = getBaseObjectForComparison(args.get(0), minValue);

        loc = compareToRange((Comparable) predObj1, minValue, maxValue);
        if (loc == Location.BEFORE || loc == Location.MIN) {
          Object predObj2 = getBaseObjectForComparison(args.get(1), minValue);

          Location loc2 = compareToRange((Comparable) predObj2, minValue, maxValue);
          if (loc2 == Location.AFTER || loc2 == Location.MAX) {
            return hasNull ? TruthValue.YES_NULL : TruthValue.YES;
          } else if (loc2 == Location.BEFORE) {
            return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
          } else {
            return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
          }
        } else if (loc == Location.AFTER) {
          return hasNull ? TruthValue.NO_NULL : TruthValue.NO;
        } else {
          return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
        }
      case IS_NULL:
        // min = null condition above handles the all-nulls YES case
        return hasNull ? TruthValue.YES_NO : TruthValue.NO;
      default:
        return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
      }

      // in case failed conversion, return the default YES_NO_NULL truth value
    } catch (NumberFormatException nfe) {
      return hasNull ? TruthValue.YES_NO_NULL : TruthValue.YES_NO;
    }
  }

  private static Object getBaseObjectForComparison(Object predObj, Object statsObj) {
    if (predObj != null) {
      if (predObj instanceof ExprNodeConstantDesc) {
        predObj = ((ExprNodeConstantDesc) predObj).getValue();
      }
      // following are implicitly convertible
      if (statsObj instanceof Long) {
        if (predObj instanceof Double) {
          return ((Double) predObj).longValue();
        } else if (predObj instanceof HiveDecimal) {
          return ((HiveDecimal) predObj).longValue();
        } else if (predObj instanceof String) {
          return Long.valueOf(predObj.toString());
        }
      } else if (statsObj instanceof Double) {
        if (predObj instanceof Long) {
          return ((Long) predObj).doubleValue();
        } else if (predObj instanceof HiveDecimal) {
          return ((HiveDecimal) predObj).doubleValue();
        } else if (predObj instanceof String) {
          return Double.valueOf(predObj.toString());
        }
      } else if (statsObj instanceof String) {
        return predObj.toString();
      } else if (statsObj instanceof HiveDecimal) {
        if (predObj instanceof Long) {
          return HiveDecimal.create(((Long) predObj));
        } else if (predObj instanceof Double) {
          return HiveDecimal.create(predObj.toString());
        } else if (predObj instanceof String) {
          return HiveDecimal.create(predObj.toString());
        } else if (predObj instanceof BigDecimal) {
          return HiveDecimal.create((BigDecimal)predObj);
        }
      }
    }
    return predObj;
  }

  private static Object getConvertedStatsObj(Object statsObj, Object predObj) {

    // converting between date and other types is not implicit, so convert to
    // text for comparison
    if (((predObj instanceof DateWritable) && !(statsObj instanceof DateWritable))
        || ((statsObj instanceof DateWritable) && !(predObj instanceof DateWritable))) {
      return StringUtils.stripEnd(statsObj.toString(), null);
    }

    if (statsObj instanceof String) {
      return StringUtils.stripEnd(statsObj.toString(), null);
    }
    return statsObj;
  }
  
  public static class SargApplier {
    private final SearchArgument sarg;
    private final List<PredicateLeaf> sargLeaves;
    private final int[] filterColumns;
    private final long rowIndexStride;

    public SargApplier(SearchArgument sarg, String[] columnNames, long rowIndexStride) {
      this.sarg = sarg;
      sargLeaves = sarg.getLeaves();
      filterColumns = mapSargColumns(sargLeaves, columnNames, 0);
      this.rowIndexStride = rowIndexStride;
    }

    /**
     * Pick the row groups that we need to load from the current stripe.
     * @return an array with a boolean for each row group or null if all of the
     *    row groups must be read.
     * @throws IOException
     */
    public boolean[] pickRowGroups(
        StripeInformation stripe, OrcProto.RowIndex[] indexes) throws IOException {
      long rowsInStripe = stripe.getNumberOfRows();
      int groupsInStripe = (int) ((rowsInStripe + rowIndexStride - 1) / rowIndexStride);
      boolean[] result = new boolean[groupsInStripe]; // TODO: avoid alloc?
      TruthValue[] leafValues = new TruthValue[sargLeaves.size()];
      for(int rowGroup=0; rowGroup < result.length; ++rowGroup) {
        for(int pred=0; pred < leafValues.length; ++pred) {
          if (filterColumns[pred] != -1) {
            OrcProto.ColumnStatistics stats =
                indexes[filterColumns[pred]].getEntry(rowGroup).getStatistics();
            leafValues[pred] = evaluatePredicate(stats, sargLeaves.get(pred));
            if (LOG.isDebugEnabled()) {
              LOG.debug("Stats = " + stats);
              LOG.debug("Setting " + sargLeaves.get(pred) + " to " +
                  leafValues[pred]);
            }
          } else {
            // the column is a virtual column
            leafValues[pred] = TruthValue.YES_NO_NULL;
          }
        }
        result[rowGroup] = sarg.evaluate(leafValues).isNeeded();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Row group " + (rowIndexStride * rowGroup) + " to " +
              (rowIndexStride * (rowGroup+1) - 1) + " is " +
              (result[rowGroup] ? "" : "not ") + "included.");
        }
      }

      // if we found something to skip, use the array. otherwise, return null.
      for (boolean b: result) {
        if (!b) {
          return result;
        }
      }
      return null;
    }

  }

  /**
   * Pick the row groups that we need to load from the current stripe.
   * @return an array with a boolean for each row group or null if all of the
   *    row groups must be read.
   * @throws IOException
   */
  protected boolean[] pickRowGroups() throws IOException {
    // if we don't have a sarg or indexes, we read everything
    if (sargApp == null) {
      return null;
    }
    readRowIndex(currentStripe, included);
    return sargApp.pickRowGroups(stripes.get(currentStripe), indexes);
  }

  private void clearStreams() throws IOException {
    // explicit close of all streams to de-ref ByteBuffers
    for(InStream is: streams.values()) {
      is.close();
    }
    if(bufferChunks != null) {
      if (zcr != null) {
        for (DiskRange range : bufferChunks) {
          if (range instanceof BufferChunk) {
            zcr.releaseBuffer(((BufferChunk)range).chunk);
          }
        }
      }
      bufferChunks.clear();
    }
    streams.clear();
  }

  /**
   * Read the current stripe into memory.
   * @throws IOException
   */
  private void readStripe() throws IOException {
    StripeInformation stripe = beginReadStripe();
    includedRowGroups = pickRowGroups();

    // move forward to the first unskipped row
    if (includedRowGroups != null) {
      while (rowInStripe < rowCountInStripe &&
             !includedRowGroups[(int) (rowInStripe / rowIndexStride)]) {
        rowInStripe = Math.min(rowCountInStripe, rowInStripe + rowIndexStride);
      }
    }

    // if we haven't skipped the whole stripe, read the data
    if (rowInStripe < rowCountInStripe) {
      // if we aren't projecting columns or filtering rows, just read it all
      if (included == null && includedRowGroups == null) {
        readAllDataStreams(stripe);
      } else {
        readPartialDataStreams(stripe);
      }
      reader.startStripe(streams, stripeFooter.getColumnsList());
      // if we skipped the first row group, move the pointers forward
      if (rowInStripe != 0) {
        seekToRowEntry(reader, (int) (rowInStripe / rowIndexStride));
      }
    }
  }

  private StripeInformation beginReadStripe() throws IOException {
    StripeInformation stripe = stripes.get(currentStripe);
    stripeFooter = readStripeFooter(stripe);
    clearStreams();
    // setup the position in the stripe
    rowCountInStripe = stripe.getNumberOfRows();
    rowInStripe = 0;
    rowBaseInStripe = 0;
    for(int i=0; i < currentStripe; ++i) {
      rowBaseInStripe += stripes.get(i).getNumberOfRows();
    }
    // reset all of the indexes
    for(int i=0; i < indexes.length; ++i) {
      indexes[i] = null;
    }
    return stripe;
  }

  private void readAllDataStreams(StripeInformation stripe
                                  ) throws IOException {
    long start = stripe.getIndexLength();
    long end = start + stripe.getDataLength();
    // explicitly trigger 1 big read
    LinkedList<DiskRange> rangesToRead = Lists.newLinkedList();
    rangesToRead.add(new DiskRange(start, end));
    if (this.cache != null) {
      cache.getFileData(fileName, rangesToRead, stripe.getOffset());
    }
    readDiskRanges(file, zcr, stripe.getOffset(), rangesToRead, false);
    bufferChunks = rangesToRead;
    List<OrcProto.Stream> streamDescriptions = stripeFooter.getStreamsList();
    createStreams(
        streamDescriptions, bufferChunks, null, codec, bufferSize, streams, cache);
    // TODO: decompressed data from streams should be put in cache
  }

  /**
   * The sections of stripe that we have read.
   * This might not match diskRange - 1 disk range can be multiple buffer chunks, depending on DFS block boundaries.
   */
  public static class BufferChunk extends DiskRange {
    final ByteBuffer chunk;

    BufferChunk(ByteBuffer chunk, long offset) {
      super(offset, offset + chunk.remaining());
      this.chunk = chunk;
    }

    @Override
    public boolean hasData() {
      return chunk != null;
    }

    @Override
    public final String toString() {
      boolean makesSense = chunk.remaining() == (end - offset);
      return "data range [" + offset + ", " + end + "), size: " + chunk.remaining()
          + (makesSense ? "" : "(!)") + " type: " + (chunk.isDirect() ? "direct" : "array-backed");
    }

    @Override
    public DiskRange slice(long offset, long end) {
      assert offset <= end && offset >= this.offset && end <= this.end;
      ByteBuffer sliceBuf = chunk.slice();
      int newPos = chunk.position() + (int)(offset - this.offset);
      int newLimit = chunk.limit() - chunk.position() - (int)(this.end - end);
      sliceBuf.position(newPos);
      sliceBuf.limit(newLimit);
      return new BufferChunk(sliceBuf, offset);
    }

    @Override
    public ByteBuffer getData() {
      return chunk;
    }
  }

  public static class CacheChunk extends DiskRange {
    public LlapMemoryBuffer buffer;
    /** When we get (or allocate+put) memory buffer to cache, it's locked for us once. All is well
     * if we unlock it once; but if we use the same buffer for 2+ RGs, we need to incRef again,
     * or track our own separate refcount so we don't unlock more than once. We do the former.
     * Every time we get or allocate, we put buffer in one cache chunk that is later used by all
     * future lookups that happen to land within this DiskRange. When they call "setReused", they
     * get back previous value. If we have not used this range for any RG yet, we don't need to
     * notify cache; if it's called more than once, we are re-using this buffer and will incref.
     */
    private boolean isReused = false;

    public CacheChunk(LlapMemoryBuffer buffer, long offset, long end) {
      super(offset, end);
      this.buffer = buffer;
    }

    @Override
    public boolean hasData() {
      return buffer != null;
    }

    @Override
    public ByteBuffer getData() {
      return buffer.byteBuffer;
    }

    public boolean setReused() {
      boolean result = isReused;
      isReused = true;
      return result;
    }

    @Override
    public String toString() {
      return "start: " + offset + " end: " + end + " cache buffer: " + buffer;
    }
  }

  private static final int BYTE_STREAM_POSITIONS = 1;
  private static final int RUN_LENGTH_BYTE_POSITIONS = BYTE_STREAM_POSITIONS + 1;
  private static final int BITFIELD_POSITIONS = RUN_LENGTH_BYTE_POSITIONS + 1;
  private static final int RUN_LENGTH_INT_POSITIONS = BYTE_STREAM_POSITIONS + 1;

  /**
   * Get the offset in the index positions for the column that the given
   * stream starts.
   * @param columnEncoding the encoding of the column
   * @param columnType the type of the column
   * @param streamType the kind of the stream
   * @param isCompressed is the file compressed
   * @param hasNulls does the column have a PRESENT stream?
   * @return the number of positions that will be used for that stream
   */
  public static int getIndexPosition(OrcProto.ColumnEncoding.Kind columnEncoding,
                              OrcProto.Type.Kind columnType,
                              OrcProto.Stream.Kind streamType,
                              boolean isCompressed,
                              boolean hasNulls) {
    if (streamType == OrcProto.Stream.Kind.PRESENT) {
      return 0;
    }
    int compressionValue = isCompressed ? 1 : 0;
    int base = hasNulls ? (BITFIELD_POSITIONS + compressionValue) : 0;
    switch (columnType) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case DATE:
      case STRUCT:
      case MAP:
      case LIST:
      case UNION:
        return base;
      case CHAR:
      case VARCHAR:
      case STRING:
        if (columnEncoding == OrcProto.ColumnEncoding.Kind.DICTIONARY ||
            columnEncoding == OrcProto.ColumnEncoding.Kind.DICTIONARY_V2) {
          return base;
        } else {
          if (streamType == OrcProto.Stream.Kind.DATA) {
            return base;
          } else {
            return base + BYTE_STREAM_POSITIONS + compressionValue;
          }
        }
      case BINARY:
        if (streamType == OrcProto.Stream.Kind.DATA) {
          return base;
        }
        return base + BYTE_STREAM_POSITIONS + compressionValue;
      case DECIMAL:
        if (streamType == OrcProto.Stream.Kind.DATA) {
          return base;
        }
        return base + BYTE_STREAM_POSITIONS + compressionValue;
      case TIMESTAMP:
        if (streamType == OrcProto.Stream.Kind.DATA) {
          return base;
        }
        return base + RUN_LENGTH_INT_POSITIONS + compressionValue;
      default:
        throw new IllegalArgumentException("Unknown type " + columnType);
    }
  }

  // for uncompressed streams, what is the most overlap with the following set
  // of rows (long vint literal group).
  static final int WORST_UNCOMPRESSED_SLOP = 2 + 8 * 512;

  /**
   * Is this stream part of a dictionary?
   * @return is this part of a dictionary?
   */
  static boolean isDictionary(OrcProto.Stream.Kind kind,
                              OrcProto.ColumnEncoding encoding) {
    assert kind != OrcProto.Stream.Kind.DICTIONARY_COUNT;
    OrcProto.ColumnEncoding.Kind encodingKind = encoding.getKind();
    return kind == OrcProto.Stream.Kind.DICTIONARY_DATA ||
      (kind == OrcProto.Stream.Kind.LENGTH &&
       (encodingKind == OrcProto.ColumnEncoding.Kind.DICTIONARY ||
        encodingKind == OrcProto.ColumnEncoding.Kind.DICTIONARY_V2));
  }

  /**
   * Plan the ranges of the file that we need to read given the list of
   * columns and row groups.
   * @param streamList the list of streams avaiable
   * @param indexes the indexes that have been loaded
   * @param includedColumns which columns are needed
   * @param includedRowGroups which row groups are needed
   * @param isCompressed does the file have generic compression
   * @param encodings the encodings for each column
   * @param types the types of the columns
   * @param compressionSize the compression block size
   * @return the list of disk ranges that will be loaded
   */
  static LinkedList<DiskRange> planReadPartialDataStreams
      (List<OrcProto.Stream> streamList,
       OrcProto.RowIndex[] indexes,
       boolean[] includedColumns,
       boolean[] includedRowGroups,
       boolean isCompressed,
       List<OrcProto.ColumnEncoding> encodings,
       List<OrcProto.Type> types,
       int compressionSize) {
    LinkedList<DiskRange> result = new LinkedList<DiskRange>();
    long offset = 0;
    // figure out which columns have a present stream
    boolean[] hasNull = findPresentStreamsByColumn(streamList, types);
    DiskRange lastRange = null;
    for (OrcProto.Stream stream : streamList) {
      long length = stream.getLength();
      int column = stream.getColumn();
      OrcProto.Stream.Kind streamKind = stream.getKind();
      if (StreamName.getArea(streamKind) == StreamName.Area.DATA && includedColumns[column]) {
        // if we aren't filtering or it is a dictionary, load it.
        if (includedRowGroups == null || isDictionary(streamKind, encodings.get(column))) {
          lastRange = addEntireStreamToResult(offset, length, lastRange, result);
        } else {
          lastRange = addRgFilteredStreamToResult(stream, includedRowGroups,
              isCompressed, indexes[column], encodings.get(column), types.get(column),
              compressionSize, hasNull[column], offset, length, lastRange, result);
        }
      }
      offset += length;
    }
    return result;
  }

  private static DiskRange addEntireStreamToResult(long offset, long length,
      DiskRange lastRange, LinkedList<DiskRange> result) {
    long end = offset + length;
    if (lastRange != null && overlap(lastRange.offset, lastRange.end, offset, end)) {
      lastRange.offset = Math.min(lastRange.offset, offset);
      lastRange.end = Math.max(lastRange.end, end);
    } else {
      lastRange = new DiskRange(offset, end);
      result.add(lastRange);
    }
    return lastRange;
  }

  private static boolean[] findPresentStreamsByColumn(List<OrcProto.Stream> streamList,
      List<OrcProto.Type> types) {
    boolean[] hasNull = new boolean[types.size()];
    for(OrcProto.Stream stream: streamList) {
      if (stream.getKind() == OrcProto.Stream.Kind.PRESENT) {
        hasNull[stream.getColumn()] = true;
      }
    }
    return hasNull;
  }

  private static DiskRange addRgFilteredStreamToResult(OrcProto.Stream stream,
      boolean[] includedRowGroups, boolean isCompressed, OrcProto.RowIndex index,
      OrcProto.ColumnEncoding encoding, OrcProto.Type type, int compressionSize, boolean hasNull,
      long offset, long length, DiskRange lastRange, LinkedList<DiskRange> result) {
    for (int group = 0; group < includedRowGroups.length; ++group) {
      if (!includedRowGroups[group]) continue;
      int posn = getIndexPosition(
          encoding.getKind(), type.getKind(), stream.getKind(), isCompressed, hasNull);
      long start = index.getEntry(group).getPositions(posn);
      final long nextGroupOffset;
      boolean isLast = group == (includedRowGroups.length - 1);
      nextGroupOffset = isLast ? length : index.getEntry(group + 1).getPositions(posn);

      start += offset;
      long end = offset + estimateRgEndOffset(
          isCompressed, isLast, nextGroupOffset, length, compressionSize);
      if (lastRange != null && overlap(lastRange.offset, lastRange.end, start, end)) {
        lastRange.offset = Math.min(lastRange.offset, start);
        lastRange.end = Math.max(lastRange.end, end);
      } else {
        if (DebugUtils.isTraceOrcEnabled()) {
          LOG.info("Creating new range for RG read; last range (which can include some "
              + "previous RGs) was " + lastRange);
        }
        lastRange = new DiskRange(start, end);
        result.add(lastRange);
      }
    }
    return lastRange;
  }

  private static long estimateRgEndOffset(boolean isCompressed, boolean isLast,
      long nextGroupOffset, long streamLength, int bufferSize) {
    // figure out the worst case last location
    // if adjacent groups have the same compressed block offset then stretch the slop
    // by factor of 2 to safely accommodate the next compression block.
    // One for the current compression block and another for the next compression block.
    long slop = isCompressed ? 2 * (OutStream.HEADER_SIZE + bufferSize) : WORST_UNCOMPRESSED_SLOP;
    return isLast ? streamLength : Math.min(streamLength, nextGroupOffset + slop);
  }

  /**
   * Update the disk ranges to collapse adjacent or overlapping ranges. It
   * assumes that the ranges are sorted.
   * @param ranges the list of disk ranges to merge
   */
  static void mergeDiskRanges(List<DiskRange> ranges) {
    DiskRange prev = null;
    for(int i=0; i < ranges.size(); ++i) {
      DiskRange current = ranges.get(i);
      if (prev != null && overlap(prev.offset, prev.end,
          current.offset, current.end)) {
        prev.offset = Math.min(prev.offset, current.offset);
        prev.end = Math.max(prev.end, current.end);
        ranges.remove(i);
        i -= 1;
      } else {
        prev = current;
      }
    }
  }

  /**
   * Read the list of ranges from the file.
   * @param file the file to read
   * @param base the base of the stripe
   * @param ranges the disk ranges within the stripe to read
   * @return the bytes read for each disk range, which is the same length as
   *    ranges
   * @throws IOException
   */
  static void readDiskRanges(FSDataInputStream file,
                                 ZeroCopyReaderShim zcr,
                                 long base,
                                 LinkedList<DiskRange> ranges,
                                 boolean doForceDirect) throws IOException {
    ListIterator<DiskRange> rangeIter = ranges.listIterator();
    while (rangeIter.hasNext()) {
      DiskRange range = rangeIter.next();
      if (range.hasData()) continue;
      int len = (int) (range.end - range.offset);
      long off = range.offset;
      file.seek(base + off);
      if (zcr != null) {
        boolean hasReplaced = false;
        while (len > 0) {
          ByteBuffer partial = zcr.readBuffer(len, false);
          BufferChunk bc = new BufferChunk(partial, off);
          if (!hasReplaced) {
            rangeIter.set(bc);
            hasReplaced = true;
          } else {
            rangeIter.add(bc);
          }
          int read = partial.remaining();
          len -= read;
          off += read;
        }
      } else if (doForceDirect) {
        ByteBuffer directBuf = ByteBuffer.allocateDirect(len);
        try {
          while (directBuf.remaining() > 0) {
            int count = file.read(directBuf);
            if (count < 0) throw new EOFException();
            directBuf.position(directBuf.position() + count);
          }
        } catch (UnsupportedOperationException ex) {
          LOG.error("Stream does not support direct read; we will copy.");
          byte[] buffer = new byte[len];
          file.readFully(buffer, 0, buffer.length);
          directBuf.put(buffer);
        }
        directBuf.position(0);
        rangeIter.set(new BufferChunk(directBuf, range.offset));
      } else {
        byte[] buffer = new byte[len];
        file.readFully(buffer, 0, buffer.length);
        rangeIter.set(new BufferChunk(ByteBuffer.wrap(buffer), range.offset));
      }
    }
  }

  /**
   * Does region A overlap region B? The end points are inclusive on both sides.
   * @param leftA A's left point
   * @param rightA A's right point
   * @param leftB B's left point
   * @param rightB B's right point
   * @return Does region A overlap region B?
   */
  static boolean overlap(long leftA, long rightA, long leftB, long rightB) {
    if (leftA <= leftB) {
      return rightA >= leftB;
    }
    return rightB >= leftA;
  }

  /**
   * Build a string representation of a list of disk ranges.
   * @param ranges ranges to stringify
   * @return the resulting string
   */
  static String stringifyDiskRanges(List<DiskRange> ranges) {
    StringBuilder buffer = new StringBuilder();
    buffer.append("[");
    for(int i=0; i < ranges.size(); ++i) {
      if (i != 0) {
        buffer.append(", ");
      }
      buffer.append(ranges.get(i).toString());
    }
    buffer.append("]");
    return buffer.toString();
  }

  void createStreams(List<OrcProto.Stream> streamDescriptions,
                            List<DiskRange> ranges,
                            boolean[] includeColumn,
                            CompressionCodec codec,
                            int bufferSize,
                            Map<StreamName, InStream> streams,
                            LowLevelCache cache) throws IOException {
    long streamOffset = 0;
    for (OrcProto.Stream streamDesc: streamDescriptions) {
      int column = streamDesc.getColumn();
      if ((includeColumn != null && !includeColumn[column]) ||
          StreamName.getArea(streamDesc.getKind()) != StreamName.Area.DATA) {
        streamOffset += streamDesc.getLength();
        continue;
      }
      List<DiskRange> buffers = getStreamBuffers(ranges, streamOffset, streamDesc.getLength());
      StreamName name = new StreamName(column, streamDesc.getKind());
      streams.put(name, InStream.create(fileName, name.toString(), buffers,
          streamDesc.getLength(), codec, bufferSize, cache));
      streamOffset += streamDesc.getLength();
    }
  }

  private List<DiskRange> getStreamBuffers(List<DiskRange> ranges, long offset, long length) {
    // This assumes sorted ranges (as do many other parts of ORC code.
    ArrayList<DiskRange> buffers = new ArrayList<DiskRange>();
    long streamEnd = offset + length;
    boolean inRange = false;
    for (DiskRange range : ranges) {
      if (!inRange) {
        if (range.end <= offset) continue; // Skip until we are in range.
        inRange = true;
        if (range.offset < offset) {
          // Partial first buffer, add a slice of it.
          DiskRange partial = range.slice(offset, Math.min(streamEnd, range.end));
          partial.shiftBy(-offset);
          buffers.add(partial);
          if (range.end >= streamEnd) break; // Partial first buffer is also partial last buffer.
          continue;
        }
      } else if (range.offset >= streamEnd) {
        break;
      }
      if (range.end > streamEnd) {
        // Partial last buffer (may also be the first buffer), add a slice of it.
        DiskRange partial = range.slice(range.offset, streamEnd);
        partial.shiftBy(-offset);
        buffers.add(partial);
        break;
      }
      // Buffer that belongs entirely to one stream.
      // TODO: ideally we would want to reuse the object and remove it from the list, but we cannot
      //       because bufferChunks is also used by clearStreams for zcr. Create a useless dup.
      DiskRange full = range.slice(range.offset, range.end);
      full.shiftBy(-offset);
      buffers.add(full);
      if (range.end == streamEnd) break;
    }
    return buffers;
  }

  private LowLevelCache cache = null;
  public void setCache(LowLevelCache cache) {
    this.cache = cache;
  }

  private void readPartialDataStreams(StripeInformation stripe) throws IOException {
    List<OrcProto.Stream> streamList = stripeFooter.getStreamsList();
    LinkedList<DiskRange> rangesToRead =
        planReadPartialDataStreams(streamList,
            indexes, included, includedRowGroups, codec != null,
            stripeFooter.getColumnsList(), types, bufferSize);
    if (LOG.isDebugEnabled()) {
      LOG.debug("chunks = " + stringifyDiskRanges(rangesToRead));
    }
    mergeDiskRanges(rangesToRead);
    if (this.cache != null) {
      cache.getFileData(fileName, rangesToRead, stripe.getOffset());
    }
    readDiskRanges(file, zcr, stripe.getOffset(), rangesToRead, false);
    bufferChunks = rangesToRead;
    if (LOG.isDebugEnabled()) {
      LOG.debug("merge = " + stringifyDiskRanges(rangesToRead));
    }

    createStreams(streamList, bufferChunks, included, codec, bufferSize, streams, cache);
  }

  @Override
  public boolean hasNext() throws IOException {
    return rowInStripe < rowCountInStripe;
  }

  /**
   * Read the next stripe until we find a row that we don't skip.
   * @throws IOException
   */
  private void advanceStripe() throws IOException {
    rowInStripe = rowCountInStripe;
    while (rowInStripe >= rowCountInStripe &&
        currentStripe < stripes.size() - 1) {
      currentStripe += 1;
      readStripe();
    }
  }

  /**
   * Skip over rows that we aren't selecting, so that the next row is
   * one that we will read.
   * @param nextRow the row we want to go to
   * @throws IOException
   */
  private boolean advanceToNextRow(
      TreeReader reader, long nextRow, boolean canAdvanceStripe) throws IOException {
    long nextRowInStripe = nextRow - rowBaseInStripe;
    // check for row skipping
    if (rowIndexStride != 0 &&
        includedRowGroups != null &&
        nextRowInStripe < rowCountInStripe) {
      int rowGroup = (int) (nextRowInStripe / rowIndexStride);
      if (!includedRowGroups[rowGroup]) {
        while (rowGroup < includedRowGroups.length && !includedRowGroups[rowGroup]) {
          rowGroup += 1;
        }
        if (rowGroup >= includedRowGroups.length) {
          if (canAdvanceStripe) {
            advanceStripe();
          }
          return canAdvanceStripe;
        }
        nextRowInStripe = Math.min(rowCountInStripe, rowGroup * rowIndexStride);
      }
    }
    if (nextRowInStripe >= rowCountInStripe) {
      if (canAdvanceStripe) {
        advanceStripe();
      }
      return canAdvanceStripe;
    }
    if (nextRowInStripe != rowInStripe) {
      if (rowIndexStride != 0) {
        int rowGroup = (int) (nextRowInStripe / rowIndexStride);
        seekToRowEntry(reader, rowGroup);
        reader.skipRows(nextRowInStripe - rowGroup * rowIndexStride);
      } else {
        reader.skipRows(nextRowInStripe - rowInStripe);
      }
      rowInStripe = nextRowInStripe;
    }
    return true;
  }

  @Override
  public Object next(Object previous) throws IOException {
    Object result = reader.next(previous);
    // find the next row
    rowInStripe += 1;
    advanceToNextRow(reader, rowInStripe + rowBaseInStripe, true);
    if (isLogTraceEnabled) {
      LOG.trace("row from " + reader.path);
      LOG.trace("orc row = " + result);
    }
    return result;
  }

  @Override
  public VectorizedRowBatch nextBatch(VectorizedRowBatch previous) throws IOException {
    VectorizedRowBatch result = null;
    if (rowInStripe >= rowCountInStripe) {
      currentStripe += 1;
      readStripe();
    }

    long batchSize = computeBatchSize(VectorizedRowBatch.DEFAULT_SIZE);

    rowInStripe += batchSize;
    if (previous == null) {
      ColumnVector[] cols = (ColumnVector[]) reader.nextVector(null, (int) batchSize);
      result = new VectorizedRowBatch(cols.length);
      result.cols = cols;
    } else {
      result = (VectorizedRowBatch) previous;
      result.selectedInUse = false;
      reader.nextVector(result.cols, (int) batchSize);
    }

    result.size = (int) batchSize;
    advanceToNextRow(reader, rowInStripe + rowBaseInStripe, true);
    return result;
  }

  private long computeBatchSize(long targetBatchSize) {
    long batchSize = 0;
    // In case of PPD, batch size should be aware of row group boundaries. If only a subset of row
    // groups are selected then marker position is set to the end of range (subset of row groups
    // within strip). Batch size computed out of marker position makes sure that batch size is
    // aware of row group boundary and will not cause overflow when reading rows
    // illustration of this case is here https://issues.apache.org/jira/browse/HIVE-6287
    if (rowIndexStride != 0 && includedRowGroups != null && rowInStripe < rowCountInStripe) {
      int startRowGroup = (int) (rowInStripe / rowIndexStride);
      if (!includedRowGroups[startRowGroup]) {
        while (startRowGroup < includedRowGroups.length && !includedRowGroups[startRowGroup]) {
          startRowGroup += 1;
        }
      }

      int endRowGroup = startRowGroup;
      while (endRowGroup < includedRowGroups.length && includedRowGroups[endRowGroup]) {
        endRowGroup += 1;
      }

      final long markerPosition = (endRowGroup * rowIndexStride) < rowCountInStripe ? (endRowGroup * rowIndexStride)
          : rowCountInStripe;
      batchSize = Math.min(targetBatchSize, (markerPosition - rowInStripe));

      if (LOG.isDebugEnabled() && batchSize < targetBatchSize) {
        LOG.debug("markerPosition: " + markerPosition + " batchSize: " + batchSize);
      }
    } else {
      batchSize = Math.min(targetBatchSize, (rowCountInStripe - rowInStripe));
    }
    return batchSize;
  }

  @Override
  public void close() throws IOException {
    clearStreams();
    pool.clear();
    file.close();
  }

  @Override
  public long getRowNumber() {
    return rowInStripe + rowBaseInStripe + firstRow;
  }

  /**
   * Return the fraction of rows that have been read from the selected.
   * section of the file
   * @return fraction between 0.0 and 1.0 of rows consumed
   */
  @Override
  public float getProgress() {
    return ((float) rowBaseInStripe + rowInStripe) / totalRowCount;
  }

  private int findStripe(long rowNumber) {
    for(int i=0; i < stripes.size(); i++) {
      StripeInformation stripe = stripes.get(i);
      if (stripe.getNumberOfRows() > rowNumber) {
        return i;
      }
      rowNumber -= stripe.getNumberOfRows();
    }
    throw new IllegalArgumentException("Seek after the end of reader range");
  }

  OrcProto.RowIndex[] readRowIndex(
      int stripeIndex, boolean[] included) throws IOException {
    return readRowIndex(stripeIndex, included, null);
  }

  OrcProto.RowIndex[] readRowIndex(
      int stripeIndex, boolean[] included, OrcProto.RowIndex[] indexes) throws IOException {
    long offset = stripes.get(stripeIndex).getOffset();
    OrcProto.StripeFooter stripeFooter;
    // if this is the current stripe, use the cached objects.
    if (stripeIndex == currentStripe) {
      stripeFooter = this.stripeFooter;
      indexes = indexes == null ? this.indexes : indexes;
    } else {
      stripeFooter = readStripeFooter(stripes.get(stripeIndex));
      indexes = indexes == null ? new OrcProto.RowIndex[this.indexes.length] : indexes;
    }
    for(OrcProto.Stream stream: stripeFooter.getStreamsList()) {
      if (stream.getKind() == OrcProto.Stream.Kind.ROW_INDEX) {
        int col = stream.getColumn();
        if ((included == null || included[col]) && indexes[col] == null) {
          byte[] buffer = new byte[(int) stream.getLength()];
          file.seek(offset);
          file.readFully(buffer);
          indexes[col] = OrcProto.RowIndex.parseFrom(InStream.create(null, "index",
              Lists.<DiskRange>newArrayList(new BufferChunk(ByteBuffer.wrap(buffer), 0)),
              stream.getLength(), codec, bufferSize, null));
        }
      }
      offset += stream.getLength();
    }
    return indexes;
  }

  private void seekToRowEntry(TreeReader reader, int rowEntry) throws IOException {
    PositionProvider[] index = new PositionProvider[indexes.length];
    for (int i = 0; i < indexes.length; ++i) {
      if (indexes[i] != null) {
        index[i] = new PositionProviderImpl(indexes[i].getEntry(rowEntry));
      }
    }
    reader.seek(index);
  }

  @Override
  public void seekToRow(long rowNumber) throws IOException {
    if (rowNumber < 0) {
      throw new IllegalArgumentException("Seek to a negative row number " +
                                         rowNumber);
    } else if (rowNumber < firstRow) {
      throw new IllegalArgumentException("Seek before reader range " +
                                         rowNumber);
    }
    // convert to our internal form (rows from the beginning of slice)
    rowNumber -= firstRow;

    // move to the right stripe
    int rightStripe = findStripe(rowNumber);
    if (rightStripe != currentStripe) {
      currentStripe = rightStripe;
      readStripe();
    }
    readRowIndex(currentStripe, included);

    // if we aren't to the right row yet, advance in the stripe.
    advanceToNextRow(reader, rowNumber, true);
  }

  @Override
  public void prepareEncodedColumnRead() throws IOException {
    assert currentStripe < 1 : "Reader is supposed to be per stripe";
    if (currentStripe == 0) return;
    ++currentStripe;
    beginReadStripe();
  }

  /** Helper context for each column being read */
  private static final class ColumnReadContext {
    public ColumnReadContext(int colIx, ColumnEncoding encoding, RowIndex rowIndex) {
      this.encoding = encoding;
      this.rowIndex = rowIndex;
      this.colIx = colIx;
    }
    public static final int MAX_STREAMS = OrcProto.Stream.Kind.ROW_INDEX_VALUE;
    /** The number of streams that are part of this column. */
    int streamCount = 0;
    final StreamContext[] streams = new StreamContext[MAX_STREAMS];
    /** Column encoding. */
    final ColumnEncoding encoding;
    /** Column rowindex. */
    final OrcProto.RowIndex rowIndex;
    /** Column index in the file. */
    final int colIx;

    public void addStream(long offset, OrcProto.Stream stream, int indexIx) {
      streams[streamCount++] = new StreamContext(stream, offset, indexIx);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(" column_index: ").append(colIx);
      sb.append(" encoding: ").append(encoding);
      sb.append(" stream_count: ").append(streamCount);
      int i = 0;
      for (StreamContext sc : streams) {
        if (sc != null) {
          sb.append(" stream_").append(i).append(":").append(sc.toString());
        }
        i++;
      }
      return sb.toString();
    }
  }

  private static final class StreamContext {
    public StreamContext(OrcProto.Stream stream, long streamOffset, int streamIndexOffset) {
      this.kind = stream.getKind();
      this.length = stream.getLength();
      this.offset = streamOffset;
      this.streamIndexOffset = streamIndexOffset;
    }
    /** Offsets of each stream in the column. */
    public final long offset, length;
    public final int streamIndexOffset;
    public final OrcProto.Stream.Kind kind;
    /** Iterators for the buffers; used to maintain position in per-rg reading. */
    ListIterator<DiskRange> bufferIter;
    /** Saved stripe-level stream, to reuse for each RG (e.g. dictionaries). */
    StreamBuffer stripeLevelStream;

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(" kind: ").append(kind);
      sb.append(" offset: ").append(offset);
      sb.append(" length: ").append(length);
      sb.append(" index_offset: ").append(streamIndexOffset);
      return sb.toString();
    }
  }

  /*
   * TODO: this method could be made static or moved to separate class unrelated to RecordReader.
   *       It's not very well integrated into RecordReader, and violates many RR usage patterns.
   *       The following fields are used; and how to get rid of them:
   * currentStripe - always 0 in current usage.
   * stripes, fileName, codec, zcr, bufferSize - available externally or thru parent Reader
   * rowCountInStripe, file - derived
   * types, encodings, indexes - available externally from cache (for initial caching, reader
   *    is needed to get footer and indexes; or that can be refactored out)
   */
  @Override
  // TODO#: HERE
  public void readEncodedColumns(int stripeIx, boolean[] included, boolean[][] colRgs,
      LowLevelCache cache, Consumer<EncodedColumnBatch<OrcBatchKey>> consumer) throws IOException {
    // Note: for now we don't have to setError here, caller will setError if we throw.
    // We are also not supposed to call setDone, since we are only part of the operation.
    StripeInformation stripe = stripes.get(currentStripe);
    long stripeOffset = stripe.getOffset();
    // TODO: we should have avoided reading the footer if we got metadata from cache.
    List<OrcProto.Stream> streamList =
        this.streamList != null ? this.streamList : stripeFooter.getStreamsList();
    List<ColumnEncoding> encodings =
        this.encodings != null ? this.encodings : stripeFooter.getColumnsList();

    // 1. Figure out what we have to read.
    LinkedList<DiskRange> rangesToRead = new LinkedList<DiskRange>();
    long offset = 0; // Stream offset in relation to the stripe.
    // 1.1. Figure out which columns have a present stream
    boolean[] hasNull = findPresentStreamsByColumn(streamList, types);
    if (DebugUtils.isTraceOrcEnabled()) {
      LOG.info("The following columns have PRESENT streams: " + DebugUtils.toString(hasNull));
    }
    DiskRange lastRange = null;

    // We assume stream list is sorted by column and that non-data
    // streams do not interleave data streams for the same column.
    // 1.2. With that in mind, determine disk ranges to read/get from cache (not by stream).
    int colRgIx = -1, lastColIx = -1;
    ColumnReadContext[] colCtxs = new ColumnReadContext[colRgs.length];
    boolean[] includedRgs = null;
    boolean isCompressed = (codec != null);
    for (OrcProto.Stream stream : streamList) {
      long length = stream.getLength();
      int colIx = stream.getColumn();
      OrcProto.Stream.Kind streamKind = stream.getKind();
      if (!included[colIx] || StreamName.getArea(streamKind) != StreamName.Area.DATA) {
        if (DebugUtils.isTraceOrcEnabled()) {
          LOG.info("Skipping stream: " + streamKind + " at " + offset + ", " + length);
        }
        offset += length;
        continue;
      }
      ColumnReadContext ctx = null;
      if (lastColIx != colIx) {
        ++colRgIx;
        assert colCtxs[colRgIx] == null;
        lastColIx = colIx;
        includedRgs = colRgs[colRgIx];
        ctx = colCtxs[colRgIx] = new ColumnReadContext(
            colIx, encodings.get(colIx), indexes[colIx]);
        if (DebugUtils.isTraceOrcEnabled()) {
          LOG.info("Creating context " + colRgIx + " for column " + colIx + ":" + ctx.toString());
        }
      } else {
        ctx = colCtxs[colRgIx];
        assert ctx != null;
      }
      int indexIx = getIndexPosition(ctx.encoding.getKind(),
          types.get(colIx).getKind(), streamKind, isCompressed, hasNull[colIx]);
      ctx.addStream(offset, stream, indexIx);
      if (DebugUtils.isTraceOrcEnabled()) {
        LOG.info("Adding stream for column " + colIx + ": " + streamKind + " at " + offset
            + ", " + length + ", index position " + indexIx);
      }
      if (includedRgs == null || isDictionary(streamKind, encodings.get(colIx))) {
        lastRange = addEntireStreamToResult(offset, length, lastRange, rangesToRead);
        if (DebugUtils.isTraceOrcEnabled()) {
          LOG.info("Will read whole stream " + streamKind + "; added to " + lastRange);
        }
      } else {
        lastRange = addRgFilteredStreamToResult(stream, includedRgs,
            codec != null, indexes[colIx], encodings.get(colIx), types.get(colIx),
            bufferSize, hasNull[colIx], offset, length, lastRange, rangesToRead);
      }
      offset += length;
    }

    // 2. Now, read all of the ranges from cache or disk.
    if (DebugUtils.isTraceOrcEnabled()) {
      LOG.info("Resulting disk ranges to read: " + stringifyDiskRanges(rangesToRead));
    }
    if (cache != null) {
      cache.getFileData(fileName, rangesToRead, stripeOffset);
      if (DebugUtils.isTraceOrcEnabled()) {
        LOG.info("Disk ranges after cache (base offset " + stripeOffset
            + "): " + stringifyDiskRanges(rangesToRead));
      }
    }
    // Force direct buffers, since we will be decompressing to cache.
    readDiskRanges(file, zcr, stripeOffset, rangesToRead, true);

    // 2.1. Separate buffers (relative to stream offset) for each stream from the data we have.
    // TODO: given how we read, we could potentially get rid of this step?
    for (ColumnReadContext colCtx : colCtxs) {
      for (int i = 0; i < colCtx.streamCount; ++i) {
        StreamContext sctx = colCtx.streams[i];
        List<DiskRange> sb = getStreamBuffers(rangesToRead, sctx.offset, sctx.length);
        sctx.bufferIter = sb.listIterator();
        if (DebugUtils.isTraceOrcEnabled()) {
          LOG.info("Column " + colCtx.colIx + " stream " + sctx.kind + " at " + sctx.offset + ","
              + sctx.length + " got ranges (relative to stream) " + stringifyDiskRanges(sb));
        }
      }
    }

    // 3. Finally, decompress data, map per RG, and return to caller.
    // We go by RG and not by column because that is how data is processed.
    int rgCount = (int)Math.ceil((double)rowCountInStripe / rowIndexStride);
    for (int rgIx = 0; rgIx < rgCount; ++rgIx) {
      boolean isLastRg = rgCount - rgIx - 1 == 0;
      // Create the batch we will use to return data for this RG.
      EncodedColumnBatch<OrcBatchKey> ecb = new EncodedColumnBatch<OrcBatchKey>(
          new OrcBatchKey(fileName, stripeIx, rgIx), colRgs.length, 0);
      boolean isRGSelected = true;
      for (int colIxMod = 0; colIxMod < colRgs.length; ++colIxMod) {
        if (colRgs[colIxMod] != null && !colRgs[colIxMod][rgIx]) {
          isRGSelected = false;
          continue;
        } // RG x col filtered.
        ColumnReadContext ctx = colCtxs[colIxMod];
        RowIndexEntry index = ctx.rowIndex.getEntry(rgIx),
            nextIndex = isLastRg ? null : ctx.rowIndex.getEntry(rgIx + 1);
        ecb.initColumn(colIxMod, ctx.colIx, ctx.streamCount);
        for (int streamIx = 0; streamIx < ctx.streamCount; ++streamIx) {
          StreamContext sctx = ctx.streams[streamIx];
          long absStreamOffset = stripeOffset + sctx.offset;
          StreamBuffer cb = null;
          if (isDictionary(sctx.kind, ctx.encoding)) {
            // This stream is for entire stripe and needed for every RG; uncompress once and reuse.
            if (DebugUtils.isTraceOrcEnabled()) {
              LOG.info("Getting stripe-level stream [" + sctx.kind + ", " + ctx.encoding + "] for"
                  + " column " + ctx.colIx + " RG " + rgIx + " at " + sctx.offset + ", " + sctx.length);
            }
            cb = getStripeLevelStream(absStreamOffset, sctx, cache, isLastRg);
          } else {
            // This stream can be separated by RG using index. Let's do that.
            long cOffset = index.getPositions(sctx.streamIndexOffset),
                endCOffset = estimateRgEndOffset(isCompressed, isLastRg, isLastRg
                    ? sctx.length : nextIndex.getPositions(sctx.streamIndexOffset),
                    sctx.length, bufferSize);
            cb = new StreamBuffer();
            cb.incRef();
            if (DebugUtils.isTraceOrcEnabled()) {
              LOG.info("Getting data for column "+ ctx.colIx + " " + (isLastRg ? "last " : "")
                  + "RG " + rgIx + " stream " + sctx.kind  + " at " + sctx.offset + ", "
                  + sctx.length + " index position " + sctx.streamIndexOffset + ": compressed ["
                  + cOffset + ", " + endCOffset + ")");
            }
            InStream.uncompressStream(fileName, absStreamOffset, zcr, sctx.bufferIter,
                codec, bufferSize, cache, cOffset, endCOffset, cb);
          }
          ecb.setStreamData(colIxMod, streamIx, cb);
        }
      }
      if (isRGSelected) {
        consumer.consumeData(ecb);
      }
    }
  }

  /**
   * Reads the entire stream for a column (e.g. a dictionarty stream), or gets it from context.
   * @param isLastRg Whether the stream is being read for last RG in stripe.
   * @return StreamBuffer that contains the entire stream.
   */
  private StreamBuffer getStripeLevelStream(long baseOffset, StreamContext ctx,
      LowLevelCache cache, boolean isLastRg) throws IOException {
    if (ctx.stripeLevelStream == null) {
      ctx.stripeLevelStream = new StreamBuffer();
      // We will be using this for each RG while also sending RGs to processing.
      // To avoid buffers being unlocked, run refcount one ahead; we will not increase
      // it when building the last RG, so each RG processing will decref once, and the
      // last one will unlock the buffers.
      ctx.stripeLevelStream.incRef();
      InStream.uncompressStream(fileName, baseOffset, zcr,
          ctx.bufferIter, codec, bufferSize, cache, -1, -1, ctx.stripeLevelStream);
      ctx.bufferIter = null;
    }
    if (!isLastRg) {
      ctx.stripeLevelStream.incRef();
    }
    return ctx.stripeLevelStream;
  }

  @Override
  public List<ColumnEncoding> getCurrentColumnEncodings() throws IOException {
    return stripeFooter.getColumnsList();
  }

  @Override
  public void getCurrentRowIndexEntries(
      boolean[] included, RowIndex[] indexes) throws IOException {
    readRowIndex(currentStripe, included, indexes);
  }

  @Override
  public List<Stream> getCurrentStreams() throws IOException {
    return stripeFooter.getStreamsList();
  }

  @Override
  public void setMetadata(
      RowIndex[] index, List<ColumnEncoding> encodings, List<Stream> streams) {
    assert index.length == indexes.length;
    System.arraycopy(index, 0, indexes, 0, index.length);
    this.streamList = streams;
    this.encodings = encodings;
  }
}
