package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public abstract class ReceivedN2NFeedMessage extends ReceivedFeedMessage {

	protected final String sourceNodeName;
	protected final long composed, sent, received;

	public ReceivedN2NFeedMessage(String header, String shortText, String text,
			String sourceNodeName, long composed, long sent, long received) {
		super(header, shortText, text);
		this.sourceNodeName = sourceNodeName;
		this.composed = composed;
		this.sent = sent;
		this.received = received;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.putSingle("SourceNodeName", sourceNodeName);
		if (composed != -1)
			fs.put("TimeComposed", composed);
		if (sent != -1)
			fs.put("TimeSent", sent);
		if (received != -1)
			fs.put("TimeReceived", received);
		return fs;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, getName()
				+ " goes from server to client not the other way around", null, false);
	}

}
