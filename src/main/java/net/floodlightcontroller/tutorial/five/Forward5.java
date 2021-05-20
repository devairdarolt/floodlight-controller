/**
 * Objetivo dessa classe é fazer o roteamento utilizando L3
 * 
 * 
 */
package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;

public class Forward5 implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener {
	public static Logger log = LoggerFactory.getLogger(Forward5.class);
	public static final String MODULE_NAME = Forward5.class.getSimpleName();
	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;
	// Interface to link discovery service
	private ILinkDiscoveryService linkDiscProv;
	// Interface to switch service
	private IOFSwitchService serviceSwitch;
	// Interface to device manager service
	private IDeviceService deviceProv;
	// Switch table in which rules should be installed
	private byte table;
	// Map of hosts to devices
	private Map<IDevice, Host> knownHosts;

	@Override
	public String getName() {

		return Forward5.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) {

		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) {

		return false;
	}

	@Override
	public void deviceAdded(IDevice device) {
		log.info("DEVICE ADD {}", device);

		// TODO: check if host exists and update
		if(this.knownHosts!=null && this.knownHosts.containsKey(device)) {
			log.info("Device exists. Update");
		}
		
		Host host = new Host(device, this.floodlightProv, this.serviceSwitch);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host */
			/*****************************************************************/

		}

	}

	@Override
	public void deviceRemoved(IDevice device) {
		log.info("DEVICE REMOVED {}", device);
		Host host = this.knownHosts.get(device);
		if (null == host) {
			return;
		}
		this.knownHosts.remove(host);
		/**
		 * TODO: Remove rulles from all switch witch divice address TODO: Remove from
		 * knowHosts
		 */

	}

	@Override
	public void deviceMoved(IDevice device) {
		log.info("DEVICE MOVED {}", device);
		Host host = this.knownHosts.get(device);
		if (null == host) {
			host = new Host(device, this.floodlightProv, this.serviceSwitch);
			this.knownHosts.put(device, host);
		}

		if (!host.isAttachedToSwitch()) {
			this.deviceRemoved(device);
			return;
		}
		/**
		 * TODO: change rules from all switch to the new Address TODO: change knowHosts
		 * infos
		 */

	}

	@Override
	public void deviceIPV4AddrChanged(IDevice device) {
		log.info("DEVICE IPv4 CHANGED {}", device);
		this.deviceAdded(device);

	}

	@Override
	public void deviceIPV6AddrChanged(IDevice device) {
		log.info("DEVICE IPv6 CHANGED {}", device);
		this.deviceAdded(device);
	}

	@Override
	public void deviceVlanChanged(IDevice device) {
		log.info("DEVICE VLAN CHANGED{}", device);
		this.deviceAdded(device);
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		log.info("DISCOVERY UPDATE{}", updateList);
		for (LDUpdate update : updateList) {
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			/*
			 * if ( update.getDst()!=null && 0 == update.getDst().getLong()) {
			 * log.info(String.format("Link {}:{} -> host updated", update.getSrc(),
			 * update.getSrcPort())); }else{// Otherwise, the link is between two switches
			 * log.info(String.format("Link {}:{} -> {}:{} updated", update.getSrc(),
			 * update.getSrcPort(), update.getDst(), update.getDstPort())); }
			 */
		}

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */
		/*********************************************************************/

	}

	@Override
	public void switchAdded(DatapathId switchId) {
		log.info("SWITCH ADD {}", switchId);
		IOFSwitch sw = this.serviceSwitch.getSwitch(switchId);
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */

		/*********************************************************************/
	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		log.info("SWITCH REMOVED {}", switchId);
		IOFSwitch sw = this.serviceSwitch.getSwitch(switchId);
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts */
		/*********************************************************************/
	}

	@Override
	public void switchActivated(DatapathId switchId) {
		log.info("SWITCH ACTVATE {}", switchId);

	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
		log.info("SWITCH PORT CHANGED {}", switchId);

	}

	@Override
	public void switchChanged(DatapathId switchId) {
		log.info("SWITCH CHANGED {}", switchId);

	}

	@Override
	public void switchDeactivated(DatapathId switchId) {
		log.info("SWITCH DEACTIVATE {}", switchId);

	}

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
		Collection<Class<? extends IFloodlightService>> floodlightService = new ArrayList<Class<? extends IFloodlightService>>();
		floodlightService.add(IFloodlightProviderService.class);
		floodlightService.add(ILinkDiscoveryService.class);
		floodlightService.add(IDeviceService.class);
		floodlightService.add(IOFSwitchService.class);
		return floodlightService;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info("Inicializando {}", MODULE_NAME);
		Map<String, String> config = context.getConfigParams(this);		
		this.floodlightProv = context.getServiceImpl(IFloodlightProviderService.class);
		this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
		this.deviceProv = context.getServiceImpl(IDeviceService.class);
		this.serviceSwitch = context.getServiceImpl(IOFSwitchService.class);
		this.knownHosts = new ConcurrentHashMap<IDevice, Host>();

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info("Startup {}", MODULE_NAME);
		// Not used in routing, this routing type do not make packets, all host is pré
		// configurated in events discovery
		// this.floodlightProv.addOFSwitchListener(this);
		// this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		// this.floodlightProv.addOFMessageListener(OFType.FLOW_REMOVED, this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);

		/*********************************************************************/
		/* TODO: Initialize variables or perform startup tasks, if necessary */
		/*********************************************************************/

	}

	/*
	 * Métodos auxiliares
	 * 
	 * @author devairdarolt *
	 */

	// Get a list of all known hosts in the network.
	private Collection<Host> getHosts() {
		return this.knownHosts.values();
	}

	private Map<Long, IOFSwitch> getSwitches() {
		Map<Long, IOFSwitch> ret = new HashMap<>();
		Map<DatapathId, IOFSwitch> switchMap = serviceSwitch.getAllSwitchMap();
		for (Entry<DatapathId, IOFSwitch> sw : switchMap.entrySet()) {
			ret.put(sw.getKey().getLong(), sw.getValue());
		}
		return ret;
	}

	// Get a list of all active links in the network.
	private Collection<Link> getLinks() {
		return linkDiscProv.getLinks().keySet();
	}

	/**
	 * 
	 * Host
	 * 
	 * 
	 * 
	 * @author devairdarolt
	 *
	 */
	private class Host {
		/* Meta-data about the host from Floodlight's device manager */
		private IDevice device;
		private IOFSwitchService switchService;

		/* Floodlight module which is needed to lookup switches by DPID */
		private IFloodlightProviderService floodlightProv;

		/**
		 * Create a host.
		 * 
		 * @param device         meta-data about the host from Floodlight's device
		 *                       manager
		 * @param floodlightProv Floodlight module to lookup switches by DPID
		 */
		public Host(IDevice device, IFloodlightProviderService floodlightProv, IOFSwitchService switchService) {
			this.device = device;
			this.floodlightProv = floodlightProv;
			this.switchService = switchService;
		}

		/**
		 * Get the host's name (assuming a host's name corresponds to its MAC address).
		 * 
		 * @return the host's name
		 */
		public String getName() {
			return String.format("h%d", this.getMACAddress());
		}

		/**
		 * Get the host's MAC address.
		 * 
		 * @return the host's MAC address
		 */
		public long getMACAddress() {
			return this.device.getMACAddress().getLong();
		}

		/**
		 * Get the host's IPv4 address.
		 * 
		 * @return the host's IPv4 address, null if unknown
		 */
		public Integer getIPv4Address() {
			if (null == this.device.getIPv4Addresses() || 0 == this.device.getIPv4Addresses().length) {
				return null;
			}
			return this.device.getIPv4Addresses()[0].getInt();
		}

		/**
		 * Get the switch to which the host is connected.
		 * 
		 * @return the switch to which the host is connected, null if unknown
		 */
		public IOFSwitch getSwitch() {
			if (null == this.device.getAttachmentPoints() || 0 == this.device.getAttachmentPoints().length) {
				return null;
			}

			IOFSwitch sw = switchService.getActiveSwitch(this.device.getAttachmentPoints()[0].getNodeId());

			return sw;

		}

		/**
		 * Get the port on the switch to which the host is connected.
		 * 
		 * @return the port to which the host is connected, null if unknown
		 */
		public Integer getPort() {
			if (null == this.device.getAttachmentPoints() || 0 == this.device.getAttachmentPoints().length) {
				return null;
			}
			return this.device.getAttachmentPoints()[0].getPortId().getPortNumber();
		}

		/**
		 * Checks whether the host is attached to some switch.
		 * 
		 * @return true if the host is attached to some switch, otherwise false
		 */
		public boolean isAttachedToSwitch() {
			return (null != this.getSwitch());
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Host)) {
				return false;
			}
			Host other = (Host) obj;
			return other.device.equals(this.device);
		}
	}

}
