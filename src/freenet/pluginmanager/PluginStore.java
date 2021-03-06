/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.util.HashMap;

/**
 * This is a PluginStore. Plugins can use that to store all kinds of primary
 * data types with as many recursion level as needed.
 * @author Artefact2
 */
public class PluginStore {
	public HashMap<String, PluginStore> subStores = new HashMap<String, PluginStore>();
	public HashMap<String, Long> longs = new HashMap<String, Long>();
	public HashMap<String, long[]> longsArrays = new HashMap<String, long[]>();
	public HashMap<String, Integer> integers = new HashMap<String, Integer>();
	public HashMap<String, int[]> integersArrays = new HashMap<String, int[]>();
	public HashMap<String, Short> shorts = new HashMap<String, Short>();
	public HashMap<String, short[]> shortsArrays = new HashMap<String, short[]>();
	public HashMap<String, Boolean> booleans = new HashMap<String, Boolean>();
	public HashMap<String, boolean[]> booleansArrays = new HashMap<String, boolean[]>();
	public HashMap<String, Byte> bytes = new HashMap<String, Byte>();
	public HashMap<String, byte[]> bytesArrays = new HashMap<String, byte[]>();
	public HashMap<String, String> strings = new HashMap<String, String>();
	public HashMap<String, String[]> stringsArrays = new HashMap<String, String[]>();
}
