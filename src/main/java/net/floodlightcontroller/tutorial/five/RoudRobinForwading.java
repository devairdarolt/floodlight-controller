/**
 * Baseado no forwarding
 * 
 * Roteamento com 
 */
package net.floodlightcontroller.tutorial.five;

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
public class RoudRobinForwading implements IFloodlightModule, IOFMessageListener {
	public static final long COOKIE = 333;
	private static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	private static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	private static short FLOWMOD_PRIORITY = 100;

	protected static final Logger log = LoggerFactory.getLogger(RoudRobinForwading.class);

	// Dependencies

	private IRoutingService serviceRoutingEngine;
	private IDeviceService serviceDeviceManager;
	private ITopologyService serviceTopology;
	private IOFSwitchService serviceSwitch;
	private ILinkDiscoveryService serviceLinkDiscovery;
	IFloodlightProviderService serviceFloodlightProvider;

	// Internal stats
	private RR roundRobin;
	// Imp IDeviceListener
	protected DeviceListenerImpl deviceListener;
	protected Map<IDevice, SwitchPort> knowDevices;
	protected OFMessageDamper messageDamper;
	protected Set<IOFSwitch> edgeSwitchSet;
	@Override
	public String getName() {

		return RoudRobinForwading.class.getName();
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
		switch (msg.getType()) {
		case PACKET_IN:
			// TODO processPacketIn
			OFPacketIn packetIn = OFPacketIn.class.cast(msg);
			return processPacketIn(sw, packetIn, cntx);

		case FLOW_REMOVED:
			// TODO processFlowRemoved
			break;
		case ERROR:
			// TODO processError
			break;
		default:
			// TODO showMsg
			break;

		}
		return null;
	}

	private Command processPacketIn(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {

		
		// log.trace("Know Devices {}", knowDevices.keySet());
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		OFPort inPort = OFMessageUtils.getInPort(packetIn);
		log.trace("\n\n");
		log.trace("src {} dst {} - sw {}", srcMac, dstMac, sw.getId());
		// Packets in create before flowAdd completed/ talvez seja melhor devolver ao switch
		// quando isso acontece perdesse alguns números da sequencia
		if (!isEdgeSwitch(sw)) {			
			log.trace("Not switch edge: {}",sw);
			return Command.CONTINUE;
		}
		
		
		
		if (eth.getEtherType().equals(EthType.ARP)) {
			if (eth.isBroadcast() ) {
				processARPbroadcastOrMulticast(sw, packetIn, inPort);
				return Command.CONTINUE;
			}else if(eth.isMulticast()){
				return Command.CONTINUE;
			}
			// else process normal src/dst packets
		}

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MATCH -- Utiliza apenas MAC_SRC e MAC_DST
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_SRC, srcMac).setExact(MatchField.ETH_DST, dstMac);
		Match match = mb.build();

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// GET ROUTE -- RR
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		List<NodePortTuple> nodes = roundRobin.getNextPath(srcMac, dstMac);
		if (nodes == null) {
			log.trace("Não foi encontrado Path entre src e dst");
			if (isOnTheSameSwitch(srcMac, dstMac, sw)) {
				nodes = new ArrayList<>();
				for(Entry<IDevice, SwitchPort> entry: knowDevices.entrySet()) {
					if(entry.getKey().getMACAddress().equals(srcMac)) {						
						nodes.add(new NodePortTuple(entry.getValue().getNodeId(),entry.getValue().getPortId()));
					}
				}
			} else {
				return Command.CONTINUE;
			}

		}
		log.trace("Path {}", nodes);
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MAKE FLOW -- para cada node
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		Iterator<NodePortTuple> nodeItr = nodes.iterator();
		NodePortTuple outNodePort = null;

		while (nodeItr.hasNext()) {
			NodePortTuple node = nodeItr.next();// Link in
			if (node.getNodeId().equals(sw.getId())) {
				outNodePort = node;// store the first hop to create packet-out
			}
			log.info("{} >> {} addFlow {}",srcMac,dstMac, node);
			addFlow(sw, match, node);

		}

		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// PACKET OUT
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		/*
		 * OFFactory factory = sw.getOFFactory(); OFPacketOut.Builder packetOut =
		 * factory.buildPacketOut();
		 * 
		 * IPacket outPacket = new Ethernet().setSourceMACAddress(srcMac)
		 * .setDestinationMACAddress(dstMac).setEtherType(eth.getEtherType())
		 * .setVlanID(eth.getVlanID()).setPayload(eth.getPayload()); List<OFAction>
		 * actions = new ArrayList<>();
		 * actions.add(factory.actions().output(nodes.get(0).getPortId(),
		 * Integer.MAX_VALUE)); packetOut.setActions(actions);
		 * 
		 * packetOut.setData(outPacket.serialize());
		 */

		if(outNodePort!=null) {
			log.trace("outNodePort não pode ser null");
			log.trace("PacketOut {}{}", sw, outNodePort.getPortId());
			writePacketOutForPacketIn(sw, packetIn, outNodePort.getPortId());
		}

		return Command.CONTINUE;
	}

