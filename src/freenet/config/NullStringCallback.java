package freenet.config;

import freenet.support.api.StringCallback;

public class NullStringCallback implements StringCallback {

	public String get() {
		return "";
	}

	public void set(String val) throws InvalidConfigValueException {
		// Ignore
	}

}