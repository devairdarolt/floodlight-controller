package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

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
import org.projectfloodlight.openflow.types.*;
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
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageUtils;

@SuppressWarnings("unused")
public class Forward3 implements IOFMessageListener, IFloodlightModule {
	public static final long COOKIE = 200;
	private static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	private static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	private static short FLOWMOD_PRIORITY = 100;
	// dependencies
	private IFloodlightProviderService serviceFloodlightProvider;
	private IRoutingService serviceRoutingEngine;
	private IOFSwitchService serviceSwitch;
	private IDeviceService serviceDeviceManager;
	private ITopologyService serviceTopology;
	private ILinkDiscoveryService serviceLink;
	// Uteis
	private Map<IOFSwitch, Set<OFBufferId>> mapSwitchBufferId;
	private Map<MacAddress, Map<IOFSwitch, OFPort>> gateWays;
	private Set<String> listaMACs;
	private static Logger logger;

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
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
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {

		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		lista.add(IRoutingService.class);
		lista.add(IOFSwitchService.class);
		lista.add(IDeviceService.class);
		lista.add(ITopologyService.class);
		lista.add(ILinkDiscoveryService.class);
		return lista;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceFloodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		serviceRoutingEngine = context.getServiceImpl(IRoutingService.class);
		serviceSwitch = context.getServiceImpl(IOFSwitchService.class);
		serviceDeviceManager = context.getServiceImpl(IDeviceService.class);
		serviceTopology = context.getServiceImpl(ITopologyService.class);
		serviceLink = context.getServiceImpl(ILinkDiscoveryService.class);

		mapSwitchBufferId = new ConcurrentHashMap<IOFSwitch, Set<OFBufferId>>();
		gateWays = new ConcurrentHashMap<MacAddress, Map<IOFSwitch, OFPort>>();
		listaMACs = new ConcurrentSkipListSet<String>();
		logger = LoggerFactory.getLogger(this.getClass());
		logger.info("{} init", this.getClass().getSimpleName());

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceFloodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.EXPERIMENTER, this);
		logger.info("{} startup", this.getClass().getSimpleName());
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			mapGateways(sw, OFPacketIn.class.cast(msg), cntx);
			return processPacketInRoute(sw, OFPacketIn.class.cast(msg), cntx);
		// return processPacketIn(sw, OFPacketIn.class.cast(msg), cntx);
		case FLOW_REMOVED:
			logger.info("FLOW_REMOVED recebido de {}", sw);
			break;
		case ERROR:
			logger.info("ERRO recebido de switch {}: {}", sw, msg);
			break;
		default:
			logger.info("Mensagem de inesperada de switch {}: {} {}", sw, msg);
			break;
		}

		return Command.CONTINUE; // Comando para que a menssagem constinue sendo processada por outros listners
	}

	private Command processPacketInRoute(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {
		boolean exe = false;
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();

		if (eth.getEtherType().equals(EthType.ARP) || dstMac.isMulticast() || dstMac.isBroadcast()) {
			routerARP(sw, packetIn, cntx);
			return Command.CONTINUE;
		}

		/**
		 * Neste momento o controlador ja conhece origem e destino, então é possível
		 * criar um fluxo para tratar os demais pacotes
		 */
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MATCH -- Utiliza apenas MAC_SRC e MAC_DST
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_SRC, srcMac).setExact(MatchField.ETH_DST, dstMac);
		Match match = mb.build();
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// GET ROUTE --
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		IDevice srcDevice = serviceDeviceManager.fcStore.get(cntx, serviceDeviceManager.CONTEXT_SRC_DEVICE);
		IDevice dstDevice = serviceDeviceManager.fcStore.get(cntx, serviceDeviceManager.CONTEXT_DST_DEVICE);
		if (srcDevice == null || dstDevice == null) {
			logger.info("O controlador não conhece os dispositivos a src{} e dst{}", srcDevice, dstDevice);
			return Command.CONTINUE;
		}
		SwitchPort[] srcAttachPoints = srcDevice.getAttachmentPoints();
		SwitchPort[] dstAttachPoints = dstDevice.getAttachmentPoints();

		if (srcAttachPoints == null || dstAttachPoints == null) {
			logger.info("O controlador não conhece os switches ligados a src{} e dst{}", srcDevice, dstDevice);
			return Command.CONTINUE;
		}

		DatapathId srcDataPath = srcAttachPoints[0].getNodeId();
		IOFSwitch srcSwitch = serviceSwitch.getSwitch(srcDataPath);
		OFPort srcPort = srcAttachPoints[0].getPortId();
		DatapathId dstDataPath = dstAttachPoints[0].getNodeId();
		IOFSwitch dstSwitch = serviceSwitch.getSwitch(dstDataPath);
		OFPort dstPort = dstAttachPoints[0].getPortId();

		Path shortestPath = serviceRoutingEngine.getPath(srcDataPath, srcPort, dstDataPath, dstPort);

		logger.info("Path {}", shortestPath);
		Set<DatapathId> added = new HashSet<DatapathId>();
		List<NodePortTuple> nodePaths = new ArrayList<NodePortTuple>();
		nodePaths.addAll(shortestPath.getPath());
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MAKE FLOW -- para cada node de route
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		NodePortTuple dstNode = new NodePortTuple(dstDataPath, dstPort);
		if (nodePaths.isEmpty()) {
			nodePaths.add(dstNode);
		}

		// nodePaths = {sw1:in, sw1:out, sw2:in, sw2:out}
		Iterator<NodePortTuple> nodePathsItr = nodePaths.iterator();
		while (nodePathsItr.hasNext()) {

			NodePortTuple node_port_in = nodePathsItr.next();// Link in/back

			NodePortTuple node_port_out = nodePathsItr.next();// Link in/back
			logger.info("addFlow node [{}]", node_port_out);
			addFlow(sw, match, node_port_out);

		}
		// Agora que o fluxo foi inserido... devolve o pacote ao switch que gerou o
		// packet in
		OFMessageUtils.writePacketOutForPacketIn(sw, packetIn, nodePaths.get(1).getPortId());
		return Command.CONTINUE;
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
				logger.error("BufferId is not set and packet data is null. " + "Cannot send packetOut. "
						+ "srcSwitch={} inPort={} outPort={}", new Object[] { sw, inPort, outPort });
				return;
			}
			byte[] packetData = packet.serialize();
			pob.setData(packetData);
		}

		sw.write(pob.build());
	}

	private boolean routerARP(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		boolean proxyArp = true;// do proxy arp reply
		IDevice srcDevice = serviceDeviceManager.fcStore.get(cntx, serviceDeviceManager.CONTEXT_SRC_DEVICE);
		
		//Tenta fazer o ARP Reply caso não consiga faz o flood
		if (!proxyArpReply(sw, packetIn, cntx)) {

			logger.info("Proxi arp fails. Flood ARP...");
			OFMessageUtils.writePacketOutForPacketIn(sw, packetIn, OFPort.FLOOD);
		}

		return true;
	}

	protected boolean proxyArpReply(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		logger.info("Proxy ARP Reply");

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		// retrieve original arp to determine host configured gw IP address
		if (!(eth.getPayload() instanceof ARP))
			return false;
		ARP arpRequest = (ARP) eth.getPayload();
		// have to do proxy arp reply since at this point we cannot determine the
		// requesting application type

		// Temos o endereço IP, então encontraremos o MAC desse IP

		MacAddress replySenderMac = null;
		IPv4Address replySenderIPv4 = null;
		for (IDevice device : serviceDeviceManager.getAllDevices()) {
			for (IPv4Address ip : device.getIPv4Addresses()) {
				if (ip.equals(arpRequest.getTargetProtocolAddress())) {
					replySenderMac = device.getMACAddress();
					replySenderIPv4 = ip;
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
				(pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort()
						: pi.getMatch().get(MatchField.IN_PORT)),
				cntx, true);

		return true;

	}

	private void mapGateways(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		//
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		if (!gateWays.containsKey(srcMac)) {
			gateWays.put(srcMac, new ConcurrentHashMap<IOFSwitch, OFPort>());
			if (gateWays.get(srcMac).size() == 0) {
				OFPort port = OFMessageUtils.getInPort(pi);
				gateWays.get(srcMac).put(sw, port);

				logger.info("MAC {} visto pela primeira vez em {}", srcMac, sw);
			}
		}

	}

}
