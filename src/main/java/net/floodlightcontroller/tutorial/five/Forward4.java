/**
 * Implementação baseada no estudo do forwarding.java
 */
package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.Masked;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import net.floodlightcontroller.core.FloodlightContext;
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
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IGatewayService;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingDecisionChangedListener;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.L3RoutingManager;
import net.floodlightcontroller.routing.VirtualGatewayInstance;
import net.floodlightcontroller.routing.VirtualGatewayInterface;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.tutorial.five.Forward4.DeviceListenerImpl;
import net.floodlightcontroller.util.ParseUtils;

public class Forward4 extends ForwardingBase implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener,
		IRoutingDecisionChangedListener, IGatewayService {

	private static final short FLOWSET_BITS = 28;
	private static final long FLOWSET_MAX = (long) (Math.pow(2, FLOWSET_BITS) - 1);
	private static final short DECISION_BITS = 24;
	protected static final short FLOWSET_SHIFT = DECISION_BITS;

	protected static FlowSetIdRegistry flowSetIdRegistry;
	private static L3RoutingManager l3manager;
	private Map<OFPacketIn, Ethernet> l3cache;
	private DeviceListenerImpl deviceListener;

	@Override
	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx) {
		// TODO Auto-generated method stub
		return null;
	}

	private boolean sendPacketToLastHop(Ethernet eth, IDevice destDevice) {
		SwitchPort trueAp = findTrueAttachmentPoint(destDevice.getAttachmentPoints());
		if (trueAp == null) {
			return false;
		}
		IOFSwitch sw = switchService.getSwitch(trueAp.getNodeId());
		OFPort outputPort = trueAp.getPortId();
		Optional<VirtualGatewayInstance> instance = getGatewayInstance(trueAp.getNodeId());
		if (!instance.isPresent()) {
			instance = getGatewayInstance(new NodePortTuple(trueAp.getNodeId(), trueAp.getPortId()));
		}
		if (!instance.isPresent()) {
			log.info("Could not locate virtual gateway instance for DPID {}, port {}", sw.getId(), outputPort);
			return false;
		}
		MacAddress gatewayMac = instance.get().getGatewayMac();
		IPacket outPacket = new Ethernet().setSourceMACAddress(gatewayMac)
				.setDestinationMACAddress(destDevice.getMACAddress()).setEtherType(eth.getEtherType())
				.setVlanID(eth.getVlanID()).setPayload(eth.getPayload());
		OFFactory factory = sw.getOFFactory();
		OFPacketOut.Builder packetOut = factory.buildPacketOut();

		List<OFAction> actions = new ArrayList<>();
		actions.add(factory.actions().output(outputPort, Integer.MAX_VALUE));
		packetOut.setActions(actions);

		packetOut.setData(outPacket.serialize());
		if (log.isTraceEnabled()) {
			log.trace("Writing PacketOut, switch={}, output port={}, packet-out={}",
					new Object[] { sw, outputPort, packetOut.build() });
		}
		messageDamper.write(sw, packetOut.build());
		log.debug("Push packet out the last hop switch (true attachment point)");
		return true;
	}

	/**
	 * 
	 * Esta função retorna a porta que possui um link realmente ativo entre o host e
	 * o attachmentPoint
	 */
	private SwitchPort findTrueAttachmentPoint(SwitchPort[] aps) {
		if (aps != null) {
			// Caso o node esteja conectado a multiplos attachmentPoints
			for (SwitchPort ap : aps) {
				// Retorna todas as portas ativas do switch
				Set<OFPort> portsOnLinks = topologyService.getPortsWithLinks(ap.getNodeId());
				if (portsOnLinks == null) {
					log.error("Error looking up ports with links from topology service for switch {}", ap.getNodeId());
					continue;
				}
				// FIXME Aparentemente este if está errado, segundo a lógica o link é verdadeiro
				// caso esteja contido na lista de links on, certo?
				if (!portsOnLinks.contains(ap.getNodeId())) {
					log.info("Found 'true' attachment point of {}", ap);
					return ap;
				} else {
					log.trace("Attachment point {} was not the 'true' attachment point", ap);
				}
			}
		}
		/* This will catch case aps=null, empty, or no-true-ap */
		log.error("Could not locate a 'true' attachment point in {}", aps);
		return null;
	}

	@Override
	public Collection<VirtualGatewayInstance> getGatewayInstances() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(DatapathId dpid) {
		return l3manager.getVirtualGateway(dpid);
	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(NodePortTuple npt) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<VirtualGatewayInterface> getGatewayInterfaces(VirtualGatewayInstance gateway) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<VirtualGatewayInterface> getGatewayInterface(String name, VirtualGatewayInstance gateway) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addGatewayInstance(VirtualGatewayInstance gateway) {
		// TODO Auto-generated method stub

	}

	@Override
	public void addVirtualInterface(VirtualGatewayInstance gateway, VirtualGatewayInterface intf) {
		// TODO Auto-generated method stub

	}

	@Override
	public VirtualGatewayInstance updateVirtualGateway(String name, MacAddress newMac) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateVirtualInterface(VirtualGatewayInstance gateway, VirtualGatewayInterface intf) {
		// TODO Auto-generated method stub

	}

	@Override
	public void deleteGatewayInstances() {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean deleteGatewayInstance(String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void removeAllVirtualInterfaces(VirtualGatewayInstance gateway) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean removeVirtualInterface(String interfaceName, VirtualGatewayInstance gateway) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void routingDecisionChanged(Iterable<Masked<U64>> changedDecisions) {
		// TODO Auto-generated method stub

	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchAdded(DatapathId switchId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchActivated(DatapathId switchId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchChanged(DatapathId switchId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void switchDeactivated(DatapathId switchId) {
		// TODO Auto-generated method stub

	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		// Precisa também inicializar a classe extendida
		super.init();
		// Aqui inicializa todas as dependências da classe ForwardBase.java
		this.floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		this.routingEngineService = context.getServiceImpl(IRoutingService.class);
		this.deviceManagerService = context.getServiceImpl(IDeviceService.class);
		// this.restApiService = context.getServiceImpl(IRestApiService.class);
		this.topologyService = context.getServiceImpl(ITopologyService.class);
		this.debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		this.switchService = context.getServiceImpl(IOFSwitchService.class);
		this.linkService = context.getServiceImpl(ILinkDiscoveryService.class);

		// Inicializa dependências da extenção
		l3manager = new L3RoutingManager();
		l3cache = new ConcurrentHashMap<>();
		deviceListener = new DeviceListenerImpl();
		
		flowSetIdRegistry = FlowSetIdRegistry.getInstance();
		
		//Get statcs  de configurações 
		Map<String, String> configParameters = context.getConfigParams(this);
		
		
		String tmp = configParameters.get("hard-timeout");		
		if(tmp!=null) {
			FLOWMOD_DEFAULT_HARD_TIMEOUT = ParseUtils.parseHexOrDecInt(tmp);
		}else {
            log.info("Default hard timeout not configured. Using {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
        }
		
		tmp = configParameters.get("idle-timeout");
        if (tmp != null) {
            FLOWMOD_DEFAULT_IDLE_TIMEOUT = ParseUtils.parseHexOrDecInt(tmp);
            log.info("Default idle timeout set to {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        } else {
            log.info("Default idle timeout not configured. Using {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        }
        
        tmp = configParameters.get("table-id");
        if (tmp != null) {
            FLOWMOD_DEFAULT_TABLE_ID = TableId.of(ParseUtils.parseHexOrDecInt(tmp));
            log.info("Default table ID set to {}.", FLOWMOD_DEFAULT_TABLE_ID);
        } else {
            log.info("Default table ID not configured. Using {}.", FLOWMOD_DEFAULT_TABLE_ID);
        }
        
        tmp = configParameters.get("priority");
        if (tmp != null) {
            FLOWMOD_DEFAULT_PRIORITY = ParseUtils.parseHexOrDecInt(tmp);
            log.info("Default priority set to {}.", FLOWMOD_DEFAULT_PRIORITY);
        } else {
            log.info("Default priority not configured. Using {}.", FLOWMOD_DEFAULT_PRIORITY);
        }
        
        tmp = configParameters.get("set-send-flow-rem-flag");
        if (tmp != null) {
            FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = Boolean.parseBoolean(tmp);
            log.info("Default flags will be set to SEND_FLOW_REM {}.", FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG);
        } else {
            log.info("Default flags will be empty.");
        }
        
        
        tmp = configParameters.get("match");
        if (tmp != null) {
            tmp = tmp.toLowerCase();
            if (!tmp.contains("in-port") && !tmp.contains("vlan") 
                    && !tmp.contains("mac") && !tmp.contains("ip") 
                    && !tmp.contains("transport") && !tmp.contains("flag")) {
                /* leave the default configuration -- blank or invalid 'match' value */
            } else {
                FLOWMOD_DEFAULT_MATCH_IN_PORT = tmp.contains("in-port") ? true : false;
                FLOWMOD_DEFAULT_MATCH_VLAN = tmp.contains("vlan") ? true : false;
                FLOWMOD_DEFAULT_MATCH_MAC = tmp.contains("mac") ? true : false;
                FLOWMOD_DEFAULT_MATCH_IP = tmp.contains("ip") ? true : false;
                FLOWMOD_DEFAULT_MATCH_TRANSPORT = tmp.contains("transport") ? true : false;
				FLOWMOD_DEFAULT_MATCH_TCP_FLAG = tmp.contains("flag") ? true : false;
			}
		}
        log.info("Default flow matches set to: IN_PORT=" + FLOWMOD_DEFAULT_MATCH_IN_PORT
				+ ", VLAN=" + FLOWMOD_DEFAULT_MATCH_VLAN
				+ ", MAC=" + FLOWMOD_DEFAULT_MATCH_MAC
				+ ", IP=" + FLOWMOD_DEFAULT_MATCH_IP
				+ ", FLAG=" + FLOWMOD_DEFAULT_MATCH_TCP_FLAG
                + ", TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT);
        
        tmp = configParameters.get("detailed-match");
        if (tmp != null) {
            tmp = tmp.toLowerCase();
            if (!tmp.contains("src-mac") && !tmp.contains("dst-mac") 
                    && !tmp.contains("src-ip") && !tmp.contains("dst-ip")
                    && !tmp.contains("src-transport") && !tmp.contains("dst-transport")) {
                /* leave the default configuration -- both src and dst for layers defined above */
            } else {
                FLOWMOD_DEFAULT_MATCH_MAC_SRC = tmp.contains("src-mac") ? true : false;
                FLOWMOD_DEFAULT_MATCH_MAC_DST = tmp.contains("dst-mac") ? true : false;
                FLOWMOD_DEFAULT_MATCH_IP_SRC = tmp.contains("src-ip") ? true : false;
                FLOWMOD_DEFAULT_MATCH_IP_DST = tmp.contains("dst-ip") ? true : false;
                FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC = tmp.contains("src-transport") ? true : false;
                FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST = tmp.contains("dst-transport") ? true : false;
            }
        }
        
        
        log.info("Default detailed flow matches set to: SRC_MAC=" + FLOWMOD_DEFAULT_MATCH_MAC_SRC
                + ", DST_MAC=" + FLOWMOD_DEFAULT_MATCH_MAC_DST
                + ", SRC_IP=" + FLOWMOD_DEFAULT_MATCH_IP_SRC
                + ", DST_IP=" + FLOWMOD_DEFAULT_MATCH_IP_DST
                + ", SRC_TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC
                + ", DST_TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST);

        tmp = configParameters.get("flood-arp");
        if (tmp != null) {
            tmp = tmp.toLowerCase();
            if (!tmp.contains("yes") && !tmp.contains("yep") && !tmp.contains("true") && !tmp.contains("ja") && !tmp.contains("stimmt")) {
                FLOOD_ALL_ARP_PACKETS = false;
                log.info("Not flooding ARP packets. ARP flows will be inserted for known destinations");
            } else {
                FLOOD_ALL_ARP_PACKETS = true;
                log.info("Flooding all ARP packets. No ARP flows will be inserted");
            }
        }

        tmp = configParameters.get("remove-flows-on-link-or-port-down");
        if (tmp != null) {
            REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN = Boolean.parseBoolean(tmp);
        }
        if (REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN) {
            log.info("Flows will be removed on link/port down events");
        } else {
            log.info("Flows will not be removed on link/port down events");
        }

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		super.startUp();
		
		

	}

	/// Inner Class
	public class DeviceListenerImpl implements IDeviceListener {

		@Override
		public String getName() {
			// ignore
			return null;
		}

		@Override
		public boolean isCallbackOrderingPrereq(String type, String name) {
			// ignore
			return false;
		}

		@Override
		public boolean isCallbackOrderingPostreq(String type, String name) {
			// ignore
			return false;
		}

		@Override
		public void deviceAdded(IDevice device) {
			// ignore

		}

		@Override
		public void deviceRemoved(IDevice device) {
			// ignore

		}

		@Override
		public void deviceMoved(IDevice device) {
			// ignore

		}

		@Override
		public void deviceIPV4AddrChanged(IDevice device) {
			if (device.getIPv4Addresses() == null) {
				return;
			}
			for (IPv4Address ip : device.getIPv4Addresses()) {
				while (findPacketInCache(l3cache, ip).isPresent()) {
					Ethernet eth = findPacketInCache(l3cache, ip).get();
					sendPacketToLastHop(eth, device);
				}
			}

		}

		private Optional<Ethernet> findPacketInCache(Map<OFPacketIn, Ethernet> l3cache, IPv4Address ip) {
			Predicate<? super Ethernet> predicate = packet -> ((IPv4) packet.getPayload()).getDestinationAddress()
					.equals(ip);
			// O cache L3 aparentemente serve para guardar pacotes que ja passaram pelo
			// controlador
			// retorna uma lista de <Ethernet> cujo destino havia sido igual o informado
			return l3cache.values().stream().filter(predicate).findAny();

		}

		@Override
		public void deviceIPV6AddrChanged(IDevice device) {
			// ignore

		}

		@Override
		public void deviceVlanChanged(IDevice device) {
			// ignore

		}

	}

	protected static class FlowSetIdRegistry {
		private volatile Map<NodePortTuple, Set<U64>> nptToFlowSetIds;
		private volatile Map<U64, Set<NodePortTuple>> flowSetIdToNpts;
		private volatile long flowSetGenerator = -1;

		private static volatile FlowSetIdRegistry instance;

		private FlowSetIdRegistry() {
			nptToFlowSetIds = new ConcurrentHashMap<>();
			flowSetIdToNpts = new ConcurrentHashMap<>();
		}

		protected static FlowSetIdRegistry getInstance() {
			if (instance == null) {
				instance = new FlowSetIdRegistry();
			}
			return instance;
		}

		protected void seedFlowSetIdForUnitTest(int seed) {
			flowSetGenerator = seed;
		}

		protected synchronized U64 generateFlowSetId() {
			flowSetGenerator++;
			if (flowSetGenerator == FLOWSET_MAX) {
				flowSetGenerator = 0;
				log.warn("Flowset IDs have exceeded capacity of {}. Flowset ID generator resetting back to 0",
						FLOWSET_MAX);
			}
			U64 id = U64.of(flowSetGenerator << FLOWSET_SHIFT);
			log.debug("Generating flowset ID {}, shifted {}", flowSetGenerator, id);

			return id;
		}

		private void registerFlowSetId(NodePortTuple npt, U64 flowSetId) {
			if (nptToFlowSetIds.containsKey(npt)) {
				Set<U64> ids = nptToFlowSetIds.get(npt);
				ids.add(flowSetId);
			} else {
				Set<U64> ids = new HashSet<>();
				ids.add(flowSetId);
				nptToFlowSetIds.put(npt, ids);
			}
			flowSetIdToNpts = null;
			if (flowSetIdToNpts.containsKey(flowSetId)) {
				Set<NodePortTuple> npts = flowSetIdToNpts.get(flowSetId);
				npts.add(npt);
			} else {
				Set<NodePortTuple> npts = new HashSet<NodePortTuple>();
				npts.add(npt);
				flowSetIdToNpts.put(flowSetId, npts);
			}
		}

		private Set<U64> getFlowSetIds(NodePortTuple npt) {
			return this.nptToFlowSetIds.get(npt);
		}

		private Set<NodePortTuple> getNodePortTuples(U64 flowSetId) {
			return this.flowSetIdToNpts.get(flowSetId);
		}

		private void removeNodePortTuple(NodePortTuple npt) {
			nptToFlowSetIds.remove(npt);
			// Também precisa remover de idsToNpt

			Iterator<Set<NodePortTuple>> itr = this.flowSetIdToNpts.values().iterator();
			while (itr.hasNext()) {
				Set<NodePortTuple> npts = itr.next();
				npts.remove(npt);
			}
		}

		private void removeExpiredFlowSetId(U64 flowSetId, NodePortTuple avoid, Iterator<U64> avoidItr) {
			this.flowSetIdToNpts.remove(flowSetId);

			Iterator<Entry<NodePortTuple, Set<U64>>> itr = nptToFlowSetIds.entrySet().iterator();
			boolean removed = false;
			while (itr.hasNext()) {
				Entry<NodePortTuple, Set<U64>> entry = itr.next();

				if (entry.getKey().equals(avoid) && !removed) {
					avoidItr.remove();
					removed = true;
				} else {
					Set<U64> ids = entry.getValue();
					ids.remove(flowSetId);
				}

			}
		}
	}
}
