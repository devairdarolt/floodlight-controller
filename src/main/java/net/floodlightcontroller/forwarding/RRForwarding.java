/**
 * Baseado no forwarding
 * 
 * Roteamento com 
 */
package net.floodlightcontroller.forwarding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListSet;

import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.restlet.engine.header.RecipientInfoWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
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
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;
import net.floodlightcontroller.linkdiscovery.internal.LinkInfo;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.ConcurrentCircularBuffer;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.util.OFMessageUtils;
import net.floodlightcontroller.virtualnetwork.IVirtualNetworkService;
import net.floodlightcontroller.virtualnetwork.VirtualNetworkFilter;

@SuppressWarnings("unused")
public class RRForwarding implements IFloodlightModule, IOFMessageListener {
	// statics
	public static final long COOKIE = 333;
	private static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	private static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	private static short FLOWMOD_PRIORITY = 100;

	protected static final Logger log = LoggerFactory.getLogger(RRForwarding.class);

	// Dependencies
	private IRoutingService serviceRoutingEngine;
	private IDeviceService serviceDeviceManager;
	private ITopologyService serviceTopology;
	private IOFSwitchService serviceSwitch;
	private ILinkDiscoveryService serviceLinkDiscovery;
	IFloodlightProviderService serviceFloodlightProvider;

	// Internal stats
	private RR roundRobin;// Selector path

	protected DeviceListenerImpl deviceListener;
	protected ConcurrentSkipListSet<DatapathId> edgeSwitchSet; // Todos os switches que contém portas com liks para
																// hosts

