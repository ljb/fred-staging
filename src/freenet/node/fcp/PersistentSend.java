package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PersistentSend extends FCPMessage {
	public static final String NAME = "PersistentSend";
	private final String identifier;
	private final boolean global;
	private final File file;
	private final int verbosity;
	private final boolean started;
	private final long uid;

	public PersistentSend(String identifier, int verbosity, boolean global, boolean started, File file, long uid) {
		this.identifier = identifier;
		this.global = global;
		this.file = file;
		this.verbosity = verbosity;
		this.started = started;
		this.uid = uid;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("Filename", file.getAbsolutePath());
		fs.put("Verbosity", verbosity);
		fs.put("Global", global);
		fs.put("Started", started);
		fs.put("Uid", uid);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(file, 1);
		container.delete(file);
		container.delete(this);
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentSend goes from server to client not the other way around", identifier, global);
	}

}
