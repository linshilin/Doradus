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

package com.dell.doradus.olap.merge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.FieldDefinition;
import com.dell.doradus.common.FieldType;
import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.olap.io.VDirectory;
import com.dell.doradus.olap.store.FieldSearcher;
import com.dell.doradus.olap.store.FieldWriter;
import com.dell.doradus.olap.store.FieldWriterSV;
import com.dell.doradus.olap.store.IdReader;
import com.dell.doradus.olap.store.IdWriter;
import com.dell.doradus.olap.store.NumSearcher;
import com.dell.doradus.olap.store.NumWriter;
import com.dell.doradus.olap.store.SegmentStats;
import com.dell.doradus.olap.store.ValueReader;
import com.dell.doradus.olap.store.ValueWriter;
import com.dell.doradus.search.util.HeapList;
import com.dell.doradus.utilities.Timer;

public class Merger {
    private static Logger LOG = LoggerFactory.getLogger("Olap.Merger");
    
    private ApplicationDefinition appDef;
    private List<VDirectory> sources;
    private VDirectory destination;
    private SegmentStats stats;
    private Map<String, Remap> remaps = new HashMap<String, Remap>();
	
	public static void mergeApplication(ApplicationDefinition appDef, List<VDirectory> sources, VDirectory destination) {
		Merger m = new Merger(appDef, sources, destination);
		m.mergeApplication();
	}

	public Merger(ApplicationDefinition appDef, List<VDirectory> sources, VDirectory destination) {
		this.appDef = appDef;
		this.sources = sources;
		this.destination = destination;
	}
	
	public void mergeApplication() {
		Timer timer = new Timer();
		LOG.debug("Merging application {}", appDef.getAppName());
		stats = new SegmentStats();
		for(TableDefinition tableDef : appDef.getTableDefinitions().values()) {
			String table = tableDef.getTableName();
			LOG.debug("   Merging {}", table);
			mergeDocs(tableDef);
		}
		for(TableDefinition tableDef : appDef.getTableDefinitions().values()) {
			String table = tableDef.getTableName();
			LOG.debug("   Merging fields of table {}", table);
			for(FieldDefinition fieldDef : tableDef.getFieldDefinitions()) {
				LOG.debug("      Merging {}/{} ({})", new Object[] {table, fieldDef.getName(), fieldDef.getType()});
				mergeField(fieldDef);
			}
		}
		
		stats.totalStoreSize = destination.totalLength(false);
		stats.save(destination);
		LOG.debug("Application {} merged in {}", appDef.getAppName(), timer);
	}
	
    private void mergeDocs(TableDefinition tableDef) {
    	String table = tableDef.getTableName();
        Remap remap = new Remap(sources.size());
        IdWriter id_writer = new IdWriter(destination, table);
        
        HeapList<IxDoc> heap = new HeapList<IxDoc>(sources.size() - 1);
        IxDoc current = null;
        for(int i = 0; i < sources.size(); i++) {
            current = new IxDoc(i, new IdReader(sources.get(i), table));
            current.next();
            current = heap.AddEx(current);
        }

        while (current.id != null)
        {
        	int dstDoc = id_writer.add(current.id);
            remap.set(current.segment, current.reader.cur_number, dstDoc);
            if(current.reader.is_deleted) {
            	remap.setDeleted(current.segment, current.reader.cur_number, dstDoc);
            	id_writer.removeLastId(current.id);
            }
            current.next();
            current = heap.AddEx(current);
        }
        
        remap.shrink();
        remaps.put(table, remap);
        id_writer.close();
        stats.addTable(table, id_writer.size());
    }
    
	private void mergeField(FieldDefinition fieldDef) {
		if(fieldDef.getType() == FieldType.TEXT || fieldDef.getType() == FieldType.BINARY) {
			mergeTextField(fieldDef);
		} else if(fieldDef.isLinkField()) {
			mergeLinkField(fieldDef);
		} else if(NumSearcher.isNumericType(fieldDef.getType())) {
			mergeNumField(fieldDef);
		} else if(fieldDef.isGroupField() || fieldDef.isXLinkField()) {
			// do nothing
		} else throw new RuntimeException("Unsupported field type: " + fieldDef.getType());
	}
	
