package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ClientSendMessage extends FCPMessage {

	public static final String NAME = "ClientSend";
	final String identifier;
	final String filename;
	final boolean global;
	final String nodeIdentifier;
	final String clientToken;
	final int verbosity;

	public ClientSendMessage(SimpleFieldSet fs) throws MessageInvalidException {
		clientToken = fs.get("ClientToken");
		nodeIdentifier = fs.get("NodeIdentifier");
		identifier = fs.get("Identifier");
		global = Fields.stringToBool(fs.get("Global"), false);
		if (identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null,
					global);
		filename = fs.get("Filename");
		if (filename == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field Filename",
					identifier, global);
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
		fs.putSingle("Filename", filename);
		fs.putSingle("ClientToken", clientToken);
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
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		File file = new File(filename);
		PeerNode peer = node.getPeerNode(nodeIdentifier);
		if (peer != null && peer instanceof DarknetPeerNode) {
			long uid = node.random.nextLong();
			handler.startClientSend(this, node, (DarknetPeerNode)peer, file, uid);
		}
	}

}
