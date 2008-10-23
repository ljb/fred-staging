package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * Splits a large persistent file into a series of buckets, which are collected 
 * into groups called segments to avoid huge transactions/memory usage.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SegmentedBucketChainBucket implements Bucket {

	private final ArrayList<SegmentedChainBucketSegment> segments;
	private boolean readOnly;
	public final long bucketSize;
	public final int segmentSize;
	private long size;
	private boolean freed;
	final BucketFactory bf;
	private transient DBJobRunner dbJobRunner;
	boolean stored;
	
	public SegmentedBucketChainBucket(int blockSize, BucketFactory factory, 
			DBJobRunner runner, int segmentSize2) {
		bucketSize = blockSize;
		bf = factory;
		dbJobRunner = runner;
		segmentSize = segmentSize2;
		segments = new ArrayList<SegmentedChainBucketSegment>();
	}

	public Bucket createShadow() throws IOException {
		return null;
	}

	public void free() {
		SegmentedChainBucketSegment[] segs;
		synchronized(this) {
			segs = segments.toArray(new SegmentedChainBucketSegment[segments.size()]);
			segments.clear();
		}
		for(SegmentedChainBucketSegment segment : segs)
			segment.free();
	}

	public InputStream getInputStream() throws IOException {
		synchronized(this) {
			if(freed) throw new IOException("Freed");
		}
		return new InputStream() {

			int segmentNo = -1;
			int bucketNo = segmentSize;
			SegmentedChainBucketSegment seg = null;
			Bucket[] buckets = null;
			InputStream is = null;
			private long bucketRead = 0;
			private boolean closed;
			
			@Override
			public int read() throws IOException {
				byte[] b = new byte[1];
				if(read(b, 0, 1) <= 0) return -1;
				return b[0];
			}
			
			@Override
			public int read(byte[] buf) throws IOException {
				return read(buf, 0, buf.length);
			}
			
			@Override
			public int read(byte[] buf, int offset, int length) throws IOException {
				if(closed) throw new IOException("Already closed");
				if(bucketRead == bucketSize || is == null) {
					if(is != null)
						is.close();
					if(buckets != null)
						buckets[bucketNo] = null;
					bucketRead = 0;
					bucketNo++;
					if(bucketNo == segmentSize) {
						bucketNo = 0;
						segmentNo++;
						seg = getSegment(segmentNo);
						if(seg == null) return -1;
						buckets = getBuckets(seg);
					}
					if(bucketNo >= buckets.length) {
						synchronized(SegmentedBucketChainBucket.this) {
							if(segmentNo >= segments.size())
								// No more data
								return -1;
						}
						buckets = getBuckets(seg);
						if(bucketNo >= buckets.length)
							return -1;
					}
					is = buckets[bucketNo].getInputStream();
				}
				int r = is.read(buf, offset, length);
				if(r > 0)
					bucketRead += r;
				return r;
			}
			
			@Override
			public void close() throws IOException {
				if(closed) return;
				if(is != null) is.close();
				closed = true;
				is = null;
				seg = null;
				buckets = null;
			}
			
		};
	}

	protected synchronized SegmentedChainBucketSegment getSegment(int i) {
		return segments.get(i);
	}

	protected Bucket[] getBuckets(final SegmentedChainBucketSegment seg) {
		final BucketArrayWrapper baw = new BucketArrayWrapper();
		dbJobRunner.runBlocking(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				container.activate(seg, 1);
				baw.buckets = seg.shallowCopyBuckets();
				container.deactivate(seg, 1);
			}
			
		}, NativeThread.HIGH_PRIORITY);
		return baw.buckets;
	}

	public String getName() {
		return "SegmentedBucketChainBucket";
	}

	public OutputStream getOutputStream() throws IOException {
		SegmentedChainBucketSegment[] segs;
		synchronized(this) {
			if(readOnly) throw new IOException("Read-only");
			if(freed) throw new IOException("Freed");
			size = 0;
			segs = segments.toArray(new SegmentedChainBucketSegment[segments.size()]);
			segments.clear();
		}
		for(int i=0;i<segs.length;i++) {
			segs[i].free();
		}
		return new OutputStream() {
			
			int segmentNo = 0;
			int bucketNo = 0;
			SegmentedChainBucketSegment seg = makeSegment(segmentNo, null);
			OutputStream cur = seg.makeBucketStream(bucketNo);
			private long bucketLength;
			private boolean closed;

			@Override
			public void write(int arg0) throws IOException {
				write(new byte[] { (byte)arg0 });
			}
			
			@Override
			public void write(byte[] buf) throws IOException {
				write(buf, 0, buf.length);
			}
			
			@Override
			public void write(byte[] buf, int offset, int length) throws IOException {
				boolean ro;
				synchronized(SegmentedBucketChainBucket.this) {
					ro = readOnly;
				}
				if(ro) {
					if(!closed) close();
					throw new IOException("Read-only");
				}
				if(closed) throw new IOException("Already closed");
				while(length > 0) {
					if(bucketLength == bucketSize) {
						bucketNo++;
						cur.close();
						if(bucketNo == segmentSize) {
							bucketNo = 0;
							segmentNo++;
							seg = makeSegment(segmentNo, seg);
						}
						cur = seg.makeBucketStream(bucketNo);
						bucketLength = 0;
					}
					int left = (int)Math.min(Integer.MAX_VALUE, bucketSize - bucketLength);
					int write = Math.min(left, length);
					cur.write(buf, offset, write);
					offset += write;
					length -= write;
					bucketLength += write;
					synchronized(SegmentedBucketChainBucket.class) {
						size += write;
					}
				}					
			}
			
			@Override
			public void close() throws IOException {
				if(closed) return;
				cur.close();
				closed = true;
				cur = null;
				final SegmentedChainBucketSegment oldSeg = seg;
				seg = null;
				dbJobRunner.runBlocking(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						oldSeg.storeTo(container);
						container.ext().store(segments, 1);
						container.ext().store(SegmentedBucketChainBucket.this, 1);
						container.deactivate(oldSeg, 1);
						dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
					}
					
				}, NativeThread.HIGH_PRIORITY);
			}
		};
	}

	private final DBJob killMe = new SegmentedBucketChainBucketKillJob(this);
	
	protected synchronized SegmentedChainBucketSegment makeSegment(int index, final SegmentedChainBucketSegment oldSeg) {
		if(oldSeg != null) {
			dbJobRunner.runBlocking(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					oldSeg.storeTo(container);
					container.ext().store(segments, 1);
					container.ext().store(SegmentedBucketChainBucket.this, 1);
					container.deactivate(oldSeg, 1);
					dbJobRunner.queueRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
				}
				
			}, NativeThread.NORM_PRIORITY);
		}
		SegmentedChainBucketSegment seg = new SegmentedChainBucketSegment(this);
		if(segments.size() != index) throw new IllegalArgumentException("Asked to add segment "+index+" but segments length is "+segments.size());
		segments.add(seg);
		return seg;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void removeFrom(ObjectContainer container) {
		// FIXME do something
	}

	public void setReadOnly() {
		readOnly = true;
	}

	public synchronized long size() {
		return size;
	}

	/**
	 * Note that we don't recurse inside the segments, as it would produce a huge
	 * transaction. So you will need to close the OutputStream to commit the 
	 * progress of writing to a file. And yes, we can't append. So you need to
	 * write everything before storing the bucket.
	 * 
	 * FIXME: Enforce the rule that you must close any OutputStream's before 
	 * calling storeTo().
	 */
	public void storeTo(ObjectContainer container) {
		stored = true;
		dbJobRunner.removeRestartJob(killMe, NativeThread.HIGH_PRIORITY, container);
		container.ext().store(segments, 1);
		container.ext().store(this, 1);
	}
	
	public Bucket[] getBuckets() {
		final BucketArrayWrapper baw = new BucketArrayWrapper();
		dbJobRunner.runBlocking(new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				baw.buckets = getBuckets(container);
			}
			
		}, NativeThread.HIGH_PRIORITY);
		return baw.buckets;
	}

	protected synchronized Bucket[] getBuckets(ObjectContainer container) {
		int segs = segments.size();
		if(segs == 0) return new Bucket[0];
		SegmentedChainBucketSegment seg = segments.get(segs-1);
		container.activate(seg, 1);
		seg.activateBuckets(container);
		int size = (segs - 1) * segmentSize + seg.size();
		Bucket[] buckets = new Bucket[size];
		seg.shallowCopyBuckets(buckets, (segs-1)*segmentSize);
		container.deactivate(seg, 1);
		int pos = 0;
		for(int i=0;i<(segs-1);i++) {
			seg = segments.get(i);
			container.activate(seg, 1);
			seg.activateBuckets(container);
			seg.shallowCopyBuckets(buckets, pos);
			container.deactivate(seg, 1);
			pos += segmentSize;
		}
		return buckets;
	}

	synchronized void clear() {
		segments.clear();
	}

}