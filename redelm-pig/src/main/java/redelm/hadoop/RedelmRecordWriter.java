/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package redelm.hadoop;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import redelm.Log;
import redelm.column.ColumnDescriptor;
import redelm.column.mem.MemColumnWriteStore;
import redelm.column.mem.MemPageStore;
import redelm.column.mem.Page;
import redelm.column.mem.PageReader;
import redelm.hadoop.CodecFactory.BytesCompressor;
import redelm.hadoop.metadata.CompressionCodecName;
import redelm.io.ColumnIOFactory;
import redelm.io.MessageColumnIO;
import redelm.schema.MessageType;

import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

/**
 * Writes records to a Redelm file
 *
 * @see RedelmOutputFormat
 *
 * @author Julien Le Dem
 *
 * @param <T> the type of the materialized records
 */
public class RedelmRecordWriter<T> extends RecordWriter<Void, T> {
  private static final Log LOG = Log.getLog(RedelmRecordWriter.class);

  private final RedelmFileWriter w;
  private final WriteSupport<T> writeSupport;
  private final MessageType schema;
  private final Map<String, String> extraMetaData;
  private final int blockSize;
  private final int pageSize;
  private final CompressionCodecName codecName;
  private final BytesCompressor compressor;

  private int recordCount;

  private MemColumnWriteStore store;
  private MemPageStore pageStore;


  /**
   *
   * @param w the file to write to
   * @param writeSupport the class to convert incoming records
   * @param schema the schema of the records
   * @param extraMetaData extra meta data to write in the footer of the file
   * @param blockSize the size of a block in the file (this will be approximate)
   * @param codec the codec used to compress
   */
  RedelmRecordWriter(RedelmFileWriter w, WriteSupport<T> writeSupport, MessageType schema,  Map<String, String> extraMetaData, int blockSize, int pageSize, CompressionCodecName codecName, BytesCompressor compressor) {
    if (writeSupport == null) {
      throw new NullPointerException("writeSupport");
    }
    this.w = w;
    this.writeSupport = writeSupport;
    this.schema = schema;
    this.extraMetaData = extraMetaData;
    this.blockSize = blockSize;
    this.pageSize = pageSize;
    this.codecName = codecName;
    this.compressor = compressor;
    initStore();
  }

  private void initStore() {
    pageStore = new MemPageStore();
    store = new MemColumnWriteStore(pageStore, pageSize);
    //
    MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(schema);
    writeSupport.initForWrite(columnIO.getRecordWriter(store), schema, extraMetaData);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close(TaskAttemptContext taskAttemptContext) throws IOException,
  InterruptedException {
    flushStore();
    w.end(extraMetaData);
    compressor.release();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void write(Void key, T value) throws IOException, InterruptedException {
    writeSupport.write(value);
    ++ recordCount;
    checkBlockSizeReached();
  }

  private void checkBlockSizeReached() throws IOException {
    if (store.memSize() > blockSize) {
      flushStore();
      initStore();
    }
  }

  private void flushStore()
      throws IOException {
    w.startBlock(recordCount);
    store.flush();
    List<ColumnDescriptor> columns = schema.getColumns();
    for (ColumnDescriptor columnDescriptor : columns) {
      PageReader pageReader = pageStore.getPageReader(columnDescriptor);
      int totalValueCount = pageReader.getTotalValueCount();
      w.startColumn(columnDescriptor, totalValueCount, codecName);
      int n = 0;
      do {
        Page page = pageReader.readPage();
        n += page.getValueCount();
        long uncompressedSize = page.getBytes().size();
        w.writeDataPage(page.getValueCount(), (int)uncompressedSize, compressor.compress(page.getBytes()));
      } while (n < totalValueCount);
      w.endColumn();
    }
    recordCount = 0;
    w.endBlock();
    store = null;
    pageStore = null;
  }
}