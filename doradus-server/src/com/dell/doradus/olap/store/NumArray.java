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

package com.dell.doradus.olap.store;

import com.dell.doradus.olap.io.VInputStream;

public class NumArray {
	private int m_size;
	private int m_bits;
	private BitVector m_bitArray;
	private byte[] m_byteArray;
	private short[] m_shortArray;
	private int[] m_intArray;
	private long[] m_longArray;
	

	public NumArray(int size, int bits) {
		m_size = size;
		m_bits = bits;
		switch(bits) {
			case 0 : break;
			case 1 : m_bitArray = new BitVector(size); break; 
			case 8 : m_byteArray = new byte[size]; break; 
			case 16 : m_shortArray = new short[size]; break; 
			case 32 : m_intArray = new int[size]; break; 
			case 64 : m_longArray = new long[size]; break; 
			default: throw new RuntimeException("Unknown bits: " + bits);
		}
	}
	
	public int size() { return m_size; }
	
	public long get(int index) {
		switch(m_bits) {
			case 0 : return 0;
			case 1 : return m_bitArray.get(index) ? 1 : 0; 
			case 8 : return m_byteArray[index]; 
			case 16 : return m_shortArray[index]; 
			case 32 : return m_intArray[index]; 
			case 64 : return m_longArray[index]; 
			default: throw new RuntimeException("Unknown bits: " + m_bits);
		}
	}
	
	public void set(int index, long value) {
		switch(m_bits) {
			case 0 : break;
			case 1 : if(value != 0) m_bitArray.set(index); else m_bitArray.clear(index); break; 
			case 8 : m_byteArray[index] = (byte)value; break; 
			case 16 : m_shortArray[index] = (short)value; break; 
			case 32 : m_intArray[index] = (int)value; break; 
			case 64 : m_longArray[index] = value; break; 
			default: throw new RuntimeException("Unknown bits: " + m_bits);
		}
	}
	
	public void load(VInputStream input) {
		switch(m_bits) {
		case 0 : break;
		case 1 :
			input.read(m_bitArray.getBuffer(), 0, m_bitArray.getBuffer().length);  
			break; 
		case 8 :
			for(int i = 0; i < m_size; i++) m_byteArray[i] = (byte)input.readByte();
			break;
		case 16 :
			for(int i = 0; i < m_size; i++) m_shortArray[i] = input.readShort();
			break;
		case 32 :
			for(int i = 0; i < m_size; i++) m_intArray[i] = input.readInt();
			break;
		case 64 :
			for(int i = 0; i < m_size; i++) m_longArray[i] = input.readLong();
			break;
		default: throw new RuntimeException("Unknown bits: " + m_bits);
		}
	}
	
	public long cacheSize()
	{
		long size = 16;
		if(m_bitArray != null) size += m_bitArray.getBuffer().length * 4;
		if(m_byteArray != null) size += m_byteArray.length * 1;
		if(m_shortArray != null) size += m_shortArray.length * 2;
		if(m_intArray != null) size += m_intArray.length * 4;
		if(m_longArray != null) size += m_longArray.length * 8;
		return size;
	}
	
}
