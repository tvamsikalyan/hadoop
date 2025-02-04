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
package org.apache.hadoop.hdfs.protocol.datatransfer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hadoop.io.IOUtils;

/**
 * This class wraps an InputStream and an OutputStream.
 * Both the InputStream and OutputStream are closed on close call.
 * This class is moved from HDFS module to COMMON module for removing HDFS dependencies from YARN.
 */
public class IOStreamPair implements Closeable {
  public final InputStream in;
  public final OutputStream out;

  public IOStreamPair(InputStream in, OutputStream out) {
    this.in = in;
    this.out = out;
  }

  @Override
  public void close() throws IOException {
    IOUtils.closeStream(in);
    IOUtils.closeStream(out);
  }
}