	private boolean isOnTheSameSwitch(MacAddress srcMac, MacAddress dstMac, IOFSwitch sw) {
		ArrayList<DatapathId> datapath = new ArrayList<>();
		DatapathId sw1 = null;
		DatapathId sw2 = null;
		for (Entry<IDevice, SwitchPort> entry : knowDevices.entrySet()) {
			if (entry.getKey().getMACAddress().equals(srcMac)) {
				sw1 = entry.getValue().getNodeId();
			}
			if (entry.getKey().getMACAddress().equals(dstMac)) {
				sw2 = entry.getValue().getNodeId();
			}
		}
		if(sw1.equals(sw2)) {
			return true;
		}
		return false;
	}

	private boolean isEdgeSwitch(IOFSwitch sw) {
		if(this.edgeSwitchSet==null || this.edgeSwitchSet.isEmpty()) {
			this.edgeSwitchSet = getEdgesSwitches();
		}
		for(IOFSwitch swt:this.edgeSwitchSet) {
			if(swt.getId().equals(sw.getId())) {
				return true;
			}
		}		
		return false;
	}

	private void processARPbroadcastOrMulticast(IOFSwitch sw, OFPacketIn packetIn, OFPort inPort) {
		Set<NodePortTuple> broadcastPorts = getBroadcastPorts();
		for (NodePortTuple node : broadcastPorts) {
			if (!(node.getNodeId().equals(sw.getId()) && node.getPortId().equals(inPort))) {
				writePacketOutForPacketIn(serviceSwitch.getSwitch(node.getNodeId()), packetIn, node.getPortId());
			}
		}
	}
	
