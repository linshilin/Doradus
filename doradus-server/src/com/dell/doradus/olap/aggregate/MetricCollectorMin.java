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

package com.dell.doradus.olap.aggregate;

public abstract class MetricCollectorMin implements IMetricCollector {
	public long[] metric;

	@Override public void setSize(int size) { 
		metric = new long[size];
		for(int i = 0; i < size; i++) metric[i] = Long.MAX_VALUE;
	}
	
	@Override public int getSize() { return metric.length; }
	@Override public IMetricValue convert(IMetricValue value) { return value; }
	
	@Override public void add(int field, IMetricValue value) {
		MetricValueMin v = (MetricValueMin)value;
		if(metric[field] > v.metric) metric[field] = v.metric;
	}

	public static class MinNum extends MetricCollectorMin {
		@Override public IMetricValue get(int field) {
			MetricValueMin.MinNum v = new MetricValueMin.MinNum();
			if(field >= 0) v.metric = metric[field];
			return v;
		}
	}
	
	public static class MinDate extends MetricCollectorMin {
		@Override public IMetricValue get(int field) {
			MetricValueMin.MinDate v = new MetricValueMin.MinDate();
			if(field >= 0) v.metric = metric[field];
			return v;
		}
	}
	
	public static class MinBoolean extends MetricCollectorMin {
		@Override public IMetricValue get(int field) {
			MetricValueMin.MinBoolean v = new MetricValueMin.MinBoolean();
			if(field >= 0) v.metric = metric[field];
			return v;
		}
	}
	
}
