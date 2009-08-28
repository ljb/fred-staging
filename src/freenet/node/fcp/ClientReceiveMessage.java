package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.DarknetPeerNode;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ClientReceiveMessage extends FCPMessage {

	public static final String NAME = "ClientReceive";
	public final String identifier;
	public final long uid;
	public final int verbosity;
	public final boolean global;
	public boolean accept;
	public final String nodeIdentifier;
	public final String clientToken;

	public ClientReceiveMessage(SimpleFieldSet fs) throws MessageInvalidException {
			clientToken = fs.get("ClientToken");
			nodeIdentifier = fs.get("NodeIdentifier");
			identifier = fs.get("Identifier");
			global = Fields.stringToBool(fs.get("Global"), false);
			accept = Fields.stringToBool(fs.get("Accept"), true);

			try {
				uid = fs.getLong("Uid");
			} catch (FSParseException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Invalid uid", null, true);
			}

			if (identifier == null)
				throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null,
						true);

		String verbosityString = fs.get("Verbosity");
		if(verbosityString == null)
			verbosity = 0;
		else {
			try {
				verbosity = Integer.parseInt(verbosityString, 10);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Error parsing Verbosity field: "+e.getMessage(), identifier, global);
			}
		}
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("NodeIdentifier", nodeIdentifier);
		fs.putSingle("Global", Boolean.toString(global));
		fs.putSingle("ClientToken", clientToken);
		fs.put("Uid", uid);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		PeerNode peer = node.getPeerNode(nodeIdentifier);
		if(peer != null && peer instanceof DarknetPeerNode) {
			DarknetPeerNode darkPeer = (DarknetPeerNode)peer;
			if(accept)
				darkPeer.acceptTransfer(this, handler);
			else
				darkPeer.rejectTransfer(uid);
			}
	}

}
