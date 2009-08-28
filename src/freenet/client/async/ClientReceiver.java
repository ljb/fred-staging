package freenet.client.async;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.ReceiveContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BulkReceiver;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.RequestClient;
import freenet.node.fcp.UnknownNodeIdentityException;
import freenet.support.Logger;
import freenet.support.io.RandomAccessFileWrapper;
import freenet.support.io.RandomAccessThing;

public class ClientReceiver extends ClientRequester implements TransmitCompletionCallback {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetter.class);
	}

	private ClientSendReceiveCallback clientCallback;
	private final byte[] nodeIdentity;
	private final long uid;
	private final long size;
	private final File file;
	private long blocksReceived;
	private transient DarknetPeerNode peer;
	private transient BulkReceiver receiver;
	private transient PartiallyReceivedBulk prb;
	private boolean finished;
	private final ReceiveContext ctx;

	public ClientReceiver(ClientSendReceiveCallback clientCallback, ReceiveContext ctx, File file, DarknetPeerNode peer, long uid, long size,
		    short priorityClass, RequestClient clientContext, ObjectContainer container) throws DisconnectedException, IOException {
		super(priorityClass, clientContext);
		this.clientCallback = clientCallback;
		this.ctx = ctx;
		this.finished = false;
		this.peer = peer;
		this.uid = uid;
		this.size = size;
		this.file = file;
		nodeIdentity = peer.getIdentity();
		totalBlocks = (int) (size / Node.PACKET_SIZE + (size % Node.PACKET_SIZE > 0 ? 1 : 0));
		minSuccessBlocks = totalBlocks;
	}

	public void start(ObjectContainer container, ClientContext context) throws FileNotFoundException, UnknownNodeIdentityException {
		if(peer == null) {
			container.activate(nodeIdentity, 5);
			peer = (DarknetPeerNode) context.peers.getByIdentity(nodeIdentity);
			if(peer == null)
				throw new UnknownNodeIdentityException(nodeIdentity);
		}
		if(prb == null) {
			RandomAccessThing data = new RandomAccessFileWrapper(file, "rw");
			prb = new PartiallyReceivedBulk(context.usm, size, Node.PACKET_SIZE, data, blocksReceived);
			receiver = new BulkReceiver(context.jobRunner, this, prb, peer, uid, null);
		}
		if(logMINOR)
			Logger.minor(this, "Starting " + this +" persistent=" + persistent());
		context.mainExecutor.execute(new Runnable() {

			public void run() {
				receiver.receive();
			}
		});
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Canceling " + this + "persistent=" + persistent());
		synchronized(this) {
			if(super.cancel()) {
				if(logMINOR)
					Logger.minor(this, "Already cancelled " + this);
				return;
			}
		}
		prb.abort(RetrievalException.RECEIVER_DIED, "cancel");

	}

	@Override
	public FreenetURI getURI() {
		return null;
	}

	@Override
	public String toString() {
		return super.toString();
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {

	}

	@Override
	public boolean isFinished() {
		return finished;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		container.activate(ctx, 1);
		container.activate(ctx.eventProducer, 1);
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, true), container, context);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState,
			ObjectContainer container) {
	}

	public void onBlockFinished(ObjectContainer container, ClientContext context) {
		completedBlock(false, container, context);
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Failed");
		container.activate(clientCallback, 1);
		clientCallback.onFailure(container, context);
	}

	public void onSuccess(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this ,"Succeeded");
		finished = true;
		container.store(this);
		container.activate(clientCallback, 1);
		clientCallback.onSuccess(container, context);
	}

}
