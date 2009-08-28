package freenet.node.fcp;

public class UnknownNodeIdentityException extends Exception {

	public final byte[] nodeIdentity;

	public UnknownNodeIdentityException(byte[] nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

}
