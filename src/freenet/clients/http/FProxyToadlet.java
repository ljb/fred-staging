package freenet.clients.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.FoundURICallback;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.clients.http.filter.ContentFilter.FilterOutput;
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.TimeUtil;
import freenet.support.URIPreEncoder;
import freenet.support.URLEncoder;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

public final class FProxyToadlet extends Toadlet implements RequestClient {
	
	private static byte[] random;
	final NodeClientCore core;
	final ClientContext context;
	final FProxyFetchTracker fetchTracker;
	
	private static FoundURICallback prefetchHook;
	static final Set<String> prefetchAllowedTypes = new HashSet<String>();
	static {
		// Only valid inlines
		prefetchAllowedTypes.add("image/png");
		prefetchAllowedTypes.add("image/jpeg");
		prefetchAllowedTypes.add("image/gif");
	}
	
	// ?force= links become invalid after 2 hours.
	private static final long FORCE_GRAIN_INTERVAL = 60*60*1000;
	/** Maximum size for transparent pass-through, should be a config option */
	public static long MAX_LENGTH = (2*1024*1024 * 11) / 10; // 2MB plus a bit due to buggy inserts
	
	static final URI welcome;
	public static final short PRIORITY = RequestStarter.INTERACTIVE_PRIORITY_CLASS;
	static {
		try {
			welcome = new URI("/welcome/");
		} catch (URISyntaxException e) {
			throw new Error("Broken URI constructor: "+e, e);
		}
	}
	
	public FProxyToadlet(final HighLevelSimpleClient client, NodeClientCore core) {
		super(client);
		client.setMaxLength(MAX_LENGTH);
		client.setMaxIntermediateLength(MAX_LENGTH);
		this.core = core;
		this.context = core.clientContext;
		prefetchHook = new FoundURICallback() {

				public void foundURI(FreenetURI uri) {
					// Ignore
				}
				
				public void foundURI(FreenetURI uri, boolean inline) {
					if(!inline) return;
					if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Prefetching "+uri);
					client.prefetch(uri, 60*1000, 512*1024, prefetchAllowedTypes);
				}

				public void onText(String text, String type, URI baseURI) {
					// Ignore
				}
				
			};
		fetchTracker = new FProxyFetchTracker(context, getClientImpl().getFetchContext(), this);
	}

	public void handleMethodPOST(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String ks = uri.getPath();
		
		if (ks.equals("/")||ks.startsWith("/servlet/")) {
			try {
	            throw new RedirectException("/welcome/");
			} catch (URISyntaxException e) {
				// HUH!?!
			}
		}		
	}

