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

package org.apache.hadoop.yarn.logaggregation.filecontroller.tfile;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.logaggregation.ContainerLogFileInfo;
import org.apache.hadoop.yarn.logaggregation.ExtendedLogMetaRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.math3.util.Pair;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ClusterStorageCapacityExceededException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.HarFs;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogKey;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogReader;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogValue;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogWriter;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationDFSException;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationFileController;
import org.apache.hadoop.yarn.logaggregation.filecontroller.LogAggregationFileControllerContext;
import org.apache.hadoop.yarn.logaggregation.ContainerLogAggregationType;
import org.apache.hadoop.yarn.logaggregation.ContainerLogMeta;
import org.apache.hadoop.yarn.logaggregation.ContainerLogsRequest;
import org.apache.hadoop.yarn.logaggregation.LogAggregationUtils;
import org.apache.hadoop.yarn.logaggregation.LogToolUtils;
import org.apache.hadoop.yarn.util.Times;
import org.apache.hadoop.yarn.webapp.View.ViewContext;
import org.apache.hadoop.yarn.webapp.view.HtmlBlock.Block;

/**
 * The TFile log aggregation file Controller implementation.
 */
@Private
@Unstable
public class LogAggregationTFileController
    extends LogAggregationFileController {

  private static final Logger LOG = LoggerFactory.getLogger(
      LogAggregationTFileController.class);

  private LogWriter writer;
  private TFileLogReader tfReader = null;

  public LogAggregationTFileController(){}

  @Override
  public void initInternal(Configuration conf) {
    // do nothing
  }

  @Override
  public void initializeWriter(LogAggregationFileControllerContext context)
      throws IOException {
    this.writer = new LogWriter();
    writer.initialize(this.conf, context.getRemoteNodeTmpLogFileForApp(),
        context.getUserUgi());
    // Write ACLs once when the writer is created.
    writer.writeApplicationACLs(context.getAppAcls());
    writer.writeApplicationOwner(context.getUserUgi().getShortUserName());
  }

  @Override
  public void closeWriter() throws LogAggregationDFSException {
    if (this.writer != null) {
      try {
        this.writer.close();
      } catch (ClusterStorageCapacityExceededException e) {
        throw new LogAggregationDFSException(e);
      } finally {
        this.writer = null;
      }
    }
  }

  @Override
  public void write(LogKey logKey, LogValue logValue) throws IOException {
    this.writer.append(logKey, logValue);
  }

  @Override
  public void postWrite(final LogAggregationFileControllerContext record)
      throws Exception {
    // Before upload logs, make sure the number of existing logs
    // is smaller than the configured NM log aggregation retention size.
    if (record.isUploadedLogsInThisCycle() &&
        record.isLogAggregationInRolling()) {
      cleanOldLogs(record.getRemoteNodeLogFileForApp(), record.getNodeId(),
          record.getUserUgi());
      record.increcleanupOldLogTimes();
    }

    // close the writer before the file is renamed or deleted
    closeWriter();

    final Path renamedPath = record.getRollingMonitorInterval() <= 0
        ? record.getRemoteNodeLogFileForApp() : new Path(
            record.getRemoteNodeLogFileForApp().getParent(),
            record.getRemoteNodeLogFileForApp().getName() + "_"
            + record.getLogUploadTimeStamp());
    final boolean rename = record.isUploadedLogsInThisCycle();
    try {
      record.getUserUgi().doAs(new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          FileSystem remoteFS = record.getRemoteNodeLogFileForApp()
              .getFileSystem(conf);
          if (rename) {
            remoteFS.rename(record.getRemoteNodeTmpLogFileForApp(),
                renamedPath);
          } else {
            remoteFS.delete(record.getRemoteNodeTmpLogFileForApp(), false);
          }
          return null;
        }
      });
    } catch (Exception e) {
      LOG.error(
          "Failed to move temporary log file to final location: ["
          + record.getRemoteNodeTmpLogFileForApp() + "] to ["
          + renamedPath + "]", e);
      throw new Exception("Log uploaded failed for Application: "
          + record.getAppId() + " in NodeManager: "
          + LogAggregationUtils.getNodeString(record.getNodeId()) + " at "
          + Times.format(record.getLogUploadTimeStamp()) + "\n");
    }
  }

  @Override
  public boolean readAggregatedLogs(ContainerLogsRequest logRequest,
      OutputStream os) throws IOException {
    boolean findLogs = false;
    boolean createPrintStream = (os == null);
    ApplicationId appId = logRequest.getAppId();
    String nodeId = logRequest.getNodeId();
    List<String> logTypes = new ArrayList<>();
    if (logRequest.getLogTypes() != null && !logRequest
        .getLogTypes().isEmpty()) {
      logTypes.addAll(logRequest.getLogTypes());
    }
    String containerIdStr = logRequest.getContainerId();
    boolean getAllContainers = (containerIdStr == null
        || containerIdStr.isEmpty());
    long size = logRequest.getBytes();
    RemoteIterator<FileStatus> nodeFiles = LogAggregationUtils
        .getRemoteNodeFileDir(conf, appId, logRequest.getAppOwner(),
        remoteRootLogDir, remoteRootLogDirSuffix);
    byte[] buf = new byte[65535];
    while (nodeFiles != null && nodeFiles.hasNext()) {
      final FileStatus thisNodeFile = nodeFiles.next();
      String nodeName = thisNodeFile.getPath().getName();
      if (nodeName.equals(appId + ".har")) {
        Path p = new Path("har:///"
            + thisNodeFile.getPath().toUri().getRawPath());
        nodeFiles = HarFs.get(p.toUri(), conf).listStatusIterator(p);
        continue;
      }
      if ((nodeId == null || nodeName.contains(LogAggregationUtils
          .getNodeString(nodeId))) && !nodeName.endsWith(
              LogAggregationUtils.TMP_FILE_SUFFIX)) {
        try (LogReader reader = new LogReader(conf, thisNodeFile.getPath())) {
          DataInputStream valueStream;
          LogKey key = new LogKey();
          valueStream = reader.next(key);
          while (valueStream != null) {
            if (getAllContainers || (key.toString().equals(containerIdStr))) {
              if (createPrintStream) {
                os = LogToolUtils.createPrintStream(
                    logRequest.getOutputLocalDir(),
                    thisNodeFile.getPath().getName(), key.toString());
              }
              try {
                while (true) {
                  try {
                    String fileType = valueStream.readUTF();
                    String fileLengthStr = valueStream.readUTF();
                    long fileLength = Long.parseLong(fileLengthStr);
                    if (logTypes == null || logTypes.isEmpty() ||
                        logTypes.contains(fileType)) {
                      LogToolUtils.outputContainerLog(key.toString(),
                          nodeName, fileType, fileLength, size,
                          Times.format(thisNodeFile.getModificationTime()),
                          valueStream, os, buf,
                          ContainerLogAggregationType.AGGREGATED);
                      byte[] b = aggregatedLogSuffix(fileType).getBytes(
                          StandardCharsets.UTF_8);
                      os.write(b, 0, b.length);
                      findLogs = true;
                    } else {
                      long totalSkipped = 0;
                      long currSkipped = 0;
                      while (currSkipped != -1 && totalSkipped < fileLength) {
                        currSkipped = valueStream.skip(
                            fileLength - totalSkipped);
                        totalSkipped += currSkipped;
                      }
                    }
                  } catch (EOFException eof) {
                    break;
                  }
                }
              } finally {
                os.flush();
                if (createPrintStream) {
                  closePrintStream(os);
                }
              }
              if (!getAllContainers) {
                break;
              }
            }
            // Next container
            key = new LogKey();
            valueStream = reader.next(key);
          }
        } catch (IOException ex) {
          LOG.error("Skipping empty or corrupt file " +
              thisNodeFile.getPath(), ex);
          continue; // skip empty or corrupt files
        }
      }
    }
    return findLogs;
  }

  @Override
  public Map<String, List<ContainerLogFileInfo>> getLogMetaFilesOfNode(
      ExtendedLogMetaRequest logRequest, FileStatus currentNodeFile,
      ApplicationId appId) throws IOException {
    Map<String, List<ContainerLogFileInfo>> logMetaFiles = new HashMap<>();
    Path nodePath = currentNodeFile.getPath();

    try (LogReader reader = new LogReader(conf, nodePath)) {
      DataInputStream valueStream;
      LogKey key = new LogKey();
      valueStream = reader.next(key);
      while (valueStream != null) {
        if (logRequest.getContainerId() == null ||
            logRequest.getContainerId().equals(key.toString())) {
          logMetaFiles.put(key.toString(), new ArrayList<>());
          fillMetaFiles(currentNodeFile, valueStream,
              logMetaFiles.get(key.toString()));
        }
        // Next container
        key = new LogKey();
        valueStream = reader.next(key);
      }
    }
    return logMetaFiles;
  }

  private void fillMetaFiles(
      FileStatus currentNodeFile, DataInputStream valueStream,
      List<ContainerLogFileInfo> logMetaFiles)
      throws IOException {
    while (true) {
      try {
        Pair<String, String> logMeta =
            LogReader.readContainerMetaDataAndSkipData(
                valueStream);
        ContainerLogFileInfo logMetaFile = new ContainerLogFileInfo();
        logMetaFile.setLastModifiedTime(
            Long.toString(currentNodeFile.getModificationTime()));
        logMetaFile.setFileName(logMeta.getFirst());
        logMetaFile.setFileSize(logMeta.getSecond());
        logMetaFiles.add(logMetaFile);
      } catch (EOFException eof) {
        break;
      }
    }
  }

  @Override
  public List<ContainerLogMeta> readAggregatedLogsMeta(
      ContainerLogsRequest logRequest) throws IOException {
    List<ContainerLogMeta> containersLogMeta = new ArrayList<>();
    String containerIdStr = logRequest.getContainerId();
    String nodeId = logRequest.getNodeId();
    ApplicationId appId = logRequest.getAppId();
    String appOwner = logRequest.getAppOwner();
    ApplicationAttemptId appAttemptId = logRequest.getAppAttemptId();
    boolean getAllContainers = (containerIdStr == null &&
        appAttemptId == null);
    boolean getOnlyOneContainer = containerIdStr != null;
    String nodeIdStr = (nodeId == null) ? null
        : LogAggregationUtils.getNodeString(nodeId);
    RemoteIterator<FileStatus> nodeFiles = LogAggregationUtils
        .getRemoteNodeFileDir(conf, appId, appOwner,
        remoteRootLogDir, remoteRootLogDirSuffix);
    if (nodeFiles == null) {
      throw new IOException("There is no available log file for "
          + "application:" + appId);
    }
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      if (thisNodeFile.getPath().getName().equals(appId + ".har")) {
        Path p = new Path("har:///"
            + thisNodeFile.getPath().toUri().getRawPath());
        nodeFiles = HarFs.get(p.toUri(), conf).listStatusIterator(p);
        continue;
      }
      if (nodeIdStr != null) {
        if (!thisNodeFile.getPath().getName().contains(nodeIdStr)) {
          continue;
        }
      }
      if (!thisNodeFile.getPath().getName()
          .endsWith(LogAggregationUtils.TMP_FILE_SUFFIX)) {
        try (LogReader reader = new LogReader(conf,
            thisNodeFile.getPath())) {
          DataInputStream valueStream;
          LogKey key = new LogKey();
          valueStream = reader.next(key);
          while (valueStream != null) {
            if (getAllContainers || (key.toString().equals(containerIdStr)) ||
                belongsToAppAttempt(appAttemptId, key.toString())) {
              ContainerLogMeta containerLogMeta = new ContainerLogMeta(
                  key.toString(), thisNodeFile.getPath().getName());
              while (true) {
                try {
                  Pair<String, String> logMeta =
                      LogReader.readContainerMetaDataAndSkipData(
                          valueStream);
                  containerLogMeta.addLogMeta(
                      logMeta.getFirst(),
                      logMeta.getSecond(),
                      Times.format(thisNodeFile.getModificationTime()));
                } catch (EOFException eof) {
                  break;
                }
              }
              containersLogMeta.add(containerLogMeta);
              if (getOnlyOneContainer) {
                break;
              }
            }
            // Next container
            key = new LogKey();
            valueStream = reader.next(key);
          }
        } catch (IOException ex) {
          LOG.error("Skipping empty or corrupt file " +
              thisNodeFile.getPath(), ex);
          continue; // skip empty or corrupt files
        }
      }
    }
    return containersLogMeta;
  }

  @Override
  public void renderAggregatedLogsBlock(Block html, ViewContext context) {
    TFileAggregatedLogsBlock block = new TFileAggregatedLogsBlock(
        context, conf, remoteRootLogDir, remoteRootLogDirSuffix);
    block.render(html);
  }

  @Override
  public String getApplicationOwner(Path aggregatedLog, ApplicationId appId)
      throws IOException {
    createTFileLogReader(aggregatedLog);
    return this.tfReader.getLogReader().getApplicationOwner();
  }

  @Override
  public Map<ApplicationAccessType, String> getApplicationAcls(
      Path aggregatedLog, ApplicationId appId) throws IOException {
    createTFileLogReader(aggregatedLog);
    return this.tfReader.getLogReader().getApplicationAcls();
  }

  private void createTFileLogReader(Path aggregatedLog) throws IOException {
    if (this.tfReader == null || !this.tfReader.getAggregatedLogPath()
        .equals(aggregatedLog)) {
      LogReader logReader = new LogReader(conf, aggregatedLog);
      this.tfReader = new TFileLogReader(logReader, aggregatedLog);
    }
  }

  private static class TFileLogReader {
    private LogReader logReader;
    private Path aggregatedLogPath;

    TFileLogReader(LogReader logReader, Path aggregatedLogPath) {
      this.setLogReader(logReader);
      this.setAggregatedLogPath(aggregatedLogPath);
    }
    public LogReader getLogReader() {
      return logReader;
    }
    public void setLogReader(LogReader logReader) {
      this.logReader = logReader;
    }
    public Path getAggregatedLogPath() {
      return aggregatedLogPath;
    }
    public void setAggregatedLogPath(Path aggregatedLogPath) {
      this.aggregatedLogPath = aggregatedLogPath;
    }
  }
}
