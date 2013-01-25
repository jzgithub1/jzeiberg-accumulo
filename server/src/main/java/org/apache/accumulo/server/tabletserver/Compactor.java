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
package org.apache.accumulo.server.tabletserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.accumulo.cloudtrace.instrument.Span;
import org.apache.accumulo.cloudtrace.instrument.Trace;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.thrift.IterInfo;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVIterator;
import org.apache.accumulo.core.file.FileSKVWriter;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.WrappingIterator;
import org.apache.accumulo.core.iterators.system.ColumnFamilySkippingIterator;
import org.apache.accumulo.core.iterators.system.DeletingIterator;
import org.apache.accumulo.core.iterators.system.MultiIterator;
import org.apache.accumulo.core.iterators.system.TimeSettingIterator;
import org.apache.accumulo.core.tabletserver.thrift.ActiveCompaction;
import org.apache.accumulo.core.tabletserver.thrift.CompactionReason;
import org.apache.accumulo.core.tabletserver.thrift.CompactionType;
import org.apache.accumulo.core.util.LocalityGroupUtil;
import org.apache.accumulo.core.util.LocalityGroupUtil.LocalityGroupConfigurationError;
import org.apache.accumulo.core.util.MetadataTable.DataFileValue;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.problems.ProblemReport;
import org.apache.accumulo.server.problems.ProblemReportingIterator;
import org.apache.accumulo.server.problems.ProblemReports;
import org.apache.accumulo.server.problems.ProblemType;
import org.apache.accumulo.server.tabletserver.Tablet.MajorCompactionReason;
import org.apache.accumulo.server.tabletserver.Tablet.MinorCompactionReason;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;


public class Compactor implements Callable<CompactionStats> {
  
  public class CountingIterator extends WrappingIterator {
    
    private long count;
    
    public CountingIterator deepCopy(IteratorEnvironment env) {
      return new CountingIterator(this, env);
    }
    
    private CountingIterator(CountingIterator other, IteratorEnvironment env) {
      setSource(other.getSource().deepCopy(env));
      count = 0;
    }
    
    public CountingIterator(SortedKeyValueIterator<Key,Value> source) {
      this.setSource(source);
      count = 0;
    }
    
    @Override
    public void init(SortedKeyValueIterator<Key,Value> source, Map<String,String> options, IteratorEnvironment env) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public void next() throws IOException {
      super.next();
      count++;
      if (count % 1024 == 0) {
        entriesRead.addAndGet(1024);
      }
    }
    
    public long getCount() {
      return count;
    }
  }

  private static final Logger log = Logger.getLogger(Compactor.class);
  
  static class CompactionCanceledException extends Exception {
    private static final long serialVersionUID = 1L;
  }
  
  static interface CompactionEnv {
    boolean isCompactionEnabled();
    
    IteratorScope getIteratorScope();
  }
  
  private Map<String,DataFileValue> filesToCompact;
  private InMemoryMap imm;
  private String outputFile;
  private boolean propogateDeletes;
  private TableConfiguration acuTableConf;
  private CompactionEnv env;
  private Configuration conf;
  private FileSystem fs;
  protected KeyExtent extent;
  private List<IteratorSetting> iterators;
  
  // things to report
  private String currentLocalityGroup = "";
  private long startTime;

  private MajorCompactionReason reason;
  protected MinorCompactionReason mincReason;
  
  private AtomicLong entriesRead = new AtomicLong(0);
  private AtomicLong entriesWritten = new AtomicLong(0);
  
  private synchronized void setLocalityGroup(String name) {
    this.currentLocalityGroup = name;
  }

  private void clearStats() {
    entriesRead.set(0);
    entriesWritten.set(0);
  }

  protected static Set<Compactor> runningCompactions = Collections.synchronizedSet(new HashSet<Compactor>());
  
  public static class CompactionInfo {
    
