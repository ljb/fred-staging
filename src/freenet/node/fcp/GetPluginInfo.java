/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

/**
 * can find a plugin that implements FredPluginFCP
 * 
 */
public class GetPluginInfo extends FCPMessage {

	static final String NAME = "GetPluginInfo";

	private final String identifier;
	private final boolean detailed;
	private final String plugname;

	public GetPluginInfo(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "GetPluginInfo must contain an Identifier field", null, false);
		plugname = fs.get("PluginName");
		if(plugname == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "GetPluginInfo must contain a PluginName field", identifier, false);
		detailed = Fields.stringToBool(fs.get("Detailed"), false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(detailed && !handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "GetPluginInfo detailed requires full access", identifier, false);
		}

		PluginInfoWrapper pi = node.pluginManager.getFCPPluginInfo(plugname);
		if (pi == null) {
			handler.outputHandler.queue(new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_PLUGIN, false, "Plugin '"+ plugname + "' does not exist or is not a FCP plugin", identifier, false));
		} else {
			handler.outputHandler.queue(new PluginInfoMessage(pi, identifier, detailed));
		}
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
}