	@Override
	public String getName() {

		return RRForwarding.class.getName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	/**
	 * Default message worker
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			return processPacketIn(sw, OFPacketIn.class.cast(msg), cntx);

		case FLOW_REMOVED:
			// TODO Modificar caso seja necessário que o controlador seja avisado quando um
			// fluxo expirar
			break;
		case ERROR:
			log.trace("Ocorreu um erro no switch {}", sw.getSwitchDescription().getDatapathDescription());
			break;
		default:
			log.trace("O controlador recebeu uma mensagem inesperada");
			break;

		}
		return Command.STOP;
	}

	/**
	 * Função responsável por fazer inserir as regras em switches para tratar o
	 * encaminhamento de fluxo
	 * 
	 * @param sw
	 * @param packetIn
	 * @param cntx
	 * @return
	 */
	private Command processPacketIn(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {

		// log.trace("Know Devices {}", knowDevices.keySet());
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		OFPort inPort = OFMessageUtils.getInPort(packetIn);

		if (!isEdgeSwitch(sw)) {
			log.trace("Not switch edge: {}", sw);
			return Command.CONTINUE;
		}

		if (eth.getEtherType().equals(EthType.ARP)) {
			boolean ARPprocessed = proxyARP(packetIn, cntx);
			return Command.CONTINUE;

		}
		if (eth.getEtherType().equals(EthType.IPv4)) {

			//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			// MATCH
			//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			IPv4 ipv4Packet = IPv4.class.cast(eth.getPayload());

			Match.Builder mb = sw.getOFFactory().buildMatch();
			mb.setExact(MatchField.IPV4_SRC, ipv4Packet.getSourceAddress());
			mb.setExact(MatchField.IPV4_DST, ipv4Packet.getDestinationAddress());
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
			if (ipv4Packet.getProtocol().equals(IpProtocol.TCP)) {
				mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
				TCP protocol = TCP.class.cast(ipv4Packet.getPayload());
				mb.setExact(MatchField.TCP_SRC, protocol.getSourcePort());
				mb.setExact(MatchField.TCP_DST, protocol.getDestinationPort());

			} else if (ipv4Packet.getProtocol().equals(IpProtocol.UDP)) {
				mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
				UDP protocol = UDP.class.cast(ipv4Packet.getPayload());
				mb.setExact(MatchField.TCP_SRC, protocol.getSourcePort());
				mb.setExact(MatchField.TCP_DST, protocol.getDestinationPort());

			}
			Match match = mb.build();

			//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			// GET ROUTE -- RR
			////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			List<NodePortTuple> nodes = null;

			if (!isOnTheSameSwitch(srcMac, dstMac, sw)) {

				nodes = roundRobin.getNextPath(srcMac, dstMac, ipv4Packet.getSourceAddress(),
						ipv4Packet.getDestinationAddress());

			} else {
				nodes = new ArrayList<>();
				for (Entry<MacAddress, SwitchPort> entry : deviceListener.knowMacAddress.entrySet()) {
					if (entry.getKey().equals(dstMac)) {
						nodes.add(new NodePortTuple(entry.getValue().getNodeId(), entry.getValue().getPortId()));
					}

				}
			}
			if (nodes == null) {
				log.trace("Não foi encontrado Path entre src e dst");
				return Command.CONTINUE;
			}
			log.trace("Path {}", nodes);
			////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			// ADD FLOW -- para cada node
			////////////////////////////////////////////////////////////////////////////////////////////////////////////////

			Iterator<NodePortTuple> nodeItr = nodes.iterator();
			NodePortTuple outNodePort = null;

			List<String> switches = new ArrayList<>();
			while (nodeItr.hasNext()) {
				NodePortTuple node = nodeItr.next();// Link in
				if (node.getNodeId().equals(sw.getId())) {
					outNodePort = node;// store the first hop to create packet-out
				}
				switches.add(serviceSwitch.getSwitch(node.getNodeId()).getSwitchDescription().getDatapathDescription());
				addFlow(sw, match, node);

			}
			log.trace("{} >> {} addFlow {}", srcMac, dstMac, switches);

			////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			// PACKET OUT -- in the first hop
			////////////////////////////////////////////////////////////////////////////////////////////////////////////////

			if (outNodePort != null) {
				log.trace("PacketOut {}{}", sw, outNodePort.getPortId());
				writePacketOutForPacketIn(sw, packetIn, outNodePort.getPortId());
			}
		}
		return Command.CONTINUE;
	}

	/**
	 * Send packet arps direct to the switch port of the host
	 * 
	 * @param packetIn
	 * @param cntx
	 */
	private boolean proxyARP(OFPacketIn packetIn, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		SwitchPort swPortMac = deviceListener.findSwitchPort(dstMac);

		ARP arp = ARP.class.cast(eth.getPayload());

		SwitchPort swPortIp = deviceListener.findSwitchPort(arp.getTargetProtocolAddress());

		if (swPortIp != null) {
			IOFSwitch sw = serviceSwitch.getSwitch(swPortIp.getNodeId());
			if (sw == null)
				return false;
			OFPort egresPort = swPortIp.getPortId();
			writePacketOutForPacketIn(sw, packetIn, egresPort);
			log.trace("send ARP targeted IP {} to {}", arp.getTargetProtocolAddress(), swPortIp);
			return true;
		}

		if (swPortMac != null) {
			IOFSwitch sw = serviceSwitch.getSwitch(swPortMac.getNodeId());
			if (sw == null)
				return false;
			OFPort egresPort = swPortMac.getPortId();
			writePacketOutForPacketIn(sw, packetIn, egresPort);
			log.trace("send ARP targeted mac {} to {}", arp.getTargetHardwareAddress(), swPortMac);
			return true;
		}

		// if controler don't now target switch
		Set<NodePortTuple> targets = getBroadcastPorts();
		if (targets == null || targets.isEmpty()) {
			return false;
		}
		for (NodePortTuple target : targets) {
			IOFSwitch sw = serviceSwitch.getSwitch(target.getNodeId());
			if (sw == null)
				return false;
			OFPort egresPort = target.getPortId();
			writePacketOutForPacketIn(sw, packetIn, egresPort);
			log.trace("send broadcast to {}", target);
		}
		return false;

	}

	private boolean isOnTheSameSwitch(MacAddress srcMac, MacAddress dstMac, IOFSwitch sw) {
		ArrayList<DatapathId> datapath = new ArrayList<>();
		DatapathId sw1 = null;
		DatapathId sw2 = null;

		for (Entry<MacAddress, SwitchPort> entry : deviceListener.knowMacAddress.entrySet()) {
			if (entry.getKey().equals(srcMac)) {
				sw1 = entry.getValue().getNodeId();
			}
			if (entry.getKey().equals(dstMac)) {
				sw2 = entry.getValue().getNodeId();
			}
			if (sw1 != null && sw2 != null) {
				if (sw1.equals(sw2)) {
					return true;
				}
			}
		}

		return false;
	}

	private boolean isEdgeSwitch(IOFSwitch sw) {
		if (this.edgeSwitchSet != null && !this.edgeSwitchSet.isEmpty()) {
			for (DatapathId swt : this.edgeSwitchSet) {
				if (swt.equals(sw.getId())) {
					return true;
				}
			}
		} else {
			this.edgeSwitchSet = getEdgesSwitches(); // Chek if update
			for (DatapathId swt : this.edgeSwitchSet) {
				if (swt.equals(sw.getId())) {
					return true;
				}
			}
		}

		return false;
	}

	private void processARPbroadcastOrMulticast(IOFSwitch sw, OFPacketIn packetIn, OFPort inPort,
			FloodlightContext cntx) {

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		ARP arpRequest = (ARP) eth.getPayload();

		Set<NodePortTuple> broadcastPorts = new HashSet<NodePortTuple>();
		SwitchPort switchPort = deviceListener.findSwitchPort(arpRequest.getTargetHardwareAddress());
		if (switchPort != null) {
			broadcastPorts.add(new NodePortTuple(switchPort.getNodeId(), switchPort.getPortId()));
		}
		if (broadcastPorts.isEmpty()) {
			broadcastPorts.addAll(getBroadcastPorts());
		}
		for (NodePortTuple node : broadcastPorts) {
			if (!(node.getNodeId().equals(sw.getId()) && node.getPortId().equals(inPort))) {
				writePacketOutForPacketIn(serviceSwitch.getSwitch(node.getNodeId()), packetIn, node.getPortId());
			}
		}

	}

	// Edge is switches conected to a host
	private ConcurrentSkipListSet<DatapathId> getEdgesSwitches() {
		Set<NodePortTuple> list = getBroadcastPorts();
		ConcurrentSkipListSet<DatapathId> swSet = new ConcurrentSkipListSet<>();
		swSet.addAll(edgeSwitchSet);
		if (swSet != null && !swSet.isEmpty()) {
			return swSet;
		} else {
			for (NodePortTuple node : list) {
				IOFSwitch sw = serviceSwitch.getActiveSwitch(node.getNodeId());
				if (sw != null) {
					swSet.add(sw.getId());
				}
			}

		}

		return swSet;
	}

	private Set<NodePortTuple> getBroadcastPorts() {
		// FIXME: Quando o controlador não conhece o DST DEVICE não é possível ober o
		// MAC
		// A solução seria fazer um flood broadcast, porém para evitar clonagem
		// desnecessárias de pacotes nos loops
		// é possivel utilizar operações de Set da seguinte forma
		// -- U = set<switch,port> U; -- todos os links da rede
		// -- B = set<switch,port> B; -- todos os links switch-switch
		// -- A = U - B; Todas as portas desconhecidas pelo controlador
		// -- flood(A); possíveis portas de hosts
		Set<NodePortTuple> A = new HashSet<NodePortTuple>();

		// get B -- Todas as portas switch-switch
		Set<NodePortTuple> B = new HashSet<NodePortTuple>();
		Map<Link, LinkInfo> internalLinks = serviceLinkDiscovery.getLinks();
		for (Entry<Link, LinkInfo> entry : internalLinks.entrySet()) {
			// log.trace("key [{}] value [{}]", entry.getKey(), entry.getValue());

			B.add(new NodePortTuple(entry.getKey().getSrc(), entry.getKey().getSrcPort()));
			B.add(new NodePortTuple(entry.getKey().getDst(), entry.getKey().getDstPort()));
		}

		// Get U -- Todas as portas de todos os switches
		Set<NodePortTuple> U = new HashSet<NodePortTuple>();
		Map<DatapathId, IOFSwitch> allSwitchMap = serviceSwitch.getAllSwitchMap();
		for (Entry<DatapathId, IOFSwitch> entry : allSwitchMap.entrySet()) {
			// log.trace("sw {} ports {}", entry.getKey(),
			// entry.getValue().getEnabledPortNumbers());
			for (OFPort port : entry.getValue().getEnabledPortNumbers()) {
				U.add(new NodePortTuple(entry.getKey(), port));
			}
		}

		// Todas as portas que não ~(switch-switch)
		for (NodePortTuple node : U) {
			if (!B.contains(node)) {
				A.add(node);
			}
		}
		log.trace("broadcast ports {}", A);
		return A;

	}

	public static void writePacketOutForPacketIn(IOFSwitch sw, OFPacketIn packetInMessage, OFPort egressPort) {

		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();

		// Set buffer_id, in_port, actions_len
		pob.setBufferId(packetInMessage.getBufferId());
		setInPort(pob, OFPort.ANY);

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>(1);
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(egressPort).setMaxLen(0xffFFffFF).build());
		pob.setActions(actions);

		// set data - only if buffer_id == -1
		if (packetInMessage.getBufferId() == OFBufferId.NO_BUFFER) {
			byte[] packetData = packetInMessage.getData();
			pob.setData(packetData);
		}

		// and write it out
		sw.write(pob.build());
	}

	public static void setInPort(OFPacketOut.Builder pob, OFPort in) {
		if (pob.getVersion().compareTo(OFVersion.OF_15) < 0) {
			pob.setInPort(in);
		} else if (pob.getMatch() != null) {
			pob.getMatch().createBuilder().setExact(MatchField.IN_PORT, in).build();
		} else {
			pob.setMatch(
					OFFactories.getFactory(pob.getVersion()).buildMatch().setExact(MatchField.IN_PORT, in).build());
		}
	}

	private void addFlow(IOFSwitch sw, Match match, NodePortTuple node) {
		OFFlowMod.Builder flowBuilder;
		flowBuilder = sw.getOFFactory().buildFlowAdd();
		flowBuilder.setMatch(match);
		flowBuilder.setCookie(U64.of(COOKIE));
		flowBuilder.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		flowBuilder.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
		flowBuilder.setBufferId(OFBufferId.NO_BUFFER);
		flowBuilder.setPriority(FLOWMOD_PRIORITY);
		flowBuilder.setOutPort(node.getPortId());
		Set<OFFlowModFlags> flags = new HashSet<OFFlowModFlags>();
		// flags.add(OFFlowModFlags.SEND_FLOW_REM);// Flag para marcar o fluxo par ser
		// removido quando o
		// idl-timeout ocorrer
		// flowBuilder.setFlags(flags);

		// ACTIONS
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(node.getPortId()).setMaxLen(0xffFFffFF).build());

		// INSERT IN SWITCH OF PATH
		IOFSwitch swit = serviceSwitch.getSwitch(node.getNodeId());
		FlowModUtils.setActions(flowBuilder, actions, swit);
		swit.write(flowBuilder.build());
		// logger.info("Flow ADD node{} port {}", swit, node.getPortId());
	}

