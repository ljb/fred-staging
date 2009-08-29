/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.events.SendingToNetworkEvent;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.BaseClientKey;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/** A high level insert. */
public class ClientPutter extends BaseClientPutter implements PutCompletionCallback {

	/** Callback for when the insert completes. */
	final ClientPutCallback client;
	/** The data to insert. */
	final Bucket data;
	/** The URI to insert it to. Can be CHK@. */
	final FreenetURI targetURI;
	/** The ClientMetadata i.e. the MIME type and any other client-visible metadata. */
	final ClientMetadata cm;
	/** Config settings for this insert - what kind of splitfile to use if needed etc. */
	final InsertContext ctx;
	/** Target filename. If specified, we create manifest metadata so that the file can be accessed at
	 * [ final key ] / [ target filename ]. */
	final String targetFilename;
	/** The current state of the insert. */
	private ClientPutState currentState;
	/** Whether the insert has finished. */
	private boolean finished;
	/** If true, don't actually insert the data, just figure out what the final key would be. */
	private final boolean getCHKOnly;
	/** Are we inserting metadata? */
	private final boolean isMetadata;
	private boolean startedStarting;
	/** Are we inserting a binary blob? */
	private final boolean binaryBlob;
	/** The final URI for the data. */
	private FreenetURI uri;
	/** SimpleFieldSet containing progress information from last startup.
	 * Will be progressively cleared during startup. */
	private SimpleFieldSet oldProgress;

	@Deprecated
	public ClientPutter(ClientCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InsertContext ctx,
			short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, RequestClient clientContext, SimpleFieldSet stored, String targetFilename, boolean binaryBlob) {
		this((ClientPutCallback) client, data, targetURI, cm, ctx, priorityClass, getCHKOnly, isMetadata, clientContext, stored, targetFilename, binaryBlob);
	}

	/**
	 * @param client The object to call back when we complete, or don't.
	 * @param data The data to insert. This will not be freed by ClientPutter, the callback must do that. However,
	 * buckets used internally by the client layer will be freed.
	 * @param targetURI
	 * @param cm
	 * @param ctx
	 * @param scheduler
	 * @param priorityClass
	 * @param getCHKOnly
	 * @param isMetadata
	 * @param clientContext The client object for purpose of round-robin client balancing.
	 * @param stored The progress so far, stored as a SimpleFieldSet. Advisory; if there 
	 * is an error reading this in, we will restart from scratch.
	 * @param targetFilename If set, create a one-file manifest containing this filename pointing to this file.
	 */
	public ClientPutter(ClientPutCallback client, Bucket data, FreenetURI targetURI, ClientMetadata cm, InsertContext ctx,
			short priorityClass, boolean getCHKOnly, 
			boolean isMetadata, RequestClient clientContext, SimpleFieldSet stored, String targetFilename, boolean binaryBlob) {
		super(priorityClass, clientContext);
		this.cm = cm;
		this.isMetadata = isMetadata;
		this.getCHKOnly = getCHKOnly;
		this.client = client;
		this.data = data;
		this.targetURI = targetURI;
		this.ctx = ctx;
		this.finished = false;
		this.cancelled = false;
		this.oldProgress = stored;
		this.targetFilename = targetFilename;
		this.binaryBlob = binaryBlob;
	}

	/** Start the insert.
	 * @param earlyEncode If true, try to find the final URI as quickly as possible, and insert the upper
	 * layers as soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
	 * because an attacker can usually only identify the datastream once he has the top block, or once you
	 * have announced the key.
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be 
	 * running on the database thread. This is true for all methods taking a container parameter.
	 * @param context Contains some useful transient fields such as the schedulers.
	 * @throws InsertException If the insert cannot be started for some reason.
	 */
	public void start(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		start(earlyEncode, false, container, context);
	}
	
