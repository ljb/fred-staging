package freenet.node;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.client.events.EventDumper;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.support.Logger;

public class TextModeClientInterfaceServer implements Runnable {

    final RandomSource r;
    final Node n;
    final HighLevelSimpleClient client;
    final Hashtable streams;
    final File downloadsDir;
    int port;
    final String bindto;
    boolean isEnabled;

    TextModeClientInterfaceServer(Node n, HighLevelSimpleClient client, int port, String bindto) {
        this.n = n;
        this.client = client;
        client.addGlobalHook(new EventDumper());
        this.r = n.random;
        streams = new Hashtable();
        this.downloadsDir = n.downloadDir;
        this.port=port;
        this.bindto=bindto;
        this.isEnabled=true;
        n.setTMCI(this);
        new Thread(this, "Text mode client interface").start();
    }
    
	public static void maybeCreate(Node node, Config config) throws IOException {
		SubConfig TMCIConfig = new SubConfig("tmci", config);
		
		TMCIConfig.register("enabled", true, 1, true, "Enable TMCI", "Whether to enable the TMCI",
				new TMCIEnabledCallback(node));
		TMCIConfig.register("bindto", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind to",
				new TMCIBindtoCallback(node));
		TMCIConfig.register("port", 2323, 1, true, "Testnet port", "Testnet port number",
        		new TCMIPortNumberCallback(node));
		TMCIConfig.register("directEnabled", false, 1, true, "Enable on stdout/stdin?", "Enable text mode client interface on standard input/output? (.enabled refers to providing a telnet-style server, this runs it over a socket)",
				new TMCIDirectEnabledCallback(node));
		
		boolean TMCIEnabled = TMCIConfig.getBoolean("enabled");
		int port =  TMCIConfig.getInt("port");
		String bind_ip = TMCIConfig.getString("bindto");
		boolean direct = TMCIConfig.getBoolean("directEnabled");
        HighLevelSimpleClient client = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);

		if(TMCIEnabled){
			new TextModeClientInterfaceServer(node, client, port, bind_ip);
			Logger.normal(node, "TMCI started on "+bind_ip+":"+port);
			System.out.println("TMCI started on "+bind_ip+":"+port);
		}
		else{
			Logger.normal(node, "Not starting TMCI as it's disabled");
		}
		
		if(direct) {
			TextModeClientInterface directTMCI =
				new TextModeClientInterface(node, client, node.downloadDir, System.in, System.out);
			Thread t = new Thread(directTMCI, "Direct text mode interface");
			t.setDaemon(true);
			t.start();
			node.setDirectTMCI(directTMCI);
		}
		
		TMCIConfig.finishedInitialization();
	}

    
    static class TMCIEnabledCallback implements BooleanCallback {
    	
    	final Node node;
    	
    	TMCIEnabledCallback(Node n) {
    		this.node = n;
    	}
    	
    	public boolean get() {
    		return node.getTextModeClientInterface() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }

    static class TMCIDirectEnabledCallback implements BooleanCallback {
    	
    	final Node node;
    	
    	TMCIDirectEnabledCallback(Node n) {
    		this.node = n;
    	}
    	
    	public boolean get() {
    		return node.getDirectTMCI() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }
    
    static class TMCIBindtoCallback implements StringCallback {
    	
    	final Node node;
    	
    	TMCIBindtoCallback(Node n) {
    		this.node = n;
    	}
    	
    	public String get() {
    		if(node.getTextModeClientInterface()!=null)
    			return node.getTextModeClientInterface().bindto;
    		else
    			return "127.0.0.1";
    	}
    	
    	public void set(String val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }

    static class TCMIPortNumberCallback implements IntCallback{
    	
    	final Node node;
    	
    	TCMIPortNumberCallback(Node n) {
    		this.node = n;
    	}
    	
    	public int get() {
    		if(node.getTextModeClientInterface()!=null)
    			return node.getTextModeClientInterface().port;
    		else
    			return 2323;
    	}
    	
    	// TODO: implement it
    	public void set(int val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		node.getTextModeClientInterface().setPort(val);
    	}
    }

    /**
     * Read commands, run them
     */
    public void run() {
    	while(true) {
    		int curPort = port;
    		String bindTo = this.bindto;
    		ServerSocket server;
    		try {
    			server = new ServerSocket(curPort, 0, InetAddress.getByName(bindTo));
    		} catch (IOException e) {
    			Logger.error(this, "Could not bind to TMCI port: "+bindto+":"+port);
    			System.exit(-1);
    			return;
    		}
    		try {
    			server.setSoTimeout(1000);
    		} catch (SocketException e1) {
    			Logger.error(this, "Could not set timeout: "+e1, e1);
    			System.err.println("Could not start TMCI: "+e1);
    			e1.printStackTrace();
    			return;
    		}
    		while(isEnabled) {
    			// Maybe something has changed?
				if(port != curPort) break;
				if(!(this.bindto.equals(bindTo))) break;
    			try {
    				Socket s = server.accept();
    				InputStream in = s.getInputStream();
    				OutputStream out = s.getOutputStream();
    				
    				TextModeClientInterface tmci = 
					new TextModeClientInterface(this, in, out);
    				
    				Thread t = new Thread(tmci, "Text mode client interface handler for "+s.getPort());
    				
    				t.setDaemon(true);
    				
    				t.start();
    				
    			} catch (SocketTimeoutException e) {
    				// Ignore and try again
    			} catch (SocketException e){
    				Logger.error(this, "Socket error : "+e, e);
    			} catch (IOException e) {
    				Logger.error(this, "TMCI failed to accept socket: "+e, e);
    			}
    		}
    		try{
    			server.close();
    		}catch (IOException e){
    			Logger.error(this, "Error shuting down TMCI", e);
    		}
    	}
    }

	public void setPort(int val) {
		port = val;
	}
	
}
