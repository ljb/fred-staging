package freenet.node.fcp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import com.db4o.ObjectContainer;
import com.onionnetworks.util.FileUtil;

import freenet.client.ReceiveContext;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientReceiver;
import freenet.client.async.ClientRequester;
import freenet.client.async.ClientSendReceiveCallback;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.io.comm.DisconnectedException;
import freenet.node.DarknetPeerNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class ClientReceive extends ClientRequest implements ClientSendReceiveCallback, ClientEventListener {

	private transient DarknetPeerNode peer;
	private final byte[] nodeIdentity;
	private final File targetFile;
	private final long size;
	private boolean succeeded;

	private final ReceiveContext ctx;

	private SimpleProgressMessage progressPending;
	private final String mimeType;
	private final ClientReceiver receiver;
	private final long uid;
	private String failureReason;
	// Verbosity bitmask
	private static final int VERBOSITY_SPLITFILE_PROGRESS = 1;

	public ClientReceive(FCPClient globalClient, String identifier, String clientToken, DarknetPeerNode peer, int verbosity, File file, long size, String mimeType, long uid, FCPServer server, ObjectContainer container) throws DisconnectedException, IOException {
		super(null, identifier, verbosity, null, globalClient, (short) 4, ClientRequest.PERSIST_FOREVER, clientToken, true, container);
		ctx = new ReceiveContext(server.defaultReceiveContext, false);
		ctx.eventProducer.addEventListener(this);

		//FIXME Use node.clientCore.downloadDir
		this.targetFile = new File("/downloads/direct-"+FileUtil.sanitizeFileName(peer.getName())+"-"+file);
		this.size = size;
		this.mimeType = mimeType;
		this.peer = peer;
		this.uid = uid;
		nodeIdentity = peer.getIdentity();
		receiver = new ClientReceiver(this, ctx, file, peer, uid, size, priorityClass, lowLevelClient, container);
	}

	public ClientReceive(FCPConnectionHandler handler, ClientReceiveMessage message, String filename, String mimeType, long size, DarknetPeerNode peer, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MessageInvalidException, IOException, DisconnectedException {
		super(null, message.identifier, 0, handler, (short) 4, ClientRequest.PERSIST_FOREVER, message.clientToken, message.global, container);
		ctx = new ReceiveContext(server.defaultReceiveContext, false);
		ctx.eventProducer.addEventListener(this);

		//FIXME Use node.clientCore.downloadDir
		this.targetFile = new File("/downloads/direct-"+FileUtil.sanitizeFileName(peer.getName())+"-"+filename);
		this.size = size;
		this.mimeType = mimeType;
		this.peer = peer;
		this.uid = message.uid;
		nodeIdentity = peer.getIdentity();
		receiver = new ClientReceiver(this, ctx, new File(filename), peer, uid, size, priorityClass, lowLevelClient, container);
	}

	@Override
	public boolean canRestart() {
		return false;
	}

	@Override
	protected void freeData(ObjectContainer container) {
	}

	@Override
	protected ClientRequester getClientRequest() {
		return receiver;
	}

	@Override
	public String getFailureReason(ObjectContainer container) {
		return failureReason;
	}

	@Override
	public SimpleFieldSet getFieldSet() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public double getSuccessFraction(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getFraction();
		} else
			return -1;
	}

	@Override
	public double getTotalBlocks(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getTotalBlocks();
		} else
			return -1;
	}

	@Override
	public double getMinBlocks(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getMinBlocks();
		} else
			return -1;
	}

	@Override
	public double getFailedBlocks(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getFailedBlocks();
		} else
			return -1;
	}

	@Override
	public double getFatalyFailedBlocks(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getFatalyFailedBlocks();
		} else
			return -1;
	}

	@Override
	public double getFetchedBlocks(ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 2);
			return progressPending.getFetchedBlocks();
		} else
			return -1;
	}

	public long getSize(ObjectContainer container) {
		return size;
	}

	@Override
	public boolean hasSucceeded() {
		return succeeded;
	}

	@Override
	public boolean isTotalFinalized(ObjectContainer container) {
		return true;
	}

	@Override
	public void onLostConnection(ObjectContainer container, ClientContext context) {

	}

	@Override
	protected FCPMessage persistentTagMessage(ObjectContainer container) {
		container.activate(targetFile, 5);
		return new PersistentReceive(identifier, targetFile, verbosity, succeeded, started, uid);
	}

	@Override
	public boolean restart(ObjectContainer container, ClientContext context) throws DatabaseDisabledException {
		return true;
	}

	@Override
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest,
			boolean includeData, boolean onlyData, ObjectContainer container) {

		if(!onlyData) {
			if(includePersistentRequest) {
				FCPMessage msg = persistentTagMessage(container);
				handler.queue(msg);
			}
			if(progressPending != null) {
				container.activate(progressPending, 5);
				handler.queue(progressPending);
			}
		}

	}

	@Override
	public void start(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			started = true;
		}

		try {
			receiver.start(container, context);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			onFailure(container, context);
		} catch (UnknownNodeIdentityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			onFailure(container, context);
		}

		if(!finished) {
			FCPMessage msg = persistentTagMessage(container);
			client.queueClientRequestMessage(msg, 0, container);
		}

		container.store(this);
	}

	@Override
	void register(ObjectContainer container, boolean noTags)
			throws IdentifierCollisionException {
		if(client != null)
			assert(this.persistenceType == client.persistenceType);
			try {
				client.register(this, container);
			} catch (IdentifierCollisionException e) {
				throw e;
			}
	}

	public void onFailure(ObjectContainer container, ClientContext context) {
		if(finished)
			return;
		synchronized(this) {
			finished = true;
			succeeded = false;
			started = true;
		}
		container.activate(client, 1);
		finish(container);
		if(client != null)
			client.notifyFailure(this, container);
		container.store(this);
	}

	public void onSuccess(ObjectContainer container, ClientContext context) {
		Logger.minor(this, "Succeeded: " + identifier);
		synchronized(this) {
			finished = true;
			succeeded = true;
		}
	}

	public String getMIMEType() {
		return mimeType;
	}

	public File getDestFilename(ObjectContainer container) {
		container.activate(targetFile, 5);
		return targetFile;
	}

	public String getRecipient(ObjectContainer container, ClientContext context) {
		if(peer == null)
			peer = (DarknetPeerNode) context.peers.getByIdentity(nodeIdentity);
		if(peer == null || !(peer instanceof DarknetPeerNode))
			try {
				throw new UnknownNodeIdentityException(nodeIdentity);
			} catch (UnknownNodeIdentityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		return peer.getName();
	}

	public void onRemoveEventProducer(ObjectContainer container) {

	}

	public void receive(final ClientEvent ce, ObjectContainer container, ClientContext context) {
		if(container == null) {
			try {
				context.jobRunner.queue(new DBJob() {
					public boolean run(ObjectContainer container, ClientContext context) {
						receive(ce, container, context);
						return false;
					}
				}, NativeThread.HIGH_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else if(ce instanceof SplitfileProgressEvent){
			if(!((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS))
				return;
			SimpleProgressMessage progress = new SimpleProgressMessage(identifier, global, (SplitfileProgressEvent)ce);
			trySendProgress(progress, null, container);
		}
	}

	private void trySendProgress(SimpleProgressMessage msg, FCPConnectionOutputHandler handler, ObjectContainer container) {
		if(progressPending != null) {
			container.activate(progressPending, 1);
			progressPending.removeFrom(container);
		}
		progressPending = msg;

		if(handler != null)
			handler.queue(msg);
		else {
			container.activate(client, 1);
			client.queueClientRequestMessage(msg, VERBOSITY_SPLITFILE_PROGRESS, container);
		}
	}

}
