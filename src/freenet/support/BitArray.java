/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.support;

import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.io.WritableToDataOutputStream;

public class BitArray implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: BitArray.java,v 1.2 2005/08/25 17:28:19 amphibian Exp $";

	private final int _size;
	private final byte[] _bits;

	public BitArray(byte[] data) {
		_bits = data;
		_size = data.length*8;
	}
	
	/**
	 * This constructor does not check for unacceptable sizes, and should only be used on trusted data.
	 */
	public BitArray(DataInput dis) throws IOException {
		_size = dis.readInt();
		_bits = new byte[(_size / 8) + (_size % 8 == 0 ? 0 : 1)];
		dis.readFully(_bits);
	}
	
	public BitArray(DataInput dis, int maxSize) throws IOException {
		_size = dis.readInt();
		if (_size<=0 || _size>maxSize)
			throw new IOException("Unacceptable bitarray size: "+_size);
		_bits = new byte[(_size / 8) + (_size % 8 == 0 ? 0 : 1)];
		dis.readFully(_bits);
	}

	public BitArray(int size) {
		_size = size;
		_bits = new byte[(size / 8) + (size % 8 == 0 ? 0 : 1)];
	}

	public BitArray(BitArray src) {
		this._size = src._size;
		this._bits = new byte[src._bits.length];
		System.arraycopy(src._bits, 0, _bits, 0, src._bits.length);
	}
	
	public void setBit(int pos, boolean f) {
		int b = unsignedByteToInt(_bits[pos / 8]);
		int mask = (1 << (pos % 8));
		if (f) {
			_bits[pos / 8] = (byte) (b | mask);
		} else {
			_bits[pos / 8] = (byte) (b & (~mask));
		}
	}

	public boolean bitAt(int pos) {
		int b = unsignedByteToInt(_bits[pos / 8]);
		int mask = (1 << (pos % 8));
		return (b & mask) != 0;
	}

	public static int unsignedByteToInt(byte b) {
		return b & 0xFF;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(this._size);
		for (int x=0; x<_size; x++) {
			if (bitAt(x)) {
				sb.append('1');
			} else {
				sb.append('0');
			}
		}
		return sb.toString();
	}

	public void writeToDataOutputStream(DataOutputStream dos) throws IOException {
		dos.writeInt(_size);
		dos.write(_bits);
	}

	public static int serializedLength(int size) {
		return ((size / 8) + (size % 8 == 0 ? 0 : 1)) + 4;
	}

	public int getSize() {
		return _size;
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof BitArray)) {
			return false;
		}
		BitArray ba = (BitArray) o;
		if (ba.getSize() != getSize()) {
			return false;
		}
		for (int x=0; x<getSize(); x++) {
			if (ba.bitAt(x) != bitAt(x)) {
				return false;
			}
		}
		return true;
	}
	
	@Override
	public int hashCode() {
	    return Fields.hashCode(_bits);
	}

	public void setNFirstOnes(long n) {
		for(int i=0;i<_bits.length;i++) {
			if(n <= 0)
				return;
			else if(n >= 8) {
				_bits[i] = (byte)0xFF;
				n -= 8;
			}
			else {
				_bits[i] = (byte)(Math.pow(2, n) - 1);
				return;
			}
		}
	}

	public void setAllOnes() {
		for(int i=0;i<_bits.length;i++)
			_bits[i] = (byte)0xFF;
	}

	public int firstOne() {
		for(int i=0;i<_bits.length;i++) {
			byte b = _bits[i];
			if(b == 0) continue;
			for(int j=0;j<8;j++) {
				int mask = (1 << j);
				if((b & mask) != 0) {
					int x = i*8+j;
					if(x >= _size) return -1;
					return x;
				}
			}
		}
		return -1;
	}
}