	//Edge is switches conected to a host
	private Set<IOFSwitch> getEdgesSwitches() {
		Set<NodePortTuple> list = getBroadcastPorts();
		Set<IOFSwitch> swSet = new HashSet<>();
		swSet.addAll(edgeSwitchSet);
		if(swSet!= null && !swSet.isEmpty()) {
			return swSet;
		}else {
			for(NodePortTuple node:list) {
				IOFSwitch sw = serviceSwitch.getActiveSwitch(node.getNodeId());
				swSet.add(sw);
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
		for(Entry<DatapathId, IOFSwitch> entry:allSwitchMap.entrySet()) {
			log.info("sw {} ports {}",entry.getKey(),entry.getValue().getEnabledPortNumbers());
			for(OFPort port:entry.getValue().getEnabledPortNumbers()) {
				U.add(new NodePortTuple(entry.getKey(), port));
			}
		}
		
		
		/*
		 * // OLD get U Set<DatapathId> allswitches = serviceSwitch.getAllSwitchDpids();
		 * for (DatapathId sw : allswitches) { Set<OFPort> ports =
		 * serviceTopology.getPorts(sw); for (OFPort port : ports) { U.add(new
		 * NodePortTuple(sw, port)); } }
		 */
		
		// Todas as portas que não ~(switch-switch)
		for (NodePortTuple node : U) {
			if (!B.contains(node)) {
				A.add(node);
			}
		}
		log.trace("broadcast ports {}",A);
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
		flowBuilder.setIdleTimeout(this.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		flowBuilder.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
		flowBuilder.setBufferId(OFBufferId.NO_BUFFER);
		flowBuilder.setPriority(FLOWMOD_PRIORITY);
		flowBuilder.setOutPort(node.getPortId());
		Set<OFFlowModFlags> flags = new HashSet<OFFlowModFlags>();
		flags.add(OFFlowModFlags.SEND_FLOW_REM);// Flag para marcar o fluxo par ser removido quando o
												// idl-timeout ocorrer
		flowBuilder.setFlags(flags);

		// ACTIONS
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(node.getPortId()).setMaxLen(0xffFFffFF).build());

		// INSERT IN SWITCH OF PATH
		IOFSwitch swit = serviceSwitch.getSwitch(node.getNodeId());
		FlowModUtils.setActions(flowBuilder, actions, swit);
		swit.write(flowBuilder.build());
		// logger.info("Flow ADD node{} port {}", swit, node.getPortId());
	}

	private boolean processARPdst(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {
		log.trace("processARPdst");
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		ARP arpRequest = (ARP) eth.getPayload();

		MacAddress replySenderMac = null;
		IPv4Address replySenderIPv4 = null;

		// find mac of IPv4
		for (Entry<IDevice, SwitchPort> entry : knowDevices.entrySet()) {
			for (IPv4Address ipv4 : entry.getKey().getIPv4Addresses()) {
				if (ipv4.equals(arpRequest.getTargetProtocolAddress())) {
					replySenderMac = entry.getKey().getMACAddress();
					replySenderIPv4 = arpRequest.getTargetProtocolAddress();
				}
			}
		}
		// se não encontrar as informações para contruir o pacote (10.0.0.2 at
		// 00:00:00:02) então flood
		if (replySenderIPv4 == null || replySenderMac == null) {
			return false;
		}

		// generate proxy ARP reply
		IPacket arpReply = new Ethernet().setSourceMACAddress(replySenderMac)
				.setDestinationMACAddress(eth.getSourceMACAddress()).setEtherType(EthType.ARP)
				.setVlanID(eth.getVlanID()).setPriorityCode(eth.getPriorityCode())
				.setPayload(new ARP().setHardwareType(ARP.HW_TYPE_ETHERNET).setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte) 6).setProtocolAddressLength((byte) 4).setOpCode(ARP.OP_REPLY)
						.setSenderHardwareAddress(replySenderMac).setSenderProtocolAddress(replySenderIPv4)
						.setTargetHardwareAddress(eth.getSourceMACAddress())
						.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));

		// push ARP reply out
		pushPacket(arpReply, sw, OFBufferId.NO_BUFFER, OFPort.ANY,
				(packetIn.getVersion().compareTo(OFVersion.OF_12) < 0 ? packetIn.getInPort()
						: packetIn.getMatch().get(MatchField.IN_PORT)),
				cntx, true);
		return true;
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
		knowDevices = new ConcurrentHashMap<IDevice, SwitchPort>();
		edgeSwitchSet = new HashSet<IOFSwitch>();

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
			public MacAddress src;
			public MacAddress dst;

			public Key(MacAddress src2, MacAddress dst2) {
				super();
				this.src = src2;
				this.dst = dst2;
			}
		}

		private Map<Key, ConcurrentLinkedDeque<Path>> knowPaths;

