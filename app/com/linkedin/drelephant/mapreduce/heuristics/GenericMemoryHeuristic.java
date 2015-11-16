/*
 * Copyright 2015 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.mapreduce.heuristics;

import com.linkedin.drelephant.configurations.heuristic.HeuristicConfigurationData;
import com.linkedin.drelephant.util.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.linkedin.drelephant.analysis.Heuristic;
import com.linkedin.drelephant.analysis.HeuristicResult;
import com.linkedin.drelephant.analysis.Severity;
import com.linkedin.drelephant.mapreduce.data.MapReduceCounterData;
import com.linkedin.drelephant.mapreduce.data.MapReduceApplicationData;
import com.linkedin.drelephant.mapreduce.data.MapReduceTaskData;
import com.linkedin.drelephant.math.Statistics;

import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;


/**
 * This heuristic deals with the efficiency of container size
 */
public abstract class GenericMemoryHeuristic implements Heuristic<MapReduceApplicationData> {
  private static final Logger logger = Logger.getLogger(GenericMemoryHeuristic.class);
  private static final long CONTAINER_MEMORY_DEFAULT_BYTES = 2048L * FileUtils.ONE_MB;

  // Severity Parameters
  private static final String MEM_RATIO_SEVERITY = "memory_ratio_severity";
  private static final String CONTAINER_MEM_SEVERITY = "container_memory_severity";

  // Default value of parameters
  private double[] memRatioLimits = {0.6d, 0.5d, 0.4d, 0.3d}; // Avg Physical Mem of Tasks / Container Mem
  private double[] memoryLimits = {1.1d, 1.5d, 2.0d, 2.5d};   // Container Memory Severity Limits

  private String _containerMemConf;
  private String _heuristicName;
  private HeuristicConfigurationData _heuristicConfData;

  @Override
  public String getHeuristicName() {
    return _heuristicName;
  }

  private void loadParameters() {
    Map<String, String> paramMap = _heuristicConfData.getParamMap();

    if(paramMap.get(MEM_RATIO_SEVERITY) != null) {
      double[] confMemRatioLimits = Utils.getParam(paramMap.get(MEM_RATIO_SEVERITY), memRatioLimits.length);
      if (confMemRatioLimits != null) {
        memRatioLimits = confMemRatioLimits;
      }
    }
    logger.info(_heuristicName + " will use " + MEM_RATIO_SEVERITY + " with the following threshold settings: "
        + Arrays.toString(memRatioLimits));

    if(paramMap.get(CONTAINER_MEM_SEVERITY) != null) {
      double[] confMemoryLimits = Utils.getParam(paramMap.get(CONTAINER_MEM_SEVERITY), memoryLimits.length);
      if (confMemoryLimits != null) {
        memoryLimits = confMemoryLimits;
      }
    }
    logger.info(_heuristicName + " will use " + CONTAINER_MEM_SEVERITY + " with the following threshold settings: "
        + Arrays.toString(memoryLimits));
    for (int i = 0; i < memoryLimits.length; i++) {
      memoryLimits[i] = memoryLimits[i] * CONTAINER_MEMORY_DEFAULT_BYTES;
    }
  }

  protected GenericMemoryHeuristic(String containerMemConf, String heuristicName,
      HeuristicConfigurationData heuristicConfData) {
    this._heuristicName = heuristicName;
    this._containerMemConf = containerMemConf;
    this._heuristicConfData = heuristicConfData;

    loadParameters();
  }

  protected abstract MapReduceTaskData[] getTasks(MapReduceApplicationData data);

  @Override
  public HeuristicResult apply(MapReduceApplicationData data) {

    if(!data.getSucceeded()) {
      return null;
    }

    String containerSizeStr = data.getConf().getProperty(_containerMemConf);

    long containerMem;
    try {
      containerMem = Long.parseLong(containerSizeStr);
    } catch (NumberFormatException e) {
      // Some job has a string var like "${VAR}" for this config.
      if(containerSizeStr.startsWith("$")) {
        String realContainerConf = containerSizeStr.substring(containerSizeStr.indexOf("{")+1,
            containerSizeStr.indexOf("}"));
        containerMem = Long.parseLong(data.getConf().getProperty(realContainerConf));
      } else {
        throw e;
      }
    }
    containerMem *= FileUtils.ONE_MB;

    MapReduceTaskData[] tasks = getTasks(data);
    List<Long> taskPMems = new ArrayList<Long>();
    List<Long> taskVMems = new ArrayList<Long>();
    List<Long> runtimesMs = new ArrayList<Long>();
    long taskPMin = Long.MAX_VALUE;
    long taskPMax = 0;
    for (MapReduceTaskData task : tasks) {
      if (task.isSampled()) {
        runtimesMs.add(task.getTotalRunTimeMs());
        long taskPMem = task.getCounters().get(MapReduceCounterData.CounterName.PHYSICAL_MEMORY_BYTES);
        long taskVMem = task.getCounters().get(MapReduceCounterData.CounterName.VIRTUAL_MEMORY_BYTES);
        taskPMems.add(taskPMem);
        taskPMin = Math.min(taskPMin, taskPMem);
        taskPMax = Math.max(taskPMax, taskPMem);
        taskVMems.add(taskVMem);
      }
    }

    if(taskPMin == Long.MAX_VALUE) {
      taskPMin = 0;
    }

    long taskPMemAvg = Statistics.average(taskPMems);
    long taskVMemAvg = Statistics.average(taskVMems);
    long averageTimeMs = Statistics.average(runtimesMs);

    Severity severity;
    if (tasks.length == 0) {
      severity = Severity.NONE;
    } else {
      severity = getTaskMemoryUtilSeverity(taskPMemAvg, containerMem);
    }

    HeuristicResult result = new HeuristicResult(_heuristicName, severity);

    result.addDetail("Number of tasks", Integer.toString(tasks.length));
    result.addDetail("Avg task runtime", Statistics.readableTimespan(averageTimeMs));
    result.addDetail("Avg Physical Memory (MB)", Long.toString(taskPMemAvg/FileUtils.ONE_MB));
    result.addDetail("Max Physical Memory (MB)", Long.toString(taskPMax/FileUtils.ONE_MB));
    result.addDetail("Min Physical Memory (MB)", Long.toString(taskPMin/FileUtils.ONE_MB));
    result.addDetail("Avg Virtual Memory (MB)", Long.toString(taskVMemAvg/FileUtils.ONE_MB));
    result.addDetail("Requested Container Memory", FileUtils.byteCountToDisplaySize(containerMem));

    return result;
  }

  private Severity getTaskMemoryUtilSeverity(long taskMemAvg, long taskMemMax) {
    double ratio = ((double)taskMemAvg) / taskMemMax;
    Severity sevRatio = getMemoryRatioSeverity(ratio);
    // Severity is reduced if the requested container memory is close to default
    Severity sevMax = getContainerMemorySeverity(taskMemMax);

    return Severity.min(sevRatio, sevMax);
  }


  private Severity getContainerMemorySeverity(long taskMemMax) {
    return Severity.getSeverityAscending(
        taskMemMax, memoryLimits[0], memoryLimits[1], memoryLimits[2], memoryLimits[3]);
  }

  private Severity getMemoryRatioSeverity(double ratio) {
    return Severity.getSeverityDescending(
        ratio, memRatioLimits[0], memRatioLimits[1], memRatioLimits[2], memRatioLimits[3]);
  }
}