	/** Start the insert.
	 * @param earlyEncode If true, try to find the final URI as quickly as possible, and insert the upper
	 * layers as soon as we can, rather than waiting for the lower layers. The default behaviour is safer,
	 * because an attacker can usually only identify the datastream once he has the top block, or once you
	 * have announced the key.
	 * @param restart If true, restart the insert even though it has completed before.
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be 
	 * running on the database thread. This is true for all methods taking a container parameter.
	 * @param context Contains some useful transient fields such as the schedulers.
	 * @throws InsertException If the insert cannot be started for some reason.
	 */
	public boolean start(boolean earlyEncode, boolean restart, ObjectContainer container, ClientContext context) throws InsertException {
		if(persistent())
			container.activate(client, 1);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Starting "+this);
		try {
			this.targetURI.checkInsertURI();
			
			if(data == null)
				throw new InsertException(InsertException.BUCKET_ERROR, "No data to insert", null);
			
			boolean cancel = false;
			synchronized(this) {
				if(restart) {
					if(currentState != null && !finished) return false;
					finished = false;
				}
				if(startedStarting) return false;
				startedStarting = true;
				if(currentState != null) return false;
				cancel = this.cancelled;
				if(!cancel) {
					if(!binaryBlob) {
						ClientMetadata meta = cm;
						if(meta != null) meta = persistent() ? meta.clone() : meta; 
						currentState =
							new SingleFileInserter(this, this, new InsertBlock(data, meta, persistent() ? targetURI.clone() : targetURI), isMetadata, ctx, 
									false, getCHKOnly, false, null, null, false, targetFilename, earlyEncode);
					} else
						currentState =
							new BinaryBlobInserter(data, this, null, false, priorityClass, ctx, context, container);
				}
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				oldProgress = null;
				return false;
			}
			synchronized(this) {
				cancel = cancelled;
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				oldProgress = null;
				if(persistent())
					container.store(this);
				return false;
			}
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Starting insert: "+currentState);
			if(currentState instanceof SingleFileInserter)
				((SingleFileInserter)currentState).start(oldProgress, container, context);
			else
				currentState.schedule(container, context);
			synchronized(this) {
				oldProgress = null;
				cancel = cancelled;
			}
			if(persistent()) {
				container.store(this);
				// It has scheduled, we can safely deactivate it now, so it won't hang around in memory.
				container.deactivate(currentState, 1);
			}
			if(cancel) {
				onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
				return false;
			}
		} catch (InsertException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(e, this, container);
			}
		} catch (IOException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), this, container);
			}
		} catch (BinaryBlobFormatException e) {
			Logger.error(this, "Failed to start insert: "+e, e);
			synchronized(this) {
				finished = true;
				oldProgress = null;
				currentState = null;
			}
			if(persistent())
				container.store(this);
			// notify the client that the insert could not even be started
			if (this.client!=null) {
				this.client.onFailure(new InsertException(InsertException.BINARY_BLOB_FORMAT_ERROR, e, null), this, container);
			}
		} 
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Started "+this);
		return true;
	}

	/** Called when the insert succeeds. */
	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		ClientPutState oldState;
		synchronized(this) {
			finished = true;
			oldState = currentState;
			currentState = null;
			oldProgress = null;
		}
		if(oldState != null && persistent()) {
			container.activate(oldState, 1);
			oldState.removeFrom(container, context);
		}
		if(state != null && state != oldState && persistent())
			state.removeFrom(container, context);
		if(super.failedBlocks > 0 || super.fatallyFailedBlocks > 0 || super.successfulBlocks < super.totalBlocks) {
			Logger.error(this, "Failed blocks: "+failedBlocks+", Fatally failed blocks: "+fatallyFailedBlocks+
					", Successful blocks: "+successfulBlocks+", Total blocks: "+totalBlocks+" but success?! on "+this+" from "+state,
					new Exception("debug"));
		}
		if(persistent())
			container.store(this);
		client.onSuccess(this, container);
	}

	/** Called when the insert fails. */
	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "onFailure() for "+this+" : "+state+" : "+e, e);
		if(persistent())
			container.activate(client, 1);
		ClientPutState oldState;
		synchronized(this) {
			finished = true;
			oldState = currentState;
			currentState = null;
			oldProgress = null;
		}
		if(oldState != null && persistent()) {
			container.activate(oldState, 1);
			oldState.removeFrom(container, context);
		}
		if(state != null && state != oldState && persistent())
			state.removeFrom(container, context);
		if(persistent())
			container.store(this);
		client.onFailure(e, this, container);
	}

	/** Called when significant milestones are passed. */
	@Override
	public void onMajorProgress(ObjectContainer container) {
		if(persistent())
			container.activate(client, 1);
		client.onMajorProgress(container);
	}
	
	/** Called when we know the final URI of the insert. */
	public void onEncode(BaseClientKey key, ClientPutState state, ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(client, 1);
		synchronized(this) {
			if(this.uri != null) {
				Logger.error(this, "onEncode() called twice? Already have a uri: "+uri+" for "+this);
				if(persistent())
					this.uri.removeFrom(container);
			}
			this.uri = key.getURI();
			if(targetFilename != null)
				uri = uri.pushMetaString(targetFilename);
		}
		if(persistent())
			container.store(this);
		client.onGeneratedURI(uri, this, container);
	}

	/** Cancel the insert. Will call onFailure() if it is not already cancelled, so the callback will 
	 * normally be called. */
	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Cancelling "+this, new Exception("debug"));
		ClientPutState oldState = null;
		synchronized(this) {
			if(cancelled) return;
			if(finished) return;
			super.cancel();
			oldState = currentState;
		}
		if(persistent()) {
			container.store(this);
			if(oldState != null)
				container.activate(oldState, 1);
		}
		if(oldState != null) oldState.cancel(container, context);
		onFailure(new InsertException(InsertException.CANCELLED), null, container, context);
	}
	
	/** Has the insert completed already? */
	@Override
	public synchronized boolean isFinished() {
		return finished || cancelled;
	}

	/** Get the final URI to the inserted data */
	@Override
	public FreenetURI getURI() {
		return uri;
	}

	/** Called when a ClientPutState transitions to a new state. If this is the current state, then we update
	 * it, but it might also be a subsidiary state, in which case we ignore it. */
	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		if(newState == null) throw new NullPointerException();
		
		// onTransition is *not* responsible for removing the old state, the caller is.
		synchronized (this) {
			if (currentState == oldState) {
				currentState = newState;
				if(persistent())
					container.store(this);
				return;
			}
		}
		Logger.error(this, "onTransition: cur=" + currentState + ", old=" + oldState + ", new=" + newState);
	}

	/** Called when we have generated metadata for the insert. This should not happen, because we should
	 * insert the metadata! */
	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Got metadata on "+this+" from "+state+" (this means the metadata won't be inserted)");
	}
	
	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		if(persistent())
			container.activate(ctx, 2);
		ctx.eventProducer.produceEvent(new SplitfileProgressEvent(this.totalBlocks, this.successfulBlocks, this.failedBlocks, this.fatallyFailedBlocks, this.minSuccessBlocks, this.blockSetFinalized), container, context);
	}
	
	/** Notify listening clients that an insert has been sent to the network. */
	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		if(persistent()) {
			container.activate(ctx, 1);
			container.activate(ctx.eventProducer, 1);
		}
		ctx.eventProducer.produceEvent(new SendingToNetworkEvent(), container, context);
	}

	/** Called when we know exactly how many blocks will be needed. */
	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Set finished", new Exception("debug"));
		blockSetFinalized(container, context);
	}

	/** Called (sometimes) when enough of the data has been inserted that the file can now be fetched. Not
	 * very useful unless earlyEncode was enabled. */
	public void onFetchable(ClientPutState state, ObjectContainer container) {
		if(persistent())
			container.activate(client, 1);
		client.onFetchable(this, container);
	}

	/** Can we restart the insert? */
	public boolean canRestart() {
		if(currentState != null && !finished) {
			Logger.minor(this, "Cannot restart because not finished for "+uri);
			return false;
		}
		if(data == null) return false;
		return true;
	}

	/** Restart the insert. 
	 * @param earlyEncode See the description on @link start().
	 * @param container The database. If the insert is persistent, this must be non-null, and we must be 
	 * running on the database thread. This is true for all places where we pass in an ObjectContainer.
	 * @return True if the insert restarted successfully.
	 * @throws InsertException If the insert could not be restarted for some reason.
	 * */
	public boolean restart(boolean earlyEncode, ObjectContainer container, ClientContext context) throws InsertException {
		return start(earlyEncode, true, container, context);
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore, at the moment
	}

	/** Remove the ClientPutter from the database. */
	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.activate(cm, 2);
		cm.removeFrom(container);
		// This is passed in. We should not remove it, because the caller (ClientPutBase) should remove it.
//		container.activate(ctx, 1);
//		ctx.removeFrom(container);
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		if(uri != null) {
			container.activate(uri, 5);
			uri.removeFrom(container);
		}
		super.removeFrom(container, context);
	}
}