    private Compactor compactor;
    private String localityGroup;
    private long entriesRead;
    private long entriesWritten;
    
    CompactionInfo(Compactor compactor) {
      this.localityGroup = compactor.currentLocalityGroup;
      this.entriesRead = compactor.entriesRead.get();
      this.entriesWritten = compactor.entriesWritten.get();
      this.compactor = compactor;
    }

    public ActiveCompaction toThrift() {
      
      CompactionType type;
      
      if (compactor.imm != null)
        if (compactor.filesToCompact.size() > 0)
          type = CompactionType.MERGE;
        else
          type = CompactionType.MINOR;
      else if (!compactor.propogateDeletes)
        type = CompactionType.FULL;
      else
        type = CompactionType.MAJOR;
      
      CompactionReason reason;
      
      if (compactor.imm != null)
        switch(compactor.mincReason){
          case USER:
            reason = CompactionReason.USER;
            break;
          case CLOSE:
            reason = CompactionReason.CLOSE;
            break;
          case SYSTEM:
          default:
            reason = CompactionReason.SYSTEM;
            break;
        }
      else
        switch (compactor.reason) {
          case USER:
            reason = CompactionReason.USER;
            break;
          case CHOP:
            reason = CompactionReason.CHOP;
            break;
          case IDLE:
            reason = CompactionReason.IDLE;
            break;
          case NORMAL:
          default:
            reason = CompactionReason.SYSTEM;
            break;
        }
      
      List<IterInfo> iiList = new ArrayList<IterInfo>();
      Map<String,Map<String,String>> iterOptions = new HashMap<String,Map<String,String>>();
      
      for (IteratorSetting iterSetting : compactor.iterators) {
        iiList.add(new IterInfo(iterSetting.getPriority(), iterSetting.getIteratorClass(), iterSetting.getName()));
        iterOptions.put(iterSetting.getName(), iterSetting.getOptions());
      }
      
      return new ActiveCompaction(compactor.extent.toThrift(), System.currentTimeMillis() - compactor.startTime, new ArrayList<String>(
          compactor.filesToCompact.keySet()), compactor.outputFile, type, reason, localityGroup, entriesRead, entriesWritten, iiList, iterOptions);
    }
  }
  
  public static List<CompactionInfo> getRunningCompactions() {
    ArrayList<CompactionInfo> compactions = new ArrayList<Compactor.CompactionInfo>();
    
    synchronized (runningCompactions) {
      for (Compactor compactor : runningCompactions) {
        compactions.add(new CompactionInfo(compactor));
      }
    }
    
    return compactions;
  }

  Compactor(Configuration conf, FileSystem fs, Map<String,DataFileValue> files, InMemoryMap imm, String outputFile, boolean propogateDeletes,
      TableConfiguration acuTableConf, KeyExtent extent, CompactionEnv env, List<IteratorSetting> iterators, MajorCompactionReason reason) {
    this.extent = extent;
    this.conf = conf;
    this.fs = fs;
    this.filesToCompact = files;
    this.imm = imm;
    this.outputFile = outputFile;
    this.propogateDeletes = propogateDeletes;
    this.acuTableConf = acuTableConf;
    this.env = env;
    this.iterators = iterators;
    this.reason = reason;
    
    startTime = System.currentTimeMillis();
  }
  
  Compactor(Configuration conf, FileSystem fs, Map<String,DataFileValue> files, InMemoryMap imm, String outputFile, boolean propogateDeletes,
      TableConfiguration acuTableConf, KeyExtent extent, CompactionEnv env) {
    this(conf, fs, files, imm, outputFile, propogateDeletes, acuTableConf, extent, env, new ArrayList<IteratorSetting>(), null);
  }
  
  public FileSystem getFileSystem() {
    return fs;
  }
  
  KeyExtent getExtent() {
    return extent;
  }
  
  String getOutputFile() {
    return outputFile;
  }
  
