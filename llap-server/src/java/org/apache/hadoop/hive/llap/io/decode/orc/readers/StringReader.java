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
package org.apache.hadoop.hive.llap.io.decode.orc.readers;

import java.io.IOException;

import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.io.orc.PositionProvider;
import org.apache.hadoop.io.Text;

/**
 *
 */
public interface StringReader {

  /**
   * Seek to the position provided by index.
   * @param index
   * @throws java.io.IOException
   */
  void seek(PositionProvider index) throws IOException;

  /**
   * Skip number of specified rows.
   * @param numValues
   * @throws IOException
   */
  void skip(long numValues) throws IOException;

  /**
   * Check if there are any more values left.
   * @return
   * @throws IOException
   */
  boolean hasNext() throws IOException;

  /**
   * Return the next available value.
   * @return
   * @throws IOException
   */
  Text next() throws IOException;

  /**
   * Return the next available vector for values.
   * @return
   * @throws IOException
   */
  ColumnVector nextVector(ColumnVector previous, long previousLen)
      throws IOException;
}