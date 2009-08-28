package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.events.ClientEventProducer;
import freenet.client.events.SimpleEventProducer;

public class SendContext {

	public final ClientEventProducer eventProducer;
	private boolean hasOwnEventProducer;

	public SendContext(ClientEventProducer eventProducer) {
		this.eventProducer = eventProducer;
		hasOwnEventProducer = true;
	}

	public SendContext(SendContext ctx, boolean keepProducer) {
		if(keepProducer)
			eventProducer = ctx.eventProducer;
		else
			eventProducer = new SimpleEventProducer();
		hasOwnEventProducer = !keepProducer;
	}

	public void removeFrom(ObjectContainer container) {
		if(hasOwnEventProducer) {
			container.activate(eventProducer, 1);
			eventProducer.removeFrom(container);
		}
		container.delete(this);
	}

}