  @Override
  public CompactionStats call() throws IOException, CompactionCanceledException {
    
    FileSKVWriter mfw = null;
    
    CompactionStats majCStats = new CompactionStats();

    boolean remove = runningCompactions.add(this);
    
    clearStats();

    try {
      FileOperations fileFactory = FileOperations.getInstance();
      mfw = fileFactory.openWriter(outputFile, fs, conf, acuTableConf);
      
      Map<String,Set<ByteSequence>> lGroups;
      try {
        lGroups = LocalityGroupUtil.getLocalityGroups(acuTableConf);
      } catch (LocalityGroupConfigurationError e) {
        throw new IOException(e);
      }
      
      long t1 = System.currentTimeMillis();
      
      HashSet<ByteSequence> allColumnFamilies = new HashSet<ByteSequence>();
      
      if (mfw.supportsLocalityGroups()) {
        for (Entry<String,Set<ByteSequence>> entry : lGroups.entrySet()) {
          setLocalityGroup(entry.getKey());
          compactLocalityGroup(entry.getKey(), entry.getValue(), true, mfw, majCStats);
          allColumnFamilies.addAll(entry.getValue());
        }
      }
      
      setLocalityGroup("");
      compactLocalityGroup(null, allColumnFamilies, false, mfw, majCStats);
      
      long t2 = System.currentTimeMillis();
      
      FileSKVWriter mfwTmp = mfw;
      mfw = null; // set this to null so we do not try to close it again in finally if the close fails
      mfwTmp.close(); // if the close fails it will cause the compaction to fail
      
      // Verify the file, since hadoop 0.20.2 sometimes lies about the success of close()
      try {
        FileSKVIterator openReader = fileFactory.openReader(outputFile, false, fs, conf, acuTableConf);
        openReader.close();
      } catch (IOException ex) {
        log.error("Verification of successful compaction fails!!! " + extent + " " + outputFile, ex);
        throw ex;
      }
      
      log.debug(String.format("Compaction %s %,d read | %,d written | %,6d entries/sec | %6.3f secs", extent, majCStats.getEntriesRead(),
          majCStats.getEntriesWritten(), (int) (majCStats.getEntriesRead() / ((t2 - t1) / 1000.0)), (t2 - t1) / 1000.0));
      
      majCStats.setFileSize(fileFactory.getFileSize(outputFile, fs, conf, acuTableConf));
      return majCStats;
    } catch (IOException e) {
      log.error(e, e);
      throw e;
    } catch (RuntimeException e) {
      log.error(e, e);
      throw e;
    } finally {
      
      if (remove)
        runningCompactions.remove(this);

      try {
        if (mfw != null) {
          // compaction must not have finished successfully, so close its output file
          try {
            mfw.close();
          } finally {
            Path path = new Path(outputFile);
            if (!fs.delete(path, true))
              if (fs.exists(path))
                log.error("Unable to delete " + outputFile);
          }
        }
      } catch (IOException e) {
        log.warn(e, e);
      }
    }
  }

  private List<SortedKeyValueIterator<Key,Value>> openMapDataFiles(String lgName, ArrayList<FileSKVIterator> readers) throws IOException {
    
    List<SortedKeyValueIterator<Key,Value>> iters = new ArrayList<SortedKeyValueIterator<Key,Value>>(filesToCompact.size());
    
    for (String mapFile : filesToCompact.keySet()) {
      try {
        
        FileOperations fileFactory = FileOperations.getInstance();
        
        FileSKVIterator reader;
        
        reader = fileFactory.openReader(mapFile, false, fs, conf, acuTableConf);
        
        readers.add(reader);
        
        SortedKeyValueIterator<Key,Value> iter = new ProblemReportingIterator(extent.getTableId().toString(), mapFile, false, reader);
        
        if (filesToCompact.get(mapFile).isTimeSet()) {
          iter = new TimeSettingIterator(iter, filesToCompact.get(mapFile).getTime());
        }
        
        iters.add(iter);
        
      } catch (Throwable e) {
        
        ProblemReports.getInstance().report(new ProblemReport(extent.getTableId().toString(), ProblemType.FILE_READ, mapFile, e));
        
        log.warn("Some problem opening map file " + mapFile + " " + e.getMessage(), e);
        // failed to open some map file... close the ones that were opened
        for (FileSKVIterator reader : readers) {
          try {
            reader.close();
          } catch (Throwable e2) {
            log.warn("Failed to close map file", e2);
          }
        }
        
        readers.clear();
        
        if (e instanceof IOException)
          throw (IOException) e;
        throw new IOException("Failed to open map data files", e);
      }
    }
    
    return iters;
  }
  