		public RR() {
			super();
			knowPaths = new HashMap<Key, ConcurrentLinkedDeque<Path>>();

		}
		/**
		 * Recebe origem/destino re retora o próximo caminho de uma lista circular
		 * @param src
		 * @param dst
		 * @return
		 */
		public List<NodePortTuple> getNextPath(MacAddress src, MacAddress dst) {
			ConcurrentLinkedDeque<Path> paths = null;
			// Find in rrlist
			for (Entry<Key, ConcurrentLinkedDeque<Path>> entry : knowPaths.entrySet()) {
				if (src.equals(entry.getKey().src) && dst.equals(entry.getKey().dst)) {
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
				//Pode ser a primeira vez que a rota esta sendo utilizada
				List<Path> pathList = serviceRoutingEngine.getPathsFast(srcSwPort.getNodeId(), dstSwPort.getNodeId(),
						serviceRoutingEngine.getMaxPathsToCompute());

				// siguinifica que o destino pertence ao mesmo switch que gerou o packet in
				log.trace("pathList: {}",pathList);
				paths = new ConcurrentLinkedDeque<Path>();
				paths.addAll(pathList);
				Key key = new Key(src, dst);
				knowPaths.put(key, paths);
			}
			
			if(paths==null) {
				log.trace("Não existe path entre src/dst, os dois podem pertencer ao mesmo switch");
				return null;
			}
			List<NodePortTuple> listNodes = new ArrayList<NodePortTuple>();
			
			if(!paths.isEmpty()) {
				//log.trace("Não existe path entre src/dst, os dois podem pertencer ao mesmo switch");
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

		private IDevice findDevice(MacAddress src) {
			for (Entry<IDevice, SwitchPort> entry : knowDevices.entrySet()) {
				if (src.equals(entry.getKey().getMACAddress())) {
					return entry.getKey();
				}
			}
			return null;
		}
	}

	// IDeviceListener
	class DeviceListenerImpl implements IDeviceListener {
		// Auxiliares
		public SwitchPort findSwitchPort(MacAddress src) {
			Set<IDevice> devices = new HashSet<>();
			for(IDevice device:serviceDeviceManager.getAllDevices()) {
				devices.add(device);
			}
			
			
			for (Entry<IDevice, SwitchPort> entry : knowDevices.entrySet()) {
				if (src.equals(entry.getKey().getMACAddress())) {
					return entry.getValue();
				}
			}
			return null;
		}
		
		
		@Override
		public void deviceAdded(IDevice device) {
			log.trace("DEVICE ADD {}", device);
			List<IDevice> listToRemove = new ArrayList<>();
			SwitchPort newSwPort = null;
			for (Entry<IDevice, SwitchPort> entry : knowDevices.entrySet()) {
				if (entry.getKey().getMACAddress().equals(device.getMACAddress())) {
					boolean contains = true;
					newSwPort = new SwitchPort(entry.getValue().getNodeId(), entry.getValue().getPortId());
					listToRemove.add(entry.getKey());

				}
			}
			// se ja existe então remove para fazer o update
			for (IDevice element : listToRemove) {
				knowDevices.remove(element);
			}
			for (SwitchPort attachmentPoint : device.getAttachmentPoints()) {
				knowDevices.put(device, attachmentPoint);
				break;
			}
			
		}

		@Override
		public void deviceRemoved(IDevice device) {
			log.trace("DEVICE REMOVED {}", device);
			IDevice key = null;
			for(Entry<IDevice, SwitchPort> entry:knowDevices.entrySet()) {
				if(device.getMACAddress().equals(entry.getKey().getMACAddress())) {
					key = entry.getKey();					
				}
			}
			if(key!=null) {
				knowDevices.remove(key);
			}			

		}

		@Override
		public void deviceIPV4AddrChanged(IDevice device) {
			// add or remove entry as gateway
			deviceAdded(device);
		}

		@Override
		public void deviceIPV6AddrChanged(IDevice device) {
			// TODO
			// logger.debug("IPv6 address change not handled in VirtualNetworkFilter.
			// Device: {}", device.toString());
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
