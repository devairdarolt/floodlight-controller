/**
 * Baseado no forwarding
 * 
 * Roteamento com 
 */
package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.OFMessageUtils;

@SuppressWarnings("unused")
public class Forward6 implements IFloodlightModule, IOFMessageListener{
	protected static final Logger logger = LoggerFactory.getLogger(Forward6.class);
	private IFloodlightProviderService serviceFloodlightProvider;
	private IRoutingService serviceRoutingEngine;
	private IDeviceService serviceDeviceManager;
	private ITopologyService serviceTopology;
	private IOFSwitchService serviceSwitch;
	private ILinkDiscoveryService serviceLinkDiscovery;
	
	
	
	
	
	
	
	/**
	 * @IFloodlightModule methods
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {

		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IRoutingService.class);
		//l.add(IRestApiService.class);
		l.add(IDeviceService.class);
		l.add(ITopologyService.class);
		//l.add(IDebugCounterService.class);
		l.add(ILinkDiscoveryService.class);
		return l;
		
	}
	public static void setLoggingLevel(ch.qos.logback.classic.Level level) {
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		root.setLevel(level);
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		setLoggingLevel(ch.qos.logback.classic.Level.DEBUG);
		this.serviceFloodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		this.serviceRoutingEngine = context.getServiceImpl(IRoutingService.class);
		this.serviceDeviceManager = context.getServiceImpl(IDeviceService.class);		
		this.serviceTopology = context.getServiceImpl(ITopologyService.class);		
		this.serviceSwitch = context.getServiceImpl(IOFSwitchService.class);
		this.serviceLinkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
		
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceFloodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.ERROR, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
		
		
	}
	
	
	/**
	 * @IOFMessageListener methods
	 */
	@Override
	public String getName() {

		return Forward6.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {

		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {

		return false;
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch(msg.getType()) {
		case PACKET_IN:
			//TODO processPacketIn
			 OFPacketIn packetIn = OFPacketIn.class.cast(msg);
			 return processPacketIn(sw,packetIn,cntx);
			
		case FLOW_REMOVED:
			//TODO processFlowRemoved
			break;
		case ERROR:
			//TODO processError
			break;
		default:
			//TODO showMsg
			break;
			
		}
		
		return Command.CONTINUE;
	}
	/////////////////////////////////////////////////////////////////////////////////////////////////////
	// Auxiliar 
	/////////////////////////////////////////////////////////////////////////////////////////////////////

	private Command processPacketIn(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {
		
		logger.debug("Packet in receiver from sw {}",sw.getId());
		return Command.CONTINUE;
	}

}
