/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.olap;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.DBObjectBatch;
import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.common.UNode;
import com.dell.doradus.common.Utils;
import com.dell.doradus.core.ServerConfig;
import com.dell.doradus.olap.aggregate.AggregationBuilder;
import com.dell.doradus.olap.aggregate.AggregationRequest;
import com.dell.doradus.olap.aggregate.AggregationRequestData;
import com.dell.doradus.olap.aggregate.AggregationResult;
import com.dell.doradus.olap.aggregate.DuplicationDetection;
import com.dell.doradus.olap.aggregate.mr.MFAggregationBuilder;
import com.dell.doradus.olap.builder.SegmentBuilder;
import com.dell.doradus.olap.io.FileDeletedException;
import com.dell.doradus.olap.io.VDirectory;
import com.dell.doradus.olap.merge.MergeResult;
import com.dell.doradus.olap.merge.Merger;
import com.dell.doradus.olap.search.Searcher;
import com.dell.doradus.olap.store.CubeSearcher;
import com.dell.doradus.olap.store.SegmentStats;
import com.dell.doradus.olap.xlink.XLinkContext;
import com.dell.doradus.search.FieldSet;
import com.dell.doradus.search.SearchResult;
import com.dell.doradus.search.SearchResultList;
import com.dell.doradus.search.aggregate.SortOrder;
import com.dell.doradus.search.parser.AggregationQueryBuilder;
import com.dell.doradus.search.parser.DoradusQueryBuilder;
import com.dell.doradus.search.query.Query;
import com.dell.doradus.search.util.LRUCache;
import com.dell.doradus.service.olap.OLAPService;
import com.dell.doradus.utilities.Timer;


/***
 * Table OLAP
 * 
 * $root/applications/
 *  - app1/
 *  	- shard1/
 *  		- .cube.txt
 *  		- .cube_N/
 *  		- timestamp-guid/
 *  		- timestamp-guid/
 *  		- timestamp-guid/
 *  	- shard2/
 *  	- shard3/
 *  - app2/
 *  - app3/

 */

public class Olap {
    private static Logger LOG = LoggerFactory.getLogger("Olap.Olap");
	
	public static void deleteAll() {
	    new VDirectory("OLAP").delete();
	}
	
	private VDirectory m_root;
	private FieldsCache m_fieldsCache = new FieldsCache(ServerConfig.getInstance().olap_cache_size_mb * 1024L * 1024);
	private LRUCache<String, CubeSearcher> m_cachedSearchers = new LRUCache<>(
			ServerConfig.getInstance().olap_loaded_segments == 0 ? 8192 : ServerConfig.getInstance().olap_loaded_segments);
	private Set<String> m_mergedCubes = new HashSet<String>();
	
	public Olap() {
		m_root = new VDirectory("OLAP").getDirectoryCreate("applications");
	}
	
	public ApplicationDefinition getApplicationDefinition(String applicationName) {
	    return OLAPService.instance().getOLAPApplication(applicationName);
	}
	
	public void deleteApplication(String appName) {
		m_root.getDirectory(appName).delete();
	}

	public void deleteShard(String appName, String shard) {
		m_root.getDirectory(appName).getDirectory(shard).delete();
	}
	
	public List<String> listShards(String application) {
		return m_root.getDirectory(application).listDirectories();
	}

	public List<String> listSegments(String application, String shard) {
		VDirectory shardDir = m_root.getDirectory(application).getDirectory(shard);
		return shardDir.listDirectories();
	}
	
	public String getCubeSegment(String application, String shard) {
		VDirectory shardDir = m_root.getDirectory(application).getDirectory(shard);
		if(!shardDir.fileExists(".cube.txt")) return null;
		return shardDir.readAllText(".cube.txt");
	}
	
