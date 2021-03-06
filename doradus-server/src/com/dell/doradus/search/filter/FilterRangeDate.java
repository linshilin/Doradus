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

package com.dell.doradus.search.filter;

import java.util.Date;
import java.util.Set;

import com.dell.doradus.search.aggregate.Entity;
import com.dell.doradus.search.analyzer.DateTrie;
import com.dell.doradus.search.query.RangeQuery;

public class FilterRangeDate implements Filter {
    private DateTrie m_trie = new DateTrie();
    private RangeQuery m_range;
    private Date m_min;
    private Date m_max;
    
    public FilterRangeDate(RangeQuery range) {
        m_range = range;
        if(range.min != null) m_min = m_trie.parse(range.min);
        if(range.max != null) m_max = m_trie.parse(range.max);
    }
    
    @Override public boolean check(Entity entity) {
        String value = entity.get(m_range.field);
        if(value == null) return false;
        Date v;
        try {
            v = m_trie.parse(value);
        }catch(NumberFormatException e) {
            return false;
        }
        if(m_min != null) {
            int c = v.compareTo(m_min);
            if(c == 0 && m_range.minInclusive) c = 1;
            if(c <= 0) return false;
        }
        if(m_range.max != null) {
            int c = v.compareTo(m_max);
            if(c == 0 && m_range.maxInclusive) c = -1;
            if(c >= 0) return false;
        }
        return true;
    }

    @Override public void addFields(Set<String> fields) {
        fields.add(m_range.field);
    }
    
}
