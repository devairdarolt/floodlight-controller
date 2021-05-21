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
import org.projectfloodlight.openflow.types.EthType;
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
public class Forward5 implements IFloodlightModule, IDeviceListener {
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
		log.info("DEVICE ADD {} {}", device.getMACAddress(),device.getIPv4Addresses());
		// TODO: check if host exists and update
		List<IDevice> keyToRemove = new ArrayList<IDevice>();
		if(knownHosts.containsKey(device)) {
			log.info(">>>>>>>>>>>>>>> Device exists. Updating");
		}
		Host host = null;
		if (this.knownHosts != null) {
			for(Entry<IDevice, Host> entry:knownHosts.entrySet()) {
				if(entry.getKey().getMACAddress().equals(device.getMACAddress())) {
					knownHosts.put(device, knownHosts.get(entry.getKey()));
					host = knownHosts.get(device);
					keyToRemove.add(entry.getKey());
					log.info("Device exists. Updating");
				}
			}
					
		}
		if(host==null) {
			host = new Host(device);
		}
		
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {			
			//Adiciona fluxo para receber os pacotes do gateway 
						
			makeFlow(makeL2Match(host, host.getSwitch()), host.getSwitch(), host.getSwitchPort());
			makeFlow(makeL3Match(host, host.getSwitch()), host.getSwitch(), host.getSwitchPort());
			
			log.info("FLOW ADD in host switch [{}] port[{}]", host.getSwitch(),host.getSwitchPort());
			//Adiciona fluxo para receber broadcast
			makeFlow(host.getSwitch().getOFFactory().buildMatch().setExact(MatchField.ETH_DST, MacAddress.BROADCAST).build(), host.getSwitch(), OFPort.FLOOD);
			//1. Adiciona fluxo em todos os switches
			//             Similar a colocar uma placa em cada cidade apontando como chegar em joinville
			//2. Adiciona também regras para tratar broadcast e multcast em todos os switches
			for(DatapathId switchId:serviceSwitch.getAllSwitchDpids()) {
				//O gateway ja possui um flow 
				if(!switchId.equals(host.getSwitch().getId())) {
					IOFSwitch sw = serviceSwitch.getSwitch(switchId);
					for(NodePortTuple node:serviceRouting.getPath(switchId, host.getSwitch().getId()).getPath()) {
						log.info("Node {}",node);
						
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
	private Match makeL3Match(Host host, IOFSwitch sw) {
		Match.Builder mb = sw.getOFFactory().buildMatch();	
		
		mb.setExact(MatchField.ETH_TYPE,EthType.IPv4);//Pre-requisito para match de IPV4
		mb.setExact(MatchField.IPV4_DST, host.getIPv4Address());
		
		Match match = mb.build();
		log.info("Match L3 build to [{},{}] in switch[{}]", new Object[] {host.getMACAddress(),host.getIPv4Address(),sw.getId()});
		return match;
	}

	private Match makeL2Match(Host host, IOFSwitch sw) {
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_DST, host.getMACAddress());	
		
		Match match = mb.build();
		log.info("Match L2 build to [{},{}] in switch[{}]", new Object[] {host.getMACAddress(),host.getIPv4Address(),sw.getId()});
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
		/*****************************************************************/
		/* TODO: Update routing: add rules to route to new host */
		/*****************************************************************/			
		Host host = this.knownHosts.get(device);		
		//Adiciona fluxo para receber os pacotes do gateway 
					
		makeFlow(makeL2Match(host, host.getSwitch()), host.getSwitch(), host.getSwitchPort());
		makeFlow(makeL3Match(host, host.getSwitch()), host.getSwitch(), host.getSwitchPort());
		
		log.info("FLOW ADD in host switch [{}] port[{}]", host.getSwitch(),host.getSwitchPort());
		//Adiciona fluxo para receber broadcast
		makeFlow(host.getSwitch().getOFFactory().buildMatch().setExact(MatchField.ETH_DST, MacAddress.BROADCAST).build(), host.getSwitch(), OFPort.FLOOD);
		//1. Adiciona fluxo em todos os switches
		//             Similar a colocar uma placa em cada cidade apontando como chegar em joinville
		//2. Adiciona também regras para tratar broadcast e multcast em todos os switches
		for(DatapathId switchId:serviceSwitch.getAllSwitchDpids()) {
			//O gateway ja possui um flow 
			if(!switchId.equals(host.getSwitch().getId())) {
				IOFSwitch sw = serviceSwitch.getSwitch(switchId);
				for(NodePortTuple node:serviceRouting.getPath(switchId, host.getSwitch().getId()).getPath()) {
					log.info("Node {}",node);
					
				}
				
			}
		}
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
	 * Criado para auxiliar o controle de Devices na rede
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
		}

		public OFPort getSwitchPort() {
			for (SwitchPort port : this.device.getAttachmentPoints()) {
				return port.getPortId();
			}

			return null;

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
			for (IPv4Address ip : this.device.getIPv4Addresses()) {
				return ip;
			}
			return null;
		}

		/**
		 * Get the switch to which the host is connected.
		 * 
		 * @return the switch to which the host is connected, null if unknown
		 */
		public IOFSwitch getSwitch() {
			for (SwitchPort att : this.device.getAttachmentPoints()) {
				return Forward5.this.serviceSwitch.getSwitch(att.getNodeId());
			}
			return null;

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