	public static void handleDownload(ToadletContext context, Bucket data, BucketFactory bucketFactory, String mimeType, String requestedMimeType, String forceString, boolean forceDownload, String basePath, FreenetURI key, String extras, String referrer, boolean downloadLink, ToadletContext ctx, NodeClientCore core, boolean dontFreeData) throws ToadletContextClosedException, IOException {
		ToadletContainer container = context.getContainer();
		if(Logger.shouldLog(Logger.MINOR, FProxyToadlet.class))
			Logger.minor(FProxyToadlet.class, "handleDownload(data.size="+data.size()+", mimeType="+mimeType+", requestedMimeType="+requestedMimeType+", forceDownload="+forceDownload+", basePath="+basePath+", key="+key);
		String extrasNoMime = extras; // extras will not include MIME type to start with - REDFLAG maybe it should be an array
		if(requestedMimeType != null) {
			if(mimeType == null || !requestedMimeType.equals(mimeType)) {
				if(extras == null) extras = "";
				extras = extras + "&type=" + requestedMimeType;
			}
			mimeType = requestedMimeType;
		}
		long size = data.size();
		
		long now = System.currentTimeMillis();
		boolean force = false;
		if(forceString != null) {
			if(forceString.equals(getForceValue(key, now)) || 
					forceString.equals(getForceValue(key, now-FORCE_GRAIN_INTERVAL)))
				force = true;
		}

		Bucket toFree = null;
		Bucket tmpRange = null;
		try {
			if((!force) && (!forceDownload)) {
				FilterOutput fo = ContentFilter.filter(data, bucketFactory, mimeType, key.toURI(basePath), container.enableInlinePrefetch() ? prefetchHook : null);
				if(data != fo.data) toFree = fo.data;
				data = fo.data;
				mimeType = fo.type;
				
				if(horribleEvilHack(data) && !(mimeType.startsWith("application/rss+xml"))) {
					PageNode page = context.getPageMaker().getPageNode(l10n("dangerousRSSTitle"), context);
					HTMLNode pageNode = page.outer;
					HTMLNode contentNode = page.content;
					
					HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
					infobox.addChild("div", "class", "infobox-header", l10n("dangerousRSSSubtitle"));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					infoboxContent.addChild("#", NodeL10n.getBase().getString("FProxyToadlet.dangerousRSS", new String[] { "type" }, new String[] { mimeType }));
					infoboxContent.addChild("p", l10n("options"));
					HTMLNode optionList = infoboxContent.addChild("ul");
					HTMLNode option = optionList.addChild("li");
					
					NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openPossRSSAsPlainText", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+basePath+key.toString()+"?type=text/plain&force="+getForceValue(key,now)+extrasNoMime+"\">",
								"</a>",
								"<b>",
								"</b>" });
					// 	FIXME: is this safe? See bug #131
					option = optionList.addChild("li");
					NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openPossRSSForceDisk", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+basePath+key.toString()+"?forcedownload"+extras+"\">",
								"</a>",
								"<b>",
								"</b>" });
					boolean mimeRSS = mimeType.startsWith("application/xml+rss") || mimeType.startsWith("text/xml"); /* blergh! */
					if(!(mimeRSS || mimeType.startsWith("text/plain"))) {
						option = optionList.addChild("li");
						NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openRSSForce", new String[] { "link", "/link", "bold", "/bold", "mime" },
								new String[] { 
									"<a href=\""+basePath+key.toString()+"?force="+getForceValue(key, now)+extras+"\">",
									"</a>",
									"<b>",
									"</b>",
									HTMLEncoder.encode(mimeType) /* these are not encoded because mostly they are tags, so we have to encode it */ });
					}
					option = optionList.addChild("li");
					NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openRSSAsRSS", new String[] { "link", "/link", "bold", "/bold" },
							new String[] {
								"<a href=\""+basePath + key.toString() + "?type=application/xml+rss&force=" + getForceValue(key, now)+extrasNoMime+"\">",
								"</a>",
								"<b>",
								"</b>" });
					if(referrer != null) {
						option = optionList.addChild("li");
						NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.backToReferrer", new String[] { "link", "/link" },
								new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
					}
					option = optionList.addChild("li");
					NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.backToFProxy", new String[] { "link", "/link" },
							new String[] { "<a href=\"/\">", "</a>" });
					
					byte[] pageBytes = pageNode.generate().getBytes("UTF-8");
					context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), "text/html; charset=utf-8", pageBytes.length);
					context.writeData(pageBytes);
					return;
				}
			}
			
			if (forceDownload) {
				MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
				headers.put("Content-Disposition", "attachment; filename=\"" + key.getPreferredFilename() + '"');
				headers.put("Cache-Control", "private");
				headers.put("Content-Transfer-Encoding", "binary");
				// really the above should be enough, but ...
				// was application/x-msdownload, but some unix browsers offer to open that in Wine as default!
				// it is important that this type not be understandable, but application/octet-stream doesn't work.
				// see http://onjava.com/pub/a/onjava/excerpt/jebp_3/index3.html
				// Testing on FF3.5.1 shows that application/x-force-download wants to run it in wine, 
				// whereas application/force-download wants to save it.
				context.sendReplyHeaders(200, "OK", headers, "application/force-download", data.size());
				context.writeData(data);
			} else {
				// Send the data, intact
				MultiValueTable<String, String> hdr = context.getHeaders();
				String rangeStr = hdr.get("range");
				// was a range request
				if (rangeStr != null) {
					
					long range[] = parseRange(rangeStr);
					if (range[1] == -1 || range[1] >= data.size()) {
						range[1] = data.size() - 1;
					}
					tmpRange = bucketFactory.makeBucket(range[1] - range[0]);
					InputStream is = data.getInputStream();
					OutputStream os = tmpRange.getOutputStream();
					if (range[0] > 0)
						is.skip(range[0]);
					FileUtil.copy(is, os, range[1] - range[0] + 1);
					os.close();
					is.close();
					MultiValueTable<String, String> retHdr = new MultiValueTable<String, String>();
					retHdr.put("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + data.size());
					context.sendReplyHeaders(206, "Partial content", retHdr, mimeType, tmpRange.size());
					context.writeData(tmpRange);
				} else {
					context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), mimeType, data.size());
					context.writeData(data);
				}
			}
		} catch (URISyntaxException use1) {
			/* shouldn't happen */
			use1.printStackTrace();
			Logger.error(FProxyToadlet.class, "could not create URI", use1);
		} catch (UnsafeContentTypeException e) {
			PageNode page = context.getPageMaker().getPageNode(l10n("dangerousContentTitle"), context);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
			infobox.addChild("div", "class", "infobox-header", e.getRawTitle());
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			HTMLNode list = infoboxContent.addChild("ul");
			writeSizeAndMIME(list, size, mimeType, true);
			
			HTMLNode option = list.addChild("li");
			option.addChild("#", (l10n("filenameLabel") + ' '));
			option.addChild("a", "href", '/' + key.toString(), getFilename(key, mimeType));
			
			infoboxContent.addChild("p").addChild(e.getHTMLExplanation());
			infoboxContent.addChild("p", l10n("options"));
			HTMLNode optionList = infoboxContent.addChild("ul");
			
			if((mimeType.equals("application/x-freenet-index")) && (core.node.pluginManager.isPluginLoaded("plugins.ThawIndexBrowser.ThawIndexBrowser"))) {
				option = optionList.addChild("li");
				NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openAsThawIndex", new String[] { "link", "/link" }, new String[] { "<b><a href=\""+basePath + "plugins/plugins.ThawIndexBrowser.ThawIndexBrowser/?key=" + key.toString() + "\">", "</a></b>" });
			}
			
			option = optionList.addChild("li");
			// FIXME: is this safe? See bug #131
			NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openAsText", new String[] { "link", "/link" }, new String[] { "<a href=\""+basePath+key.toString()+"?type=text/plain"+extrasNoMime+"\">", "</a>" });

			option = optionList.addChild("li");
			NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openForceDisk", new String[] { "link", "/link" }, new String[] { "<a href=\""+basePath+key.toString()+"?forcedownload"+extras+"\">", "</a>" });
			if(!(mimeType.equals("application/octet-stream") || mimeType.equals("application/x-msdownload"))) {
				option = optionList.addChild("li");
				NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openForce", new String[] { "link", "/link", "mime" }, new String[] { "<a href=\""+basePath + key.toString() + "?force=" + getForceValue(key, now)+extras+"\">", "</a>", HTMLEncoder.encode(mimeType)});
			}
			if(referrer != null) {
				option = optionList.addChild("li");
				NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.backToReferrer", new String[] { "link", "/link" },
						new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
			}
			option = optionList.addChild("li");
			NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.backToFProxy", new String[] { "link", "/link" },
					new String[] { "<a href=\"/\">", "</a>" });
			if(ctx.isAllowedFullAccess() || !container.publicGatewayMode()) {
				option = optionList.addChild("li");
				PHYSICAL_THREAT_LEVEL threatLevel = core.node.securityLevels.getPhysicalThreatLevel();
				if(!(threatLevel == PHYSICAL_THREAT_LEVEL.HIGH || threatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM)) {
					HTMLNode optionForm = ctx.addFormChild(option, "/downloads/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mimeType });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDiskButton") });
					optionForm.addChild("#", " - ");
					NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToDisk", new String[] { "dir", "page", "/link" }, new String[] { HTMLEncoder.encode(core.getDownloadDir().getAbsolutePath()), "<a href=\"/downloads\">", "</a>" });
				}
				
				if(threatLevel != PHYSICAL_THREAT_LEVEL.LOW) {
					option = optionList.addChild("li");
					HTMLNode optionForm = ctx.addFormChild(option, "/downloads/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "direct" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mimeType });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToTempSpaceButton") });
					optionForm.addChild("#", " - ");
					NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToTempSpace", new String[] { "page", "/link" }, new String[] { "<a href=\"/downloads\">", "</a>" });
				}

			}
			

			byte[] pageBytes = pageNode.generate().getBytes("UTF-8");
			context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), "text/html; charset=utf-8", pageBytes.length);
			context.writeData(pageBytes);
		} catch (HTTPRangeException e) {
			ctx.sendReplyHeaders(416, "Requested Range Not Satisfiable", null, null, 0);
		} finally {
			if(toFree != null && !dontFreeData) toFree.free();
			if(tmpRange != null) tmpRange.free();
		}
	}
	
	private static String l10n(String msg) {
		return NodeL10n.getBase().getString("FProxyToadlet."+msg);
	}

	/** Does the first 512 bytes of the data contain anything that Firefox might regard as RSS?
	 * This is a horrible evil hack; we shouldn't be doing blacklisting, we should be doing whitelisting.
	 * REDFLAG Expect future security issues! 
	 * @throws IOException */
	private static boolean horribleEvilHack(Bucket data) throws IOException {
		InputStream is = null;
		try {
			int sz = (int) Math.min(data.size(), 512);
			if(sz == 0)
				return false;
			is = data.getInputStream();
			byte[] buf = new byte[sz];
			// FIXME Fortunately firefox doesn't detect RSS in UTF16 etc ... yet
			is.read(buf);
			/**
		 * Look for any of the following strings:
		 * <rss
		 * &lt;feed
		 * &lt;rdf:RDF
		 * 
		 * If they start at the beginning of the file, or are preceded by one or more &lt;! or &lt;? tags,
		 * then firefox will read it as RSS. In which case we must force it to be downloaded to disk. 
		 */
			if(checkForString(buf, "<rss"))
				return true;
			if(checkForString(buf, "<feed"))
				return true;
			if(checkForString(buf, "<rdf:RDF"))
				return true;
		}
		finally {
			Closer.close(is);
		}
		return false;
	}

	/** Scan for a US-ASCII (byte = char) string within a given buffer of possibly binary data */
	private static boolean checkForString(byte[] buf, String find) {
		int offset = 0;
		int bufProgress = 0;
		while(offset < buf.length) {
			byte b = buf[offset];
			if(b == find.charAt(bufProgress)) {
				bufProgress++;
				if(bufProgress == find.length()) return true;
			} else {
				bufProgress = 0;
				if(bufProgress != 0)
					continue; // check if this byte is equal to the first one
			}
			offset++;
		}
		return false;
	}

	public void handleMethodGET(URI uri, HTTPRequest httprequest, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {

		String ks = uri.getPath();
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if (ks.equals("/")) {
			if (httprequest.isParameterSet("key")) {
				String k = httprequest.getParam("key");
				FreenetURI newURI;
				try {
					newURI = new FreenetURI(k);
				} catch (MalformedURLException e) {
					Logger.normal(this, "Invalid key: "+e+" for "+k, e);
					sendErrorPage(ctx, 404, l10n("notFoundTitle"), NodeL10n.getBase().getString("FProxyToadlet.invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
					return;
				}
				
				if(logMINOR) Logger.minor(this, "Redirecting to FreenetURI: "+newURI);
				String requestedMimeType = httprequest.getParam("type");
				long maxSize = httprequest.getLongParam("max-size", MAX_LENGTH);
				String location = getLink(newURI, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"));
				writeTemporaryRedirect(ctx, null, location);
				return;
			}
			
			try {
				String querystring = uri.getQuery();
				
				if (querystring == null) {
					throw new RedirectException(welcome);
				} else {
					// TODP possibly a proper URLEncode method
					querystring = querystring.replace(' ', '+');
					throw new RedirectException("/welcome/?" + querystring);
				}
			} catch (URISyntaxException e) {
				// HUH!?!
			}
		}else if(ks.equals("/favicon.ico")){
			byte[] buf = new byte[1024];
			int len;
			InputStream strm = getClass().getResourceAsStream("staticfiles/favicon.ico");
			
			if (strm == null) {
				this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
				return;
			}
			ctx.sendReplyHeaders(200, "OK", null, "image/x-icon", strm.available());
			
			while ( (len = strm.read(buf)) > 0) {
				ctx.writeData(buf, 0, len);
			}
			strm.close();
			return;
		} else if(ks.startsWith("/feed/") || ks.equals("/feed")) {
			//TODO Better way to find the host. Find if https is used?
			String host = ctx.getHeaders().get("host");
			String atom = core.alerts.getAtom("http://" + host);
			byte[] buf = atom.getBytes("UTF-8");
			ctx.sendReplyHeaders(200, "OK", null, "application/atom+xml", buf.length);
			ctx.writeData(buf, 0, buf.length);
			return;
		}else if(ks.equals("/robots.txt") && ctx.doRobots()){
			this.writeTextReply(ctx, 200, "Ok", "User-agent: *\nDisallow: /");
			return;
		}else if(ks.startsWith("/darknet/") || ks.equals("/darknet")) { //TODO (pre-build 1045 url format) remove when obsolete
			writePermanentRedirect(ctx, "obsoleted", "/friends/");
			return;
		}else if(ks.startsWith("/opennet/") || ks.equals("/opennet")) { //TODO (pre-build 1045 url format) remove when obsolete
			writePermanentRedirect(ctx, "obsoleted", "/strangers/");
			return;
		} else if(ks.startsWith("/queue/")) {
			writePermanentRedirect(ctx, "obsoleted", "/downloads/");
			return;
		} else if(ks.startsWith("/config/")) {
			writePermanentRedirect(ctx, "obsoleted", "/config/node");
			return;
		}
		
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		
		long maxSize;
		
		boolean restricted = (container.publicGatewayMode() && !ctx.isAllowedFullAccess());
		
		if(restricted)
			maxSize = MAX_LENGTH;
		else 
			maxSize = httprequest.getLongParam("max-size", MAX_LENGTH);
		
		//first check of httprange before get
		// only valid number format is checked here
		String rangeStr = ctx.getHeaders().get("range");
		if (rangeStr != null) {
			try {
				parseRange(rangeStr);
			} catch (HTTPRangeException e) {
				Logger.normal(this, "Invalid Range Header: "+rangeStr, e);
				ctx.sendReplyHeaders(416, "Requested Range Not Satisfiable", null, null, 0);
				return;
			}
		}
		
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			PageNode page = ctx.getPageMaker().getPageNode(l10n("invalidKeyTitle"), ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode errorInfobox = contentNode.addChild("div", "class", "infobox infobox-error");
			errorInfobox.addChild("div", "class", "infobox-header", NodeL10n.getBase().getString("FProxyToadlet.invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
			HTMLNode errorContent = errorInfobox.addChild("div", "class", "infobox-content");
			errorContent.addChild("#", l10n("expectedKeyButGot"));
			errorContent.addChild("code", ks);
			errorContent.addChild("br");
			errorContent.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBack")));
			errorContent.addChild("br");
			addHomepageLink(errorContent);

			this.writeHTMLReply(ctx, 400, l10n("invalidKeyTitle"), pageNode.generate());
			return;
		}
		String requestedMimeType = httprequest.getParam("type", null);
		String override = (requestedMimeType == null) ? "" : "?type="+URLEncoder.encode(requestedMimeType,true);
		// No point passing ?force= across a redirect, since the key will change.
		// However, there is every point in passing ?forcedownload.
		if(httprequest.isParameterSet("forcedownload")) {
			if(override.length() == 0) override = "?forcedownload";
			else override = override+"&forcedownload";
		}

		Bucket data = null;
		String mimeType = null;
		String referer = sanitizeReferer(ctx);
		FetchException fe = null;
		

		MultiValueTable<String,String> headers = ctx.getHeaders();
		String ua = headers.get("user-agent");
		String accept = headers.get("accept");
		FProxyFetchResult fr = null;
		if(logMINOR) Logger.minor(this, "UA = "+ua+" accept = "+accept);
		if(isBrowser(ua) && !ctx.disableProgressPage() && (accept == null || accept.indexOf("text/html") > -1) && !httprequest.isParameterSet("forcedownload")) {
			FProxyFetchWaiter fetch = null;
			try {
				fetch = fetchTracker.makeFetcher(key, maxSize);
			} catch (FetchException e) {
				fe = fr.failed;
			}
			if(fetch != null)
			while(true) {
			fr = fetch.getResult();
			if(fr.hasData()) {
				if(logMINOR) Logger.minor(this, "Found data");
				data = fr.data;
				mimeType = fr.mimeType;
				fetch.close(); // Not waiting any more, but still locked the results until sent
				break;
			} else if(fr.failed != null) {
				if(logMINOR) Logger.minor(this, "Request failed");
				fe = fr.failed;
				fetch.close(); // Not waiting any more, but still locked the results until sent
				break;
			} else {
				if(logMINOR) Logger.minor(this, "Still in progress");
				// Still in progress
				boolean isJsEnabled=ctx.getContainer().isFProxyJavascriptEnabled() && ua != null && !ua.contains("AppleWebKit/");
				PageNode page = ctx.getPageMaker().getPageNode(l10n("fetchingPageTitle"), ctx);
				HTMLNode pageNode = page.outer;
				String location = getLink(key, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"));
				HTMLNode headNode=page.headNode;
				if(isJsEnabled){
					//If the user has enabled javascript, we add a <noscript> http refresh(if he has disabled it in the browser)
					//And the script file
					headNode.addChild("noscript").addChild("meta", "http-equiv", "Refresh").addAttribute("content", "2;URL=" + location);
					HTMLNode scriptNode=headNode.addChild("script","//abc");
					scriptNode.addAttribute("type", "text/javascript");
					scriptNode.addAttribute("src", "/static/js/progresspage.js");
				}else{
					//If he disabled it, we just put the http refresh meta, without the noscript
					headNode.addChild("meta", "http-equiv", "Refresh").addAttribute("content", "2;URL=" + location);
				}
				HTMLNode contentNode = page.content;
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("fetchingPageBox"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addAttribute("id", "infoContent");
				infoboxContent.addChild("#", l10n("filenameLabel")+ " ");
				infoboxContent.addChild("a", "href", "/"+key.toString(false, false), key.getPreferredFilename());
				if(fr.mimeType != null) infoboxContent.addChild("br", l10n("contentTypeLabel")+" "+fr.mimeType);
				if(fr.size > 0) infoboxContent.addChild("br", "Size: "+SizeUtil.formatSize(fr.size));
				if(core.isAdvancedModeEnabled()) {
					infoboxContent.addChild("br", l10n("blocksDetail", 
							new String[] { "fetched", "required", "total", "failed", "fatallyfailed" },
							new String[] { Integer.toString(fr.fetchedBlocks), Integer.toString(fr.requiredBlocks), Integer.toString(fr.totalBlocks), Integer.toString(fr.failedBlocks), Integer.toString(fr.fatallyFailedBlocks) }));
				}
				infoboxContent.addChild("br", l10n("timeElapsedLabel")+" "+TimeUtil.formatTime(System.currentTimeMillis() - fr.timeStarted));
				long eta = fr.eta;
				if(eta > 0)
					infoboxContent.addChild("br", "ETA: "+TimeUtil.formatTime(eta));
				if(fr.goneToNetwork)
					infoboxContent.addChild("p", l10n("progressDownloading"));
				else
					infoboxContent.addChild("p", l10n("progressCheckingStore"));
				if(!fr.finalizedBlocks)
					infoboxContent.addChild("p", l10n("progressNotFinalized"));
				
				HTMLNode table = infoboxContent.addChild("table", "border", "0");
				HTMLNode progressCell = table.addChild("tr").addChild("td", "class", "request-progress");
				if(fr.totalBlocks <= 0)
					progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.unknown"));
				else {
					int total = fr.requiredBlocks;
					int fetchedPercent = (int) (fr.fetchedBlocks / (double) total * 100);
					int failedPercent = (int) (fr.failedBlocks / (double) total * 100);
					int fatallyFailedPercent = (int) (fr.fatallyFailedBlocks / (double) total * 100);
					HTMLNode progressBar = progressCell.addChild("div", "class", "progressbar");
					progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });

					if (fr.failedBlocks > 0)
						progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
					if (fr.fatallyFailedBlocks > 0)
						progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });
					
					NumberFormat nf = NumberFormat.getInstance();
					nf.setMaximumFractionDigits(1);
					String prefix = '('+Integer.toString(fr.fetchedBlocks) + "/ " + Integer.toString(total)+"): ";
					if (fr.finalizedBlocks) {
						progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0) + '%');
					} else {
						String text = nf.format((int) ((fr.fetchedBlocks / (double) total) * 1000) / 10.0)+ '%';
						text = "" + fr.fetchedBlocks + " ("+text+"??)";
						progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarNotAccurate") }, text);
					}

				}
				
				infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("fetchingPageOptions"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");

				HTMLNode ul = infoboxContent.addChild("ul");
				ul.addChild("li").addChild("p", l10n("progressOptionZero"));
				
				PHYSICAL_THREAT_LEVEL threatLevel = core.node.securityLevels.getPhysicalThreatLevel();
				
				if(!(threatLevel == PHYSICAL_THREAT_LEVEL.HIGH || threatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM)) {
					HTMLNode optionForm = ctx.addFormChild(ul.addChild("li").addChild("p"), "/downloads/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDiskButton") });
					optionForm.addChild("#", " - ");
					NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToDisk", new String[] { "dir", "page", "/link" }, new String[] { HTMLEncoder.encode(core.getDownloadDir().getAbsolutePath()), "<a href=\"/downloads\">", "</a>" });
				}

				if(threatLevel != PHYSICAL_THREAT_LEVEL.LOW) {
					HTMLNode optionForm = ctx.addFormChild(ul.addChild("li").addChild("p"), "/downloads/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "direct" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToTempSpaceButton") });
					optionForm.addChild("#", " - ");
					NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToTempSpace", new String[] { "page", "/link" }, new String[] { "<a href=\"/downloads\">", "</a>" });

				}

				ul.addChild("li").addChild("p").addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
				ul.addChild("li").addChild("p").addChild("a", new String[] { "href", "title" }, new String[] { "/", NodeL10n.getBase().getString("Toadlet.homepage") }, l10n("abortToHomepage"));
				
				MultiValueTable<String, String> retHeaders = new MultiValueTable<String, String>();
				//retHeaders.put("Refresh", "2; url="+location);
				writeHTMLReply(ctx, 200, "OK", retHeaders, pageNode.generate());
				fr.close();
				fetch.close();
				return;
			}
			}
		}
		
		try {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "FProxy fetching "+key+" ("+maxSize+ ')');
			if(data == null && fe == null) {
			FetchResult result = fetch(key, maxSize, new RequestClient() {
				public boolean persistent() {
					return false;
				}
				public void removeFrom(ObjectContainer container) {
					throw new UnsupportedOperationException();
				} }); 
			
			// Now, is it safe?
			
			data = result.asBucket();
			mimeType = result.getMimeType();
			} else if(fe != null) throw fe;
			
			
			handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"), "/", key, maxSize != MAX_LENGTH ? "&max-size="+maxSize : "", referer, true, ctx, core, fr != null);
			
		} catch (FetchException e) {
			String msg = e.getMessage();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Failed to fetch "+uri+" : "+e);
			if(e.newURI != null) {
				Toadlet.writePermanentRedirect(ctx, msg,
					getLink(e.newURI, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload")));
			} else if(e.mode == FetchException.TOO_BIG) {
				PageNode page = ctx.getPageMaker().getPageNode(l10n("fileInformationTitle"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("largeFile"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", (l10n("filenameLabel") + ' '));
				option.addChild("a", "href", '/' + key.toString(), getFilename(key, e.getExpectedMimeType()));

				String mime = writeSizeAndMIME(fileInformationList, e);
				
				infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("explanationTitle"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("largeFileExplanationAndOptions"));
				HTMLNode optionTable = infoboxContent.addChild("table", "border", "0");
				if(!restricted) {
					option = optionTable.addChild("tr");
					HTMLNode optionForm = option.addChild("td").addChild("form", new String[] { "action", "method" }, new String[] {'/' + key.toString(), "get" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "max-size", String.valueOf(e.expectedSize == -1 ? Long.MAX_VALUE : e.expectedSize*2) });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "fetch", l10n("fetchLargeFileAnywayAndDisplayButton") });
					option.addChild("td", l10n("fetchLargeFileAnywayAndDisplay"));
					PHYSICAL_THREAT_LEVEL threatLevel = core.node.securityLevels.getPhysicalThreatLevel();
					if(!(threatLevel == PHYSICAL_THREAT_LEVEL.HIGH || threatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM)) {
						option = optionTable.addChild("tr");
						optionForm = ctx.addFormChild(option.addChild("td"), "/downloads/", "tooBigQueueForm");
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
						if (mime != null) {
							optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
						}
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDiskButton") });
						NodeL10n.getBase().addL10nSubstitution(optionForm.addChild("td"), "FProxyToadlet.downloadInBackgroundToDisk", new String[] { "dir", "page", "/link" }, new String[] { HTMLEncoder.encode(core.getDownloadDir().getAbsolutePath()), "<a href=\"/downloads\">", "</a>" });
					}
					
					if(threatLevel != PHYSICAL_THREAT_LEVEL.LOW) {
					
						option = optionTable.addChild("tr");
						
						optionForm = ctx.addFormChild(option.addChild("td"), "/downloads/", "tooBigQueueForm");
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "direct" });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
						if (mime != null) {
							optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
						}
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToTempSpaceButton") });
						NodeL10n.getBase().addL10nSubstitution(option.addChild("td"), "FProxyToadlet.downloadInBackgroundToTempSpace", new String[] { "page", "/link" }, new String[] { "<a href=\"/downloads\">", "</a>" });
					
					}
				}
				

				optionTable.addChild("tr").addChild("td", "colspan", "2").addChild("a", new String[] { "href", "title" }, new String[] { "/", NodeL10n.getBase().getString("Toadlet.homepage") }, l10n("abortToHomepage"));
				
				option = optionTable.addChild("tr").addChild("td", "colspan", "2");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			} else {
				PageNode page = ctx.getPageMaker().getPageNode(FetchException.getShortMessage(e.mode), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;

				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", l10n("errorWithReason", "error", FetchException.getShortMessage(e.mode)));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", (l10n("filenameLabel") + ' '));
				option.addChild("a", "href", '/' + key.toString(), getFilename(key, e.getExpectedMimeType()));

				String mime = writeSizeAndMIME(fileInformationList, e);
				infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", l10n("explanationTitle"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("p", l10n("unableToRetrieve"));
				if(e.isFatal())
					infoboxContent.addChild("p", l10n("errorIsFatal"));
				infoboxContent.addChild("p", msg);
				if(e.errorCodes != null) {
					infoboxContent.addChild("p").addChild("pre").addChild("#", e.errorCodes.toVerboseString());
				}

				infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", l10n("options"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				
				HTMLNode optionList = infoboxContent.addChild("ul");
				
				if((e.mode == FetchException.NOT_IN_ARCHIVE || e.mode == FetchException.NOT_ENOUGH_PATH_COMPONENTS) && (core.node.pluginManager.isPluginLoaded("plugins.KeyExplorer.KeyExplorer"))) {
					option = optionList.addChild("li");
					NodeL10n.getBase().addL10nSubstitution(option, "FProxyToadlet.openWithKeyExplorer", new String[] { "link", "/link" }, new String[] { "<a href=\"/plugins/plugins.KeyExplorer.KeyExplorer/?key=" + key.toString() + "\">", "</a>" });
				}
				
				if(!e.isFatal() && (ctx.isAllowedFullAccess() || !container.publicGatewayMode())) {
					PHYSICAL_THREAT_LEVEL threatLevel = core.node.securityLevels.getPhysicalThreatLevel();
					
					if(!(threatLevel == PHYSICAL_THREAT_LEVEL.HIGH || threatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM)) {
						option = optionList.addChild("li");
						HTMLNode optionForm = ctx.addFormChild(option, "/downloads/", "dnfQueueForm");
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
						if (mime != null) {
							optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
						}
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDiskButton")});
						optionForm.addChild("#", " - ");
						NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToDisk", new String[] { "dir", "page", "/link" }, new String[] { HTMLEncoder.encode(core.getDownloadDir().getAbsolutePath()), "<a href=\"/downloads\">", "</a>" });
					}
					
					if(threatLevel != PHYSICAL_THREAT_LEVEL.LOW) {
						
						option = optionList.addChild("li");
						HTMLNode optionForm = ctx.addFormChild(option, "/downloads/", "dnfQueueForm");
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "direct" });
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
						if (mime != null) {
							optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
						}
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToTempSpaceButton")});
						optionForm.addChild("#", " - ");
						NodeL10n.getBase().addL10nSubstitution(optionForm, "FProxyToadlet.downloadInBackgroundToTempSpace", new String[] { "page", "/link" }, new String[] { "<a href=\"/downloads\">", "</a>" });
						
					}
					
					optionList.addChild("li").
						addChild("a", "href", getLink(key, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"))).addChild("#", l10n("retryNow"));
				}
				
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", NodeL10n.getBase().getString("Toadlet.homepage") }, l10n("abortToHomepage"));
				
				option = optionList.addChild("li");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
				this.writeHTMLReply(ctx, (e.mode == 10) ? 404 : 500 /* close enough - FIXME probably should depend on status code */,
						"Internal Error", pageNode.generate());
			}
		} catch (SocketException e) {
			// Probably irrelevant
			if(e.getMessage().equals("Broken pipe")) {
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Caught "+e+" while handling GET", e);
			} else {
				Logger.normal(this, "Caught "+e);
			}
			throw e;
		} catch (Throwable t) {
			writeInternalError(t, ctx);
		} finally {
			if(fr == null && data != null) data.free();
			if(fr != null) fr.close();
		}
	}

	private static String getDownloadReturnType(Node node) {
		if(node.securityLevels.getPhysicalThreatLevel() != PHYSICAL_THREAT_LEVEL.LOW)
			// Default to save to temp space
			return "direct";
		else
			return "disk";
	}

	private boolean isBrowser(String ua) {
		if(ua == null) return false;
		if(ua.indexOf("Mozilla/") > -1) return true;
		if(ua.indexOf("Opera/") > -1) return true;
		return false;
	}

	private static String writeSizeAndMIME(HTMLNode fileInformationList, FetchException e) {
		boolean finalized = e.finalizedSize();
		String mime = e.getExpectedMimeType();
		long size = e.expectedSize;
		writeSizeAndMIME(fileInformationList, size, mime, finalized);
		return mime;
	}

	private static void writeSizeAndMIME(HTMLNode fileInformationList, long size, String mime, boolean finalized) {
		if(size > 0) {
			if (finalized) {
				fileInformationList.addChild("li", (l10n("sizeLabel") + ' ') + SizeUtil.formatSize(size));
			} else {
				fileInformationList.addChild("li", (l10n("sizeLabel") + ' ')+ SizeUtil.formatSize(size) + l10n("mayChange"));
			}
		} else {
			fileInformationList.addChild("li", l10n("sizeUnknown"));
		}
		if(mime != null) {
			fileInformationList.addChild("li", NodeL10n.getBase().getString("FProxyToadlet."+(finalized ? "mimeType" : "expectedMimeType"), new String[] { "mime" }, new String[] { mime }));
		} else {
			fileInformationList.addChild("li", l10n("unknownMIMEType"));
		}
	}
	
	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FProxyToadlet."+key, new String[] { pattern }, new String[] { value });
	}
	
	private String l10n(String key, String[] pattern, String[] value) {
		return NodeL10n.getBase().getString("FProxyToadlet."+key, pattern, value);
	}

	private String getLink(FreenetURI uri, String requestedMimeType, long maxSize, String force, 
			boolean forceDownload) {
		StringBuilder sb = new StringBuilder();
		sb.append("/");
		sb.append(uri.toASCIIString());
		char c = '?';
		if(requestedMimeType != null && requestedMimeType != "") {
			sb.append(c).append("type=").append(URLEncoder.encode(requestedMimeType,false)); c = '&';
		}
		if(maxSize > 0 && maxSize != MAX_LENGTH) {
			sb.append(c).append("max-size=").append(maxSize); c = '&';
		}
		if(force != null) {
			sb.append(c).append("force=").append(force); c = '&';
		}
		if(forceDownload) {
			sb.append(c).append("forcedownload=true"); c = '&';
		}
		return sb.toString();
	}

	private String sanitizeReferer(ToadletContext ctx) {
		// FIXME we do something similar in the GenericFilterCallback thingy?
		String referer = ctx.getHeaders().get("referer");
		if(referer != null) {
			try {
				URI refererURI = new URI(URIPreEncoder.encode(referer));
				String path = refererURI.getPath();
				while(path.startsWith("/")) path = path.substring(1);
				if("".equals(path)) return "/";
				FreenetURI furi = new FreenetURI(path);
				HTTPRequest req = new HTTPRequestImpl(refererURI, "GET");
				String type = req.getParam("type");
				referer = "/" + furi.toString();
				if(type != null && type.length() > 0)
					referer += "?type=" + type;
			} catch (MalformedURLException e) {
				referer = "/";
				Logger.normal(this, "Caught MalformedURLException on the referer : "+e.getMessage());
			} catch (Throwable t) {
				Logger.error(this, "Caught handling referrer: "+t+" for "+referer, t);
				referer = null;
			}
		}
		return referer;
	}

	private static String getForceValue(FreenetURI key, long time) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
		try{
			bos.write(random);
			bos.write(key.toString().getBytes("UTF-8"));
			bos.write(Long.toString(time / FORCE_GRAIN_INTERVAL).getBytes("UTF-8"));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		String f = HexUtil.bytesToHex(SHA256.digest(bos.toByteArray()));
		return f;
	}

	public static void maybeCreateFProxyEtc(NodeClientCore core, Node node, Config config, SimpleToadletServer server, BookmarkManager bookmarks) throws IOException {
		
		// FIXME how to change these on the fly when the interface language is changed?
		
		HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
		
		random = new byte[32];
		core.random.nextBytes(random);
		FProxyToadlet fproxy = new FProxyToadlet(client, core);
		core.setFProxy(fproxy);
		
		server.registerMenu("/", "FProxyToadlet.categoryBrowsing", "FProxyToadlet.categoryTitleBrowsing", null);
		server.registerMenu("/downloads/", "FProxyToadlet.categoryQueue", "FProxyToadlet.categoryTitleQueue", null);
		server.registerMenu("/friends/", "FProxyToadlet.categoryFriends", "FProxyToadlet.categoryTitleFriends", null);
		server.registerMenu("/chat/", "FProxyToadlet.categoryChat", "FProxyToadlet.categoryTitleChat", null);
		server.registerMenu("/alerts/", "FProxyToadlet.categoryStatus", "FProxyToadlet.categoryTitleStatus", null);
		server.registerMenu("/seclevels/", "FProxyToadlet.categoryConfig", "FProxyToadlet.categoryTitleConfig", null);
		
		
		server.register(fproxy, "FProxyToadlet.categoryBrowsing", "/", false, "FProxyToadlet.welcomeTitle", "FProxyToadlet.welcome", false, null);
		
		InsertFreesiteToadlet siteinsert = new InsertFreesiteToadlet(client, core.alerts);
		server.register(siteinsert, "FProxyToadlet.categoryBrowsing", "/insertsite/", true, "FProxyToadlet.insertFreesiteTitle", "FProxyToadlet.insertFreesite", false, null);
		
		UserAlertsToadlet alerts = new UserAlertsToadlet(client, node, core);
		server.register(alerts, "FProxyToadlet.categoryStatus", "/alerts/", true, "FProxyToadlet.alertsTitle", "FProxyToadlet.alerts", true, null);

		
		QueueToadlet downloadToadlet = new QueueToadlet(core, core.getFCPServer(), client, false);
		server.register(downloadToadlet, "FProxyToadlet.categoryQueue", "/downloads/", true, "FProxyToadlet.downloadsTitle", "FProxyToadlet.downloads", false, downloadToadlet);
		QueueToadlet uploadToadlet = new QueueToadlet(core, core.getFCPServer(), client, true);
		server.register(uploadToadlet, "FProxyToadlet.categoryQueue", "/uploads/", true, "FProxyToadlet.uploadsTitle", "FProxyToadlet.uploads", false, uploadToadlet);
		
		SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
		server.register(symlinkToadlet, null, "/sl/", true, false);
		
		SecurityLevelsToadlet seclevels = new SecurityLevelsToadlet(client, node, core);
		server.register(seclevels, "FProxyToadlet.categoryConfig", "/seclevels/", true, "FProxyToadlet.seclevelsTitle", "FProxyToadlet.seclevels", true, null);

		PproxyToadlet pproxy = new PproxyToadlet(client, node, core);
		server.register(pproxy, "FProxyToadlet.categoryConfig", "/plugins/", true, "FProxyToadlet.pluginsTitle", "FProxyToadlet.plugins", true, null);
		
		SubConfig[] sc = config.getConfigs();
		Arrays.sort(sc);
		
		for(SubConfig cfg : sc) {
			String prefix = cfg.getPrefix();
			if(prefix.equals("security-levels") || prefix.equals("pluginmanager")) continue;
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config, cfg, node, core);
			server.register(configtoadlet, "FProxyToadlet.categoryConfig", "/config/"+prefix, true, "ConfigToadlet."+prefix, "ConfigToadlet.title."+prefix, true, configtoadlet);
		}
		
		WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, core, node, bookmarks);
		server.register(welcometoadlet, null, "/welcome/", true, false);
		
		
		DarknetConnectionsToadlet friendsToadlet = new DarknetConnectionsToadlet(node, core, client);
		server.register(friendsToadlet, "FProxyToadlet.categoryFriends", "/friends/", true, "FProxyToadlet.friendsTitle", "FProxyToadlet.friends", true, null);
		
		DarknetAddRefToadlet addRefToadlet = new DarknetAddRefToadlet(node, core, client);
		server.register(addRefToadlet, "FProxyToadlet.categoryFriends", "/addfriend/", true, "FProxyToadlet.addFriendTitle", "FProxyToadlet.addFriend", true, null);
		
		OpennetConnectionsToadlet opennetToadlet = new OpennetConnectionsToadlet(node, core, client);
		server.register(opennetToadlet, "FProxyToadlet.categoryStatus", "/strangers/", true, "FProxyToadlet.opennetTitle", "FProxyToadlet.opennet", true, opennetToadlet);
		
		ChatForumsToadlet chatForumsToadlet = new ChatForumsToadlet(client, core.alerts, node.pluginManager, core.node);
		server.register(chatForumsToadlet, "FProxyToadlet.categoryChat", "/chat/", true, "FProxyToadlet.chatForumsTitle", "FProxyToadlet.chatForums", true, chatForumsToadlet);
		
		N2NTMToadlet n2ntmToadlet = new N2NTMToadlet(node, core, client);
		server.register(n2ntmToadlet, null, "/send_n2ntm/", true, true);
		LocalFileInsertToadlet localFileInsertToadlet = new LocalFileInsertToadlet(core, client);
		server.register(localFileInsertToadlet, null, "/files/", true, false);
		
		BookmarkEditorToadlet bookmarkEditorToadlet = new BookmarkEditorToadlet(client, core, bookmarks);
		server.register(bookmarkEditorToadlet, null, "/bookmarkEditor/", true, false);
		
		BrowserTestToadlet browsertTestToadlet = new BrowserTestToadlet(client, core);
		server.register(browsertTestToadlet, null, "/test/", true, false);
			
		StatisticsToadlet statisticsToadlet = new StatisticsToadlet(node, core, client);
		server.register(statisticsToadlet, "FProxyToadlet.categoryStatus", "/stats/", true, "FProxyToadlet.statsTitle", "FProxyToadlet.stats", true, null);
		
		ConnectivityToadlet connectivityToadlet = new ConnectivityToadlet(client, node, core);
		server.register(connectivityToadlet, "FProxyToadlet.categoryStatus", "/connectivity/", true, "ConnectivityToadlet.connectivityTitle", "ConnectivityToadlet.connectivity", true, null);
		
		TranslationToadlet translationToadlet = new TranslationToadlet(client, core);
		server.register(translationToadlet, "FProxyToadlet.categoryConfig", TranslationToadlet.TOADLET_URL, true, "TranslationToadlet.title", "TranslationToadlet.titleLong", true, null);
		
		FirstTimeWizardToadlet firstTimeWizardToadlet = new FirstTimeWizardToadlet(client, node, core);
		server.register(firstTimeWizardToadlet, null, FirstTimeWizardToadlet.TOADLET_URL, true, false);
		
		SimpleHelpToadlet simpleHelpToadlet = new SimpleHelpToadlet(client, core);
		server.register(simpleHelpToadlet, null, "/help/", true, false);
		
	}
	
	/**
	 * Get expected filename for a file.
	 * @param e The FetchException.
	 * @param uri The original URI.
	 * @param expectedMimeType The expected MIME type.
	 */
	private static String getFilename(FreenetURI uri, String expectedMimeType) {
		String s = uri.getPreferredFilename();
		int dotIdx = s.lastIndexOf('.');
		String ext = DefaultMIMETypes.getExtension(expectedMimeType);
		if(ext == null)
			ext = "bin";
		if((dotIdx == -1) && (expectedMimeType != null)) {
			s += '.' + ext;
			return s;
		}
		if(dotIdx != -1) {
			String oldExt = s.substring(dotIdx+1);
			if(DefaultMIMETypes.isValidExt(expectedMimeType, oldExt))
				return s;
			return s + '.' + ext;
		}
		return s + '.' + ext;
	}
	
	private static long[] parseRange(String hdrrange) throws HTTPRangeException {
		
		long result[] = new long[2];
		try {
			String[] units = hdrrange.split("=", 2);
			// FIXME are MBytes and co valid? if so, we need to adjust the values and
			// return always bytes
			if (!"bytes".equals(units[0])) {
				throw new HTTPRangeException("Unknown unit, only 'bytes' supportet yet");
			}
			String[] range = units[1].split("-", 2);
			result[0] = Long.parseLong(range[0]);
			if (result[0] < 0)
				throw new HTTPRangeException("Negative 'from' value");
			if (range[1].trim().length() > 0) {
				result[1] = Long.parseLong(range[1]);
				if (result[1] <= result[0])
					throw new HTTPRangeException("'from' value must be less then 'to' value");
			} else {
				result[1] = -1;
			}
		} catch (NumberFormatException nfe) {
			throw new HTTPRangeException(nfe);
		} catch (IndexOutOfBoundsException ioobe) {
			throw new HTTPRangeException(ioobe);
		}
		return result;
	}

	public boolean persistent() {
		return false;
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String path() {
		return "/";
	}
	
}