	public SegmentStats getStats(String application, String shard) {
		String cube = getCubeSegment(application, shard);
		if(cube == null) throw new IllegalArgumentException("Application does not exist or does not have merges yet");
		CubeSearcher s = getSearcher(application, shard, cube);
		return s.getStats();
	}
	
	
	public String addSegment(String application, String shard, OlapBatch batch) {
		Timer t = new Timer();
		ApplicationDefinition appDef = getApplicationDefinition(application);
		VDirectory shardDir = m_root.getDirectoryCreate(application).getDirectoryCreate(shard);
		String guid = Long.toString(System.currentTimeMillis(), 32) + "-" + UUID.randomUUID().toString();
		VDirectory segmentDir = shardDir.getDirectory(guid);
		batch.flushSegment(appDef, segmentDir);
		segmentDir.create();
		LOG.debug("add {} objects to {}/{} in {}", new Object[] { batch.documents.size(), application, shard, t} );
		return guid;
	}

	public String addSegment(ApplicationDefinition appDef, String shard, DBObjectBatch batch) {
	    Timer t = new Timer();
	    String application = appDef.getAppName();
	    VDirectory shardDir = m_root.getDirectoryCreate(application).getDirectoryCreate(shard);
	    String guid = Long.toString(System.currentTimeMillis(), 32) + "-" + UUID.randomUUID().toString();
	    VDirectory segmentDir = shardDir.getDirectory(guid);
        SegmentBuilder builder = new SegmentBuilder(appDef);
        builder.add(batch);
        builder.flush(segmentDir);
	    segmentDir.create();
	    LOG.debug("add {} objects to {}/{} in {}", new Object[] { batch.getObjectCount(), application, shard, t} );
	    return guid;
	}
	
	public AggregationResult aggregate(String application, String table, OlapAggregate olapAggregate) {
		AggregationRequestData requestData = olapAggregate.createRequestData(this, application, table);
		AggregationRequest aggregationRequest = new AggregationRequest(this, requestData);
		if(MFAggregationBuilder.shouldUseMF(aggregationRequest.parts[0].groups)) {
			AggregationResult result = MFAggregationBuilder.aggregate(this, aggregationRequest);
			return result;
		} else {
			AggregationResult result = AggregationBuilder.aggregate(this, aggregationRequest);
			return result;
		}
	}

	public SearchResultList search(String application, String table, OlapQuery olapQuery) {
		olapQuery.fixPairParameter();
		ApplicationDefinition appDef = getApplicationDefinition(application);
		TableDefinition tableDef = appDef.getTableDef(table);
		if(tableDef == null) throw new IllegalArgumentException("Table " + table + " does not exist");
    	Query query = DoradusQueryBuilder.Build(olapQuery.getQuery(), tableDef);
    	FieldSet fieldSet = new FieldSet(tableDef, olapQuery.getFieldSet());
    	fieldSet.expand();
    	SortOrder sortOrder = AggregationQueryBuilder.BuildSortOrder(olapQuery.getSortOrder(), tableDef);
		List<SearchResultList> results = new ArrayList<SearchResultList>();
		List<String> shardsList = olapQuery.getShards(application, this); 
		List<String> xshardsList = olapQuery.getXShards(application, this); 
    	XLinkContext xcontext = new XLinkContext(application, this, xshardsList, tableDef);
    	xcontext.setupXLinkQuery(tableDef, query);
		for(String shard : shardsList) {
			results.add(search(application, shard, tableDef, query, fieldSet, olapQuery, sortOrder));
		}
		SearchResultList result = MergeResult.merge(results, fieldSet);
		if(olapQuery.getSkip() > 0) {
			int sz = result.results.size();
			result.results = new ArrayList<SearchResult>(result.results.subList(Math.min(olapQuery.getSkip(), sz), sz));
		}
		return result;
	}

