package freenet.node.fcp;

import freenet.support.SimpleFieldSet;

public class ReceivedFileOfferFeedMessage extends ReceivedN2NFeedMessage {

	public static final String NAME = "ReceivedFileOfferFeedMessage";
	private final long uid;
	public final String filename;
	public final String nodeIdentifier;
	public final long size;
	public final String mimeType;

	public ReceivedFileOfferFeedMessage(String header, String shortText, String text, String nodeIdentifier, String filename, String mimeType, long uid, long size) {
		super(header, shortText, text, nodeIdentifier, -1, -1, -1);
		this.uid = uid;
		this.filename = filename;
		this.nodeIdentifier = nodeIdentifier;
		this.size = size;
		this.mimeType = mimeType;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = super.getFieldSet();
		fs.put("Uid", uid);
		fs.put("Size", size);
		fs.putSingle("Filename", filename);
		fs.putSingle("NodeIdentifier", nodeIdentifier);
		fs.putSingle("MimeType", mimeType);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

}