package net.floodlightcontroller.util;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.tutorial.one.ProcessPacketIn;

public class UtilLog {
	
	protected static Ansi ansi;	 
			
	public static String blue(String string) {
		ansi = new Ansi(); 
		ansi.fgBlue().a(string).fgDefault();
		return ansi.toString();		
	}

}