	public void pushPacket(IPacket packet, IOFSwitch sw, OFBufferId bufferId, OFPort inPort, OFPort outPort,
			FloodlightContext cntx, boolean flush) {

		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();

		// set actions
		List<OFAction> actions = new ArrayList<>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(outPort).setMaxLen(Integer.MAX_VALUE).build());

		pob.setActions(actions);

		// set buffer_id, in_port
		pob.setBufferId(bufferId);
		OFMessageUtils.setInPort(pob, inPort);

		// set data - only if buffer_id == -1
		if (pob.getBufferId() == OFBufferId.NO_BUFFER) {
			if (packet == null) {
				log.error("BufferId is not set and packet data is null. " + "Cannot send packetOut. "
						+ "srcSwitch={} inPort={} outPort={}", new Object[] { sw, inPort, outPort });
				return;
			}
			byte[] packetData = packet.serialize();
			pob.setData(packetData);
		}

		sw.write(pob.build());
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
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
		l.add(IDeviceService.class);
		l.add(IRoutingService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IOFSwitchService.class);
		l.add(ITopologyService.class);

		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceDeviceManager = context.getServiceImpl(IDeviceService.class);
		deviceListener = new DeviceListenerImpl();

		serviceFloodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		serviceDeviceManager = context.getServiceImpl(IDeviceService.class);
		serviceRoutingEngine = context.getServiceImpl(IRoutingService.class);
		serviceLinkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
		serviceSwitch = context.getServiceImpl(IOFSwitchService.class);
		serviceTopology = context.getServiceImpl(ITopologyService.class);