  private void compactLocalityGroup(String lgName, Set<ByteSequence> columnFamilies, boolean inclusive, FileSKVWriter mfw, CompactionStats majCStats)
      throws IOException, CompactionCanceledException {
    ArrayList<FileSKVIterator> readers = new ArrayList<FileSKVIterator>(filesToCompact.size());
    Span span = Trace.start("compact");
    try {
      long entriesCompacted = 0;
      List<SortedKeyValueIterator<Key,Value>> iters = openMapDataFiles(lgName, readers);
      
      if (imm != null) {
        iters.add(imm.compactionIterator());
      }
      
      CountingIterator citr = new CountingIterator(new MultiIterator(iters, extent.toDataRange()));
      DeletingIterator delIter = new DeletingIterator(citr, propogateDeletes);
      ColumnFamilySkippingIterator cfsi = new ColumnFamilySkippingIterator(delIter);
      

      // if(env.getIteratorScope() )
      
      TabletIteratorEnvironment iterEnv;
      if (env.getIteratorScope() == IteratorScope.majc)
        iterEnv = new TabletIteratorEnvironment(IteratorScope.majc, !propogateDeletes, acuTableConf);
      else if (env.getIteratorScope() == IteratorScope.minc)
        iterEnv = new TabletIteratorEnvironment(IteratorScope.minc, acuTableConf);
      else
        throw new IllegalArgumentException();
      
      SortedKeyValueIterator<Key,Value> itr = iterEnv.getTopLevelIterator(IteratorUtil.loadIterators(env.getIteratorScope(), cfsi, extent, acuTableConf,
          iterators, iterEnv));
      
      itr.seek(extent.toDataRange(), columnFamilies, inclusive);
      
      if (!inclusive) {
        mfw.startDefaultLocalityGroup();
      } else {
        mfw.startNewLocalityGroup(lgName, columnFamilies);
      }
      
      Span write = Trace.start("write");
      try {
        while (itr.hasTop() && env.isCompactionEnabled()) {
          mfw.append(itr.getTopKey(), itr.getTopValue());
          itr.next();
          entriesCompacted++;
          
          if (entriesCompacted % 1024 == 0) {
            // Periodically update stats, do not want to do this too often since its volatile
            entriesWritten.addAndGet(1024);
          }
        }

        if (itr.hasTop() && !env.isCompactionEnabled()) {
          // cancel major compaction operation
          try {
            try {
              mfw.close();
            } catch (IOException e) {
              log.error(e, e);
            }
            fs.delete(new Path(outputFile), true);
          } catch (Exception e) {
            log.warn("Failed to delete Canceled compaction output file " + outputFile, e);
          }
          throw new CompactionCanceledException();
        }
        
      } finally {
        CompactionStats lgMajcStats = new CompactionStats(citr.getCount(), entriesCompacted);
        majCStats.add(lgMajcStats);
        write.stop();
      }
      
    } finally {
      // close sequence files opened
      for (FileSKVIterator reader : readers) {
        try {
          reader.close();
        } catch (Throwable e) {
          log.warn("Failed to close map file", e);
        }
      }
      span.stop();
    }
  }
  
}
