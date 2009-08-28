package freenet.node.fcp;

import java.io.File;
import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.SendContext;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.ClientSendReceiveCallback;
import freenet.client.async.ClientSender;
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

public class ClientSend extends ClientRequest implements ClientSendReceiveCallback, ClientEventListener {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetter.class);
	}

	/** Send context. Never passed in: always created new by the ClientSend. Therefore, we
	 * can safely delete it in requestWasRemoved(). */
	private final SendContext ctx;
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Has the friend accepted the file offer */
	private boolean accepted;
	/** Used to get the current name of the peer. Not stored in the database */
	//TODO Use a weak reference
	private transient DarknetPeerNode peer;
	/** Used to find the peer */
	private final byte[] nodeIdentity;
	private final long uid;
	private final File file;
	private final ClientSender sender;
	private SimpleProgressMessage progressPending;
	private String failureReason;
	private final int verbosity;

	public ClientSend(FCPClient globalClient, String identifier, String clientToken, DarknetPeerNode peer, int verbosity, File file, long uid, FCPServer server, ObjectContainer container) {
		super(null, identifier, verbosity, null, globalClient, (short) 4, ClientRequest.PERSIST_FOREVER, clientToken, true, container);
		ctx = new SendContext(server.defaultSendContext, false);
		ctx.eventProducer.addEventListener(this);
		this.file = file;
		this.peer = peer;
		this.uid = uid;
		this.verbosity = verbosity;

		//FIXME File might not be readable/not found and length will return 0
		peer.sendFileOffer(file, "", uid, file.length());

		started = false;
		nodeIdentity = peer.getIdentity();
		sender = new ClientSender(this, ctx, priorityClass, file, peer, uid, lowLevelClient);
	}

	public ClientSend(FCPConnectionHandler handler, ClientSendMessage message, DarknetPeerNode peer, File file, long uid, FCPServer server, ObjectContainer container) throws IdentifierCollisionException, MessageInvalidException, IOException, DisconnectedException {
		super(null, message.identifier, 0, handler, (short) 4, ClientRequest.PERSIST_FOREVER, message.clientToken, message.global, container);
		ctx = new SendContext(server.defaultSendContext, false);
		ctx.eventProducer.addEventListener(this);
		this.file = file;
		this.peer = peer;
		this.uid = uid;
		this.verbosity = message.verbosity;

		//FIXME File might not be readable/not found and length will return 0
		peer.sendFileOffer(file, "", uid, file.length());

		started = false;
		nodeIdentity = peer.getIdentity();
		sender = new ClientSender(this, ctx, priorityClass, file, peer, uid, lowLevelClient);
	}

	public void accept() {
		accepted = true;
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
		return sender;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
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

	@Override
	public synchronized boolean hasSucceeded() {
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
		container.activate(file, 5);
		return new PersistentSend(identifier, verbosity, global, started, file, uid);
	}

	@Override
	public boolean restart(ObjectContainer container, ClientContext context) {
		try {
			sender.restart(container, context);
			return true;
		} catch (UnknownNodeIdentityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DisconnectedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
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
		if(!accepted) {
			Logger.error(this, "Won't start since our offer hasn't been accept");
			return;
		}

		if(logMINOR)
			Logger.minor(this, "Starting " + this + " : " + identifier);
		synchronized(this) {
			if(finished)
				return;
			started = true;
		}
		try {
			sender.start(container, context);
		} catch (UnknownNodeIdentityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			onFailure(container, context);
		} catch (DisconnectedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			onFailure(container, context);
		} catch (IOException e) {
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

	public void onFailure(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished)
				return;
			finished = true;
			succeeded = false;
			started = true;
		}
		finish(container);
		container.activate(client, 1);
		if(client != null)
			client.notifyFailure(this, container);
		container.store(this);
	}

	public void onSuccess(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Succeeded: " + identifier);
		synchronized(this) {
			finished = true;
			succeeded = true;
		}
		finish(container);
		container.activate(client, 1);
		if(client != null)
			client.notifySuccess(this, container);
		container.store(this);
	}

	public File getOrigFilename(ObjectContainer container) {
		container.activate(file, 5);
		return file;
	}

	public long getSize(ObjectContainer container) {
		container.activate(file, 5);
		long size = file.length();
		container.deactivate(file, 5);
		return size;
	}

	public String getMIMEType(ObjectContainer container) {
		container.activate(file, 5);
		String mime = DefaultMIMETypes.guessMIMEType(file.getName(), false);
		container.deactivate(file, 5);
		return mime;
	}

	public String getRecipient(ObjectContainer container, ClientContext context) {
		if(peer == null) {
			container.activate(nodeIdentity, 2);
			peer = (DarknetPeerNode) context.peers.getByIdentity(nodeIdentity);
		}
		if(peer == null || !(peer instanceof DarknetPeerNode))
			try {
				throw new UnknownNodeIdentityException(nodeIdentity);
			} catch (UnknownNodeIdentityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		return peer.getName();
	}

	@Override
	public void register(ObjectContainer container, boolean noTags)
			throws IdentifierCollisionException {
		if(client != null)
			assert(this.persistenceType == client.persistenceType);
			try {
				client.register(this, container);
			} catch (IdentifierCollisionException e) {
				throw e;
			}
	}

	@Override
	public String getFailureReason(ObjectContainer container) {
		container.activate(failureReason, 1);
		return failureReason;
	}

	public void rejected() {
		if(accepted)
			return;
		synchronized(this) {
			started = true;
			succeeded = false;
			finished = true;
			//TODO Localize
			failureReason = "Rejected by friend";
		}
	}

	@Override
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		container.activate(nodeIdentity, 1);
		container.delete(nodeIdentity);
		container.activate(file, 1);
		container.delete(file);
		sender.removeFrom(container, context);
		container.activate(ctx, 1);
		ctx.removeFrom(container);
		super.requestWasRemoved(container, context);
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
			client.queueClientRequestMessage(msg, 1, container);
		}
	}

}
