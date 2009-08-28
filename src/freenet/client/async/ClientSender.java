package freenet.client.async;

import java.io.File;
import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.SendContext;
import freenet.client.events.SplitfileProgressEvent;
import freenet.io.comm.DisconnectedException;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.RequestClient;
import freenet.node.fcp.UnknownNodeIdentityException;
import freenet.support.Logger;
import freenet.support.io.RandomAccessFileWrapper;
import freenet.support.io.RandomAccessThing;

public class ClientSender extends ClientRequester implements TransmitCompletionCallback {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetter.class);
	}

	private final SendContext ctx;
	private final ClientSendReceiveCallback clientCallback;
	private final File file;
	private final long uid;
	private byte[] nodeIdentity;
	private transient DarknetPeerNode peer;
	private transient BulkTransmitter transmitter;
	private boolean finished;

	public ClientSender(ClientSendReceiveCallback clientCallback, SendContext ctx, short priorityClass, File file, DarknetPeerNode peer, long uid, RequestClient clientContext) {
		super(priorityClass, clientContext);
		this.ctx = ctx;
		this.clientCallback = clientCallback;
		this.peer = peer;
		this.file = file;
		this.uid = uid;
		nodeIdentity = peer.getIdentity();
		long size = file.length();
		totalBlocks = (int) (size / Node.PACKET_SIZE + (size % Node.PACKET_SIZE > 0 ? 1 : 0));
		minSuccessBlocks = totalBlocks;
	}

	public void start(ObjectContainer container, ClientContext context) throws UnknownNodeIdentityException, DisconnectedException, IOException {
		start(false, container, context);
	}

	public void start(boolean restart, ObjectContainer container, ClientContext context) throws UnknownNodeIdentityException, DisconnectedException, IOException {
		if(logMINOR)
			Logger.minor(this, restart ? "Restarting" : "Starting " + this);

		if(peer == null) {
			container.activate(nodeIdentity, 1);
			peer = (DarknetPeerNode) context.peers.getByIdentity(nodeIdentity);
			if(peer == null)
				throw new UnknownNodeIdentityException(nodeIdentity);
		}
		if(transmitter == null) {
			RandomAccessThing data = new RandomAccessFileWrapper(file, "r");
			PartiallyReceivedBulk prb = new PartiallyReceivedBulk(context.usm, data.size(), Node.PACKET_SIZE, data, true);
			transmitter = new BulkTransmitter(context.jobRunner, this, prb, peer, uid, true, context.nodeToNodeCounter);
		}
		if(restart)
			transmitter.cancel("Restart");
		context.mainExecutor.execute(new Runnable() {

			public void run() {
				transmitter.send();
			}
		});
		container.store(this);
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
		transmitter.cancel("Cancelled");
		container.store(this);
		clientCallback.onFailure(container, context);
	}

	@Override
	public String toString() {
		return super.toString();
	}

	@Override
	public FreenetURI getURI() {
		return null;
	}

	@Override
	protected void innerToNetwork(ObjectContainer container,
			ClientContext context) {
	}

	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
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

	public void reject() {
		transmitter.cancel("FileOffer: Offer rejected");
		// FIXME prb's can't be shared, right? Well they aren't here...
//		prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cancelled by receiver");
	}

	public void onSuccess(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this ,"Succeeded");
		synchronized(this) {
			finished = true;
		}
		container.store(this);
		container.activate(clientCallback, 1);
		clientCallback.onSuccess(container, context);
		container.deactivate(clientCallback, 1);
	}

	public void restart(ObjectContainer container, ClientContext context) throws UnknownNodeIdentityException, DisconnectedException, IOException {
		start(true, container, context);
	}

	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		//nodeIdentity and file are removed by the caller, requestWasRemoved in ClientSend
		super.removeFrom(container, context);
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		container.activate(ctx, 1);
		container.activate(ctx.eventProducer, 1);
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, true), container, context);
	}

}