	private SearchResultList search(String application, String shard, TableDefinition tableDef, Query query, FieldSet fieldSet, OlapQuery olapQuery, SortOrder sortOrder) {
		// repeat if segment was merged
		for(int i = 0; i < 2; i++) {
			try {
				CubeSearcher s = getSearcher(application, shard, getCubeSegment(application, shard));
				SearchResultList result = Searcher.search(s, tableDef, query, fieldSet, olapQuery.getPageSizeWithSkip(), sortOrder);
				for(SearchResult sr: result.results) {
					sr.scalars.put("_shard", shard);
				}
				return result;
			}catch(FileDeletedException ex) {
				LOG.warn(ex.getMessage() + " - retrying: " + i);
				continue;
			}
		}
		CubeSearcher s = getSearcher(application, shard, getCubeSegment(application, shard));
		return Searcher.search(s, tableDef, query, fieldSet, olapQuery.getPageSizeWithSkip(), sortOrder);
	}
	
	// just merge
	public void merge(String application, String shard) {
		String key = application + "/" + shard;
		synchronized(m_mergedCubes) {
			if(m_mergedCubes.contains(key)) throw new IllegalArgumentException(key + " is being merged");
			m_mergedCubes.add(key);
		}
		try {
			Timer t = new Timer();
			ApplicationDefinition appDef = getApplicationDefinition(application);
			VDirectory shardDir = m_root.getDirectory(application).getDirectory(shard);
			List<String> segments = shardDir.listDirectories();
			if(segments.size() == 0) return;
			
			List<VDirectory> sources = new ArrayList<VDirectory>();
			for(String segment : segments) {
				sources.add(shardDir.getDirectory(segment));
			}
			
			String guid = ".cube." + UUID.randomUUID().toString();
			VDirectory destination = shardDir.getDirectory(guid);
			
			Merger.mergeApplication(appDef, sources, destination);
			
			shardDir.writeAllText(".cube.txt", guid);
			
			destination.create();
			
			for(String segment : segments) {
				shardDir.getDirectory(segment).delete();
			}
			
			LOG.debug("merge {} segments to {}/{} in {}", new Object[]{ segments.size(), application, shard, t} );
		} finally {
			synchronized(m_mergedCubes) {
				m_mergedCubes.remove(key);
			}
		}
	}
	
	public void merge(String application, String shard, Date expirationDate) {
		String key = application + "/" + shard;
		synchronized(m_mergedCubes) {
			if(m_mergedCubes.contains(key)) throw new IllegalArgumentException(key + " is being merged");
			m_mergedCubes.add(key);
		}
		try {
			Timer t = new Timer();
			ApplicationDefinition appDef = getApplicationDefinition(application);
			VDirectory shardDir = m_root.getDirectory(application).getDirectory(shard);
			String expDateStr = expirationDate == null ? "" : XType.toString(expirationDate);
			shardDir.writeAllText("expiration.txt", expDateStr);
			
			List<String> segments = shardDir.listDirectories();
			if(segments.size() == 0) {
				LOG.debug("No segments in {}/{}", application, shard);
				return;
			} else if(segments.size() == 1 && segments.get(0).startsWith(".cube.")) {
				LOG.debug("Shard {}/{} was not modified", application, shard);
				return;
			}
			
			List<VDirectory> sources = new ArrayList<VDirectory>();
			for(String segment : segments) {
				sources.add(shardDir.getDirectory(segment));
			}
			
			String guid = ".cube." + UUID.randomUUID().toString();
			VDirectory destination = shardDir.getDirectory(guid);
			
			Merger.mergeApplication(appDef, sources, destination);
			
			shardDir.writeAllText(".cube.txt", guid);
			
			destination.create();
			
			for(String segment : segments) {
				shardDir.getDirectory(segment).delete();
			}
			
			LOG.debug("merge {} segments to {}/{} in {}", new Object[]{ segments.size(), application, shard, t} );
		} finally {
			synchronized(m_mergedCubes) {
				m_mergedCubes.remove(key);
			}
		}
	}
	
	public Date getExpirationDate(String application, String shard) {
		VDirectory shardDir = m_root.getDirectory(application).getDirectory(shard);
		if(!shardDir.fileExists("expiration.txt")) return null;
		String expDateStr = shardDir.readAllText("expiration.txt"); 
		if(expDateStr.length() == 0) return null;
		else return Utils.dateFromString(expDateStr);
	}
	