		roundRobin = new RR();
		edgeSwitchSet = new ConcurrentSkipListSet<DatapathId>();

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceDeviceManager.addListener(this.deviceListener);
		serviceFloodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);

	}

	// RR

	protected class RR {
		// Key aux in RR map
		public class Key {
			private MacAddress srcMAC;
			private MacAddress dstMAC;

			public Key(MacAddress src2, MacAddress dst2) {
				super();
				this.srcMAC = src2;
				this.dstMAC = dst2;
			}
		}

		private Map<Key, ConcurrentLinkedDeque<Path>> knowPaths;

		public RR() {
			super();
			knowPaths = new HashMap<Key, ConcurrentLinkedDeque<Path>>();

		}

		/**
		 * Recebe origem/destino re retora o próximo caminho de uma lista circular
		 * 
		 * @param src
		 * @param dst
		 * @param dstIpv4
		 * @param srcIpv4
		 * @return
		 */
		public List<NodePortTuple> getNextPath(MacAddress src, MacAddress dst, IPv4Address srcIpv4,
				IPv4Address dstIpv4) {
			ConcurrentLinkedDeque<Path> paths = null;
			// Find in rrlist
			for (Entry<Key, ConcurrentLinkedDeque<Path>> entry : knowPaths.entrySet()) {
				if (src.equals(entry.getKey().srcMAC) && dst.equals(entry.getKey().dstMAC)) {
					paths = entry.getValue();
				}
			}

			SwitchPort srcSwPort = deviceListener.findSwitchPort(src);
			SwitchPort dstSwPort = deviceListener.findSwitchPort(dst);
			// se for null então pecisa criar um path list com a chave src/dst
			Path path = null;
			if (dstSwPort == null) {
				log.trace("Controlador não conhece o destino");
				return null;
			}
			if (paths == null) {
				// Pode ser a primeira vez que a rota esta sendo utilizada
				List<Path> pathList = serviceRoutingEngine.getPathsFast(srcSwPort.getNodeId(), dstSwPort.getNodeId(),
						serviceRoutingEngine.getMaxPathsToCompute());

				// siguinifica que o destino pertence ao mesmo switch que gerou o packet in
				log.trace("pathList: {}", pathList);
				paths = new ConcurrentLinkedDeque<Path>();
				paths.addAll(pathList);
				Key key = new Key(src, dst);
				knowPaths.put(key, paths);
			}

			if (paths == null) {
				log.trace("Não existe path entre src/dst, os dois podem pertencer ao mesmo switch");
				return null;
			}
			List<NodePortTuple> listNodes = new ArrayList<NodePortTuple>();

			log.info("Paths size {}", paths.size());
			String repitedNode = "";
			for (Path p : paths) {
				List<String> pathDesc = new ArrayList<>();
				for (NodePortTuple node : p.getPath()) {

					IOFSwitch swt = serviceSwitch.getSwitch(node.getNodeId());
					if(!repitedNode.equals(swt.getSwitchDescription().getDatapathDescription())) {
						pathDesc.add(swt.getSwitchDescription().getDatapathDescription());
						repitedNode = swt.getSwitchDescription().getDatapathDescription();
					}

				}
				log.info("Paths {}", pathDesc);

			}

			if (!paths.isEmpty()) {
				// log.trace("Não existe path entre src/dst, os dois podem pertencer ao mesmo
				// switch");
				// repassa o primeiro para o final da lista

				path = paths.removeFirst();
				paths.addLast(path);
				listNodes.addAll(path.getPath());
			}

			// Caso o src e dst estejam no mesmo switch o caminho é vazio, porém ainda é
			// necessário adcionar a porta
			listNodes.add(new NodePortTuple(dstSwPort.getNodeId(), dstSwPort.getPortId()));

			ArrayDeque<DatapathId> aux = new ArrayDeque<>();
			ArrayDeque<NodePortTuple> aux1 = new ArrayDeque<>();
			for (NodePortTuple node : listNodes) {
				if (aux.contains(node.getNodeId())) {
					aux.removeLast();
					aux1.removeLast();
				}
				aux.add(node.getNodeId());
				aux1.add(node);
			}

			listNodes.clear();
			listNodes.addAll(aux1);
			return listNodes;
		}

	}

	// IDeviceListener
	class DeviceListenerImpl implements IDeviceListener {
		protected ConcurrentHashMap<MacAddress, SwitchPort> knowMacAddress;
		protected ConcurrentHashMap<IPv4Address, SwitchPort> knowIpAddress;

		DeviceListenerImpl() {
			knowMacAddress = new ConcurrentHashMap<MacAddress, SwitchPort>();
			knowIpAddress = new ConcurrentHashMap<IPv4Address, SwitchPort>();
		}

		private IDevice findDevice(MacAddress src) {
			for (IDevice device : serviceDeviceManager.getAllDevices()) {
				if (device.getMACAddress().equals(src)) {
					return device;
				}
			}
			return null;
		}

		private IDevice findDevice(IPv4Address src) {
			for (IDevice device : serviceDeviceManager.getAllDevices()) {
				for (IPv4Address ipv4 : device.getIPv4Addresses()) {
					if (ipv4.equals(src)) {
						return device;
					}
				}
			}
			return null;
		}

		public SwitchPort findSwitchPort(MacAddress src) {
			if (knowMacAddress != null) {
				for (Entry<MacAddress, SwitchPort> entry : knowMacAddress.entrySet()) {
					if (entry.getKey().equals(src)) {
						return entry.getValue();
					}
				}
			}
			return null;
		}

		public SwitchPort findSwitchPort(IPv4Address src) {

			for (Entry<IPv4Address, SwitchPort> entry : knowIpAddress.entrySet()) {
				if (entry.getKey().equals(src)) {
					return entry.getValue();
				}
			}
			return null;
		}

		@Override
		public void deviceAdded(IDevice device) {
			if (!knowMacAddress.containsKey(device.getMACAddress())) {
				for (SwitchPort attach : device.getAttachmentPoints()) {
					knowMacAddress.put(device.getMACAddress(), new SwitchPort(attach.getNodeId(), attach.getPortId()));
					break;// TODO modificar caso necessário mais switches em um único host
				}
			}

			for (IPv4Address ip : device.getIPv4Addresses()) {
				if (!knowIpAddress.containsKey(ip)) {
					for (SwitchPort attach : device.getAttachmentPoints()) {
						knowIpAddress.put(ip, new SwitchPort(attach.getNodeId(), attach.getPortId()));
						break;
					}
				}
				// break; //TODO: Modificar caso necessário mais de um IP por host
			}
			log.trace("Device add {}", device);

		}

		@Override
		public void deviceRemoved(IDevice device) {
			log.trace("DEVICE REMOVED {}", device);
			knowMacAddress.remove(device.getMACAddress());

			for (IPv4Address ip : device.getIPv4Addresses()) {
				knowIpAddress.remove(ip);

			}
		}

		@Override
		public void deviceIPV4AddrChanged(IDevice device) {
			// ignore
		}

		@Override
		public void deviceIPV6AddrChanged(IDevice device) {
			// ignore
		}

		@Override
		public void deviceMoved(IDevice device) {
			// ignore
		}

		@Override
		public void deviceVlanChanged(IDevice device) {
			// ignore
		}

		@Override
		public String getName() {
			return DeviceListenerImpl.class.getName();
		}

		@Override
		public boolean isCallbackOrderingPrereq(String type, String name) {
			return false;
		}

		@Override
		public boolean isCallbackOrderingPostreq(String type, String name) {
			// We need to go before forwarding
			return false;
		}
	}

}
// TODO:{1}. Verificar a necessidade de implementar switch listener para remover dos caches de caminhos caso um switch seja removido

// TODO:{2}. Verificar a necessidade de tratar fluxos que expiraram

// TODO:{3}. 
