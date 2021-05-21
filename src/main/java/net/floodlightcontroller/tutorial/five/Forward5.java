/**
 * @author devairdarolt
 * 
 * Objetivo dessa classe é fazer o roteamento utilizando L3
 * 
 * Sempre que ocorre mudanças nos estados de links/switch/devices
 * o controlador gera eventos que são gerenciados por esse módulo
 * de tal forma que este tenha como finalidade manter as tabelas dos
 * switches atualizadas para que não ocorra packes-in
 * 
 * Funcionalidades:
 * 
 * 1. AddDevice >>> quando um host é adcionado a rede é configurado todas as tabelas dos switchs para que tenha rotas ah este host
 * 2. RmvDevice >>> quando um host é removido, então remove-se todas as regras de todos os switchs associados a este host
 * 3. LDUP      >>> quando um kink é atualizado para UP/DOWN é necessário atualizar as regras de fluxos de todos os switchs para que o roteamento continue funcionando *  
 */
package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
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
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.FlowModUtils;

@SuppressWarnings("unused")
public class Forward5 implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener {
	public static Logger log = LoggerFactory.getLogger(Forward5.class);
	public static final String MODULE_NAME = Forward5.class.getSimpleName();
	private static final long COOKIE = 33;
	private static final int FLOWMOD_DEFAULT_IDLE_TIMEOUT = 0;// Infinite
	private static final int FLOWMOD_DEFAULT_HARD_TIMEOUT = 0;
	private static final int FLOWMOD_PRIORITY = 100;// Prioridade muito baixa

	// dependencies
	private IFloodlightProviderService serviceProvider;
	private ILinkDiscoveryService serviceLinkDiscovery;
	private IOFSwitchService serviceSwitch;
	private IDeviceService serviceDevice;
	private IRoutingService serviceRouting;
	private ITopologyService serviceTopology;
	// Switch table in which rules should be installed

	private byte table;

	// uteis
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
		if (this.knownHosts != null && this.knownHosts.containsKey(device)) {
			log.info("Device exists. Update");
		}

		Host host = new Host(device);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host */
			/*****************************************************************/

			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);

			for (SwitchPort attPoints : device.getAttachmentPoints()) {
				DatapathId swId = attPoints.getNodeId();
				IOFSwitch sw = serviceSwitch.getSwitch(swId);
				OFPort port = attPoints.getPortId();
				
				
				Match match = makeMatch(device, sw);
				makeFlow(match, sw, port);
				log.info("FLOW ADD SW {}",sw);
				
				
				if ("".equals("1")) {

					Set<DatapathId> allSwitches = serviceSwitch.getAllSwitchDpids();
					for (DatapathId swt : allSwitches) {
						if (!swt.equals(swId)) {
							List<NodePortTuple> path = serviceRouting.getPath(swt, swId).getPath();
							NodePortTuple nodePort = path.get(0);
							IOFSwitch ofSwitch = serviceSwitch.getSwitch(swt);
							OFPort ofPort = nodePort.getPortId();
							match = makeMatch(device, ofSwitch);
							makeFlow(match, ofSwitch, ofPort);
							log.info("Adicionado fluxo em sw {} porta {}", ofSwitch, ofPort);
						}
					}
				}

			}

		}

	}

	private void makeFlow(Match match, IOFSwitch sw, OFPort port) {
		OFFlowMod.Builder flowBuilder;
		flowBuilder = sw.getOFFactory().buildFlowAdd();
		flowBuilder.setMatch(match);
		flowBuilder.setCookie(U64.ZERO);
		flowBuilder.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		flowBuilder.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
		flowBuilder.setBufferId(OFBufferId.NO_BUFFER);
		flowBuilder.setPriority(FLOWMOD_PRIORITY);
		flowBuilder.setOutPort(port);
		Set<OFFlowModFlags> flags = new HashSet<OFFlowModFlags>();
		flags.add(OFFlowModFlags.SEND_FLOW_REM);// Flag para marcar o fluxo par ser removido quando o
												// idl-timeout ocorrer
		flowBuilder.setFlags(flags);

		// ACTIONS
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(port).setMaxLen(0xffFFffFF).build());

		// INSERT IN SWITCH OF PATH
		FlowModUtils.setActions(flowBuilder, actions, sw);
		sw.write(flowBuilder.build());

	}

	private Match makeMatch(IDevice device, IOFSwitch sw) {
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_DST, device.getMACAddress()).setExact(MatchField.IPV4_DST,
				device.getIPv4Addresses()[0]);
		Match match = mb.build();
		return match;
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
			host = new Host(device);
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
		//log.info("DISCOVERY UPDATE{}", updateList);
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
		this.serviceProvider = context.getServiceImpl(IFloodlightProviderService.class);
		this.serviceLinkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
		this.serviceDevice = context.getServiceImpl(IDeviceService.class);
		this.serviceSwitch = context.getServiceImpl(IOFSwitchService.class);
		this.serviceRouting = context.getServiceImpl(IRoutingService.class);
		this.serviceTopology = context.getServiceImpl(ITopologyService.class);
		this.knownHosts = new ConcurrentHashMap<IDevice, Host>();

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info("Startup {}", MODULE_NAME);
		// Not used in routing, this routing type do not make packets, all host is pré
		// configurated in events discovery
		// this.serviceProvider.addOFSwitchListener(this);
		// this.serviceProvider.addOFMessageListener(OFType.PACKET_IN, this);
		// this.serviceProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
		this.serviceLinkDiscovery.addListener(this);
		this.serviceDevice.addListener(this);

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
		return serviceLinkDiscovery.getLinks().keySet();
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
		private OFPort hostPort;

		/**
		 * Create a host.
		 * 
		 * @param device          meta-data about the host from Floodlight's device
		 *                        manager
		 * @param serviceProvider Floodlight module to lookup switches by DPID
		 */
		public Host(IDevice device) {
			this.device = device;
			log.info("Host {}", Forward5.this.knownHosts);
		}

		/**
		 * Get the host's name (assuming a host's name corresponds to its MAC address).
		 * 
		 * @return the host's name
		 */
		public String getName() {
			return String.format("h{}", this.getMACAddress().getLong());
		}

		/**
		 * Get the host's MAC address.
		 * 
		 * @return the host's MAC address
		 */
		public MacAddress getMACAddress() {
			return this.device.getMACAddress();
		}

		/**
		 * Get the host's IPv4 address.
		 * 
		 * @return the host's IPv4 address, null if unknown
		 */
		public IPv4Address getIPv4Address() {
			if (null == this.device.getIPv4Addresses() || 0 == this.device.getIPv4Addresses().length) {
				return null;
			}
			return this.device.getIPv4Addresses()[0];
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

			IOFSwitch sw = serviceSwitch.getActiveSwitch(this.device.getAttachmentPoints()[0].getNodeId());

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