	private CubeSearcher getSearcher(String app, String shard, String segment) {
		synchronized(m_cachedSearchers) {
			String key = app + "/" + shard + "/" + segment;
			CubeSearcher s = m_cachedSearchers.get(key);
			if(s == null) {
				VDirectory dir = m_root.getDirectory(app);
				dir = dir.getDirectory(shard);
				dir = dir.getDirectory(segment);
				s = new CubeSearcher(dir, m_fieldsCache);
				//m_cachedSearchers.put(key, s, s.getStats().memory() + 2 * key.length() + 16);
				m_cachedSearchers.put(key, s);
			}
			return s;
		}
	}

	public SearchResultList getDuplicateIDs(String application, String table, String shardsRange) {
		ApplicationDefinition appDef = getApplicationDefinition(application);
		if(appDef == null) throw new IllegalArgumentException("Application " + application + " not found");
		TableDefinition tableDef = appDef.getTableDef(table);
		if(tableDef == null) throw new IllegalArgumentException("Table " + table + " not found in " + application);
		List<String> shards = getShardsList(application, null, shardsRange);
		VDirectory appDir = m_root.getDirectory(application);
		List<VDirectory> dirs = new ArrayList<VDirectory>(shards.size());
		for(String shard : shards) {
			String segment = getCubeSegment(application, shard);
			VDirectory shardDir = appDir.getDirectory(shard);
			VDirectory segmentDir = shardDir.getDirectory(segment);
			dirs.add(segmentDir);
		}
		SearchResultList result = DuplicationDetection.getDuplicateIDs(tableDef, dirs, shards);
		return result;
	}
	
	public CubeSearcher getSearcher(String app, String shard) {
		String segment = getCubeSegment(app, shard);
		return getSearcher(app, shard, segment);
	}
	
	public List<String> getShardsList(String application, String shards, String shardsRange) {
    	if(shards != null && shardsRange != null) throw new IllegalArgumentException("Both shards and range parameters cannot be set");
    	if(shards == null && shardsRange == null) throw new IllegalArgumentException("shards or range parameter not set");
		List<String> shardsList = new ArrayList<String>();
    	if(shards != null) shardsList = Utils.split(shards, ',');
    	else if(shardsRange != null) {
    		String[] range = Utils.split(shardsRange, ',').toArray(new String[0]);
    		if(range.length == 0 || range.length > 2) throw new IllegalArgumentException("Shards range must be in form start-shard,end-shard or start-shard");
      		List<String> allShards = listShards(application);
    		String startShard = range[0];
    		String endShard = range.length == 1 ? null : range[1];
    		for(String shard : allShards) {
    			if(shard.compareToIgnoreCase(startShard) < 0) continue;
    			if(endShard != null && shard.compareToIgnoreCase(endShard) > 0) continue;
    			shardsList.add(shard);
    		}
    	}
    	return shardsList;
	}
	
	public OlapTasks getTasks(String appName) {
		VDirectory appDir = m_root.getDirectory(appName);
		OlapTasks tasks = new OlapTasks();
		if (appDir.fileExists("_tasks.json")) {
			tasks.parse(UNode.parseJSON(appDir.readAllText("_tasks.json")));
		}
		return tasks;
	}	// getTasks
	
	public void saveTasks(String appName, OlapTasks tasks) {
		m_root.getDirectory(appName).writeAllText("_tasks.json", tasks.toDoc().toJSON());
	}	// setTasks
	
	/**
	 * Creates/updates table of tasks statuses for a given application
	 * @param appName	Application name
	 */
// Alex: I presume this is no longer needed?	
//	private void correctTasksTable(String appName) {
//		OlapTasks tasks = getTasks(appName);
//		
//		String taskId = "*/data-aging";
//		TaskStatus status = tasks.getTaskStatus(taskId);
//		if (status == null) {
//			status = new TaskStatus();
//		}
//		tasks.getTasks().clear();
//		tasks.setTaskStatus(taskId, status);
//		saveTasks(appName, tasks);
//	}	// correctTasksTable

}
