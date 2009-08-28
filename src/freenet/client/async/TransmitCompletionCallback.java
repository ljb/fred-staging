package freenet.client.async;

import com.db4o.ObjectContainer;

public interface TransmitCompletionCallback {
	public void onSuccess(ObjectContainer container, ClientContext context);
	public void onFailure(ObjectContainer container, ClientContext context);
	public void onBlockFinished(ObjectContainer container, ClientContext context);
}