    private void mergeNumField(FieldDefinition fieldDef)
    {
		String table = fieldDef.getTableName();
		String field = fieldDef.getName();
		Remap remap = remaps.get(table);
        NumWriter num_writer = new NumWriter(remap.dstSize());
        
        for(int i = 0; i < sources.size(); i++) {
        	NumSearcher num_searcher = new NumSearcher(sources.get(i), table, field);
        	for(int j = 0; j < remap.size(i); j++) {
        		if(num_searcher.isNull(j)) continue;
        		long d = num_searcher.get(j);
        		int doc = remap.get(i, j);
        		if(doc < 0) continue;
        		num_writer.add(doc, d); 
        	}
        }
        
        num_writer.close(destination, table, field);
        stats.addNumField(fieldDef, num_writer);
    }
	
    private void mergeTextField(FieldDefinition fieldDef) {
		String table = fieldDef.getTableName();
		String field = fieldDef.getName();
		Remap valRemap = new Remap(sources.size());
		{
	        ValueWriter value_writer = new ValueWriter(destination, table, field);
	        
	        HeapList<IxTerm> heap = new HeapList<IxTerm>(sources.size() - 1);
	        IxTerm current = null;
	        for(int i = 0; i < sources.size(); i++) {
	            current = new IxTerm(i, new ValueReader(sources.get(i), table, field));
	            current.next();
	            current = heap.AddEx(current);
	        }
	
	        while (current.term != null)
	        {
	        	int dstVal = value_writer.add(current.term, current.orig);
	        	valRemap.set(current.segment, current.reader.cur_number, dstVal);
	            current.next();
	            current = heap.AddEx(current);
	        }
	        
	        value_writer.close();
		}
		
        Remap docRemap = remaps.get(table);
        
        if(fieldDef.isCollection()) {
	        FieldWriter field_writer = new FieldWriter(docRemap.dstSize());
	        
	        HeapList<IxVal> heap = new HeapList<IxVal>(sources.size() - 1);
	        IxVal current = null;
	        for(int i = 0; i < sources.size(); i++) {
	            current = new IxVal(i, docRemap, valRemap, new FieldSearcher(sources.get(i), table, field));
	            current.next();
	            current = heap.AddEx(current);
	        }
	
	        while (current.doc != Integer.MAX_VALUE)
	        {
	        	field_writer.add(current.doc, current.val);
	            current.next();
	            current = heap.AddEx(current);
	        }
	        
	        field_writer.close(destination, table, field);
	        stats.addTextField(fieldDef, field_writer);
        }
        else {
	        FieldWriterSV field_writer = new FieldWriterSV(docRemap.dstSize());
	        
	        HeapList<IxSeg> heap = new HeapList<IxSeg>(sources.size() - 1);
	        IxSeg current = null;
	        for(int i = 0; i < sources.size(); i++) {
	            current = new IxSeg(i, docRemap, valRemap, new FieldSearcher(sources.get(i), table, field));
	            current.next();
	            current = heap.AddEx(current);
	        }
	
	        while (current.doc != Integer.MAX_VALUE)
	        {
	        	field_writer.add(current.doc, current.val);
	            current.next();
	            current = heap.AddEx(current);
	        }
	        
	        field_writer.close(destination, table, field);
	        stats.addTextField(fieldDef, field_writer);
        }
        
    }

    private void mergeLinkField(FieldDefinition fieldDef)
    {
		String table = fieldDef.getTableName();
		String link = fieldDef.getName();
		
        Remap docRemap = remaps.get(table);
        Remap valRemap = remaps.get(fieldDef.getLinkExtent());
        
        FieldWriter field_writer = new FieldWriter(docRemap.dstSize());
        
        HeapList<IxVal> heap = new HeapList<IxVal>(sources.size() - 1);
        IxVal current = null;
        for(int i = 0; i < sources.size(); i++) {
            current = new IxVal(i, docRemap, valRemap, new FieldSearcher(sources.get(i), table, link));
            current.next();
            current = heap.AddEx(current);
        }

        while (current.doc != Integer.MAX_VALUE)
        {
        	field_writer.add(current.doc, current.val);
            current.next();
            current = heap.AddEx(current);
        }
        
        field_writer.close(destination, table, link);
        stats.addLinkField(fieldDef, field_writer);
    }

}
