/**
 * Implementação baseada no estudo do forwarding.java
 */
package net.floodlightcontroller.tutorial.five;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.Masked;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U16;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.python.google.common.collect.ImmutableList;

import com.google.common.collect.Maps;

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
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IGatewayService;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingDecisionChangedListener;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.L3RoutingManager;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.routing.VirtualGatewayInstance;
import net.floodlightcontroller.routing.VirtualGatewayInterface;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.tutorial.five.Forward4.DeviceListenerImpl;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFDPAUtils;
import net.floodlightcontroller.util.OFMessageUtils;
import net.floodlightcontroller.util.OFPortMode;
import net.floodlightcontroller.util.OFPortModeTuple;
import net.floodlightcontroller.util.ParseUtils;

public class Forward4 extends ForwardingBase implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener,
		IRoutingDecisionChangedListener, IGatewayService {

	private static final short FLOWSET_BITS = 28;
	private static final short DECISION_SHIFT = 0;
	private static final long FLOWSET_MAX = (long) (Math.pow(2, FLOWSET_BITS) - 1);
	private static final short DECISION_BITS = 24;
	protected static final short FLOWSET_SHIFT = DECISION_BITS;
	private static final long FLOWSET_MASK = ((1L << FLOWSET_BITS) - 1) << FLOWSET_SHIFT;
	private static final long DECISION_MASK = ((1L << DECISION_BITS) - 1) << DECISION_SHIFT;

	protected static FlowSetIdRegistry flowSetIdRegistry;
	private static L3RoutingManager l3manager;
	private Map<OFPacketIn, Ethernet> l3cache;
	private DeviceListenerImpl deviceListener;

	@Override
	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx) {

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		OFPort inPort = OFMessageUtils.getInPort(pi);
		NodePortTuple npt = new NodePortTuple(sw.getId(), inPort);
		log.info("Processando PACKT_IN para >>> src[" + eth.getSourceMACAddress().toString() + "] dst["
				+ eth.getDestinationMACAddress().toString() + "]");

		if (decision != null) {
			log.info("Forwarding decision={} was made for PacketIn={}", decision.getRoutingAction().toString(), pi);
			switch (decision.getRoutingAction()) {
			case NONE:
				// don't do anything
				return Command.CONTINUE;

			case FORWARD_OR_FLOOD:
			case FORWARD:
				doL2ForwardFlow(sw, pi, decision, cntx, false);
				return Command.CONTINUE;

			case MULTICAST:
				// treat as broadcast
				doFlood(sw, pi, decision, cntx);
				return Command.CONTINUE;
			case DROP:
				doDropFlow(sw, pi, decision, cntx);
				return Command.CONTINUE;
			default:
				log.error("Unexpected decision made for this packet-in={}", pi, decision.getRoutingAction());
				return Command.CONTINUE;
			}

		} else { // No routing decision was found

			switch (determineRoutingType()) {
			case FORWARDING:
				// L2 Forward to destination or flood if bcast or mcast

				log.info("Nenhuma decisão encontrada, Utilizando FORWARDING para L2 forwarding", pi);
				doL2Forwarding(eth, sw, pi, decision, cntx);
				break;
			case ROUTING:
				log.info("Nenhuma decisão encontrada, Utilizando ROUTING para ", pi);
				Optional<VirtualGatewayInstance> instance = getGatewayInstance(sw.getId());

				/* FIXME não seria o contrário? se a instancia esta presente utilize ela */
				if (!instance.isPresent()) {
					instance = getGatewayInstance(npt);
				}
				if (!instance.isPresent()) {
					log.info("Could not locate virtual gateway instance for DPID {}, port {}", sw.getId(), inPort);
					break;
				}
				doL3Routing(eth, sw, pi, decision, cntx, instance.get(), inPort);
				break;
			
			default:
				log.error("Unexpected routing behavior for this packet-in={} on switch {}", pi, sw.getId());
				break;

			}
		}

		return Command.CONTINUE;
	}

	/**
	 * Faz o roteamento utilizando a camada L3
	 * 
	 * @param eth
	 * @param sw
	 * @param pi
	 * @param decision
	 * @param cntx
	 * @param gatewayInstance
	 * @param inPort
	 */
	protected void doL3Routing(Ethernet eth, IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx, @Nonnull VirtualGatewayInstance gatewayInstance, OFPort inPort) {
		log.info("doL3Routing");
		MacAddress gatewayMac = gatewayInstance.getGatewayMac();
		if (eth.getEtherType() == EthType.IPv4) {
			IPv4Address intfIpAddress = findInterfaceIP(gatewayInstance,
					((IPv4) eth.getPayload()).getDestinationAddress());
			if (intfIpAddress == null) {
				log.debug("Can not locate corresponding interface for gateway {}, check its interface configuration",
						gatewayInstance.getName());
				return;
			}
		}
		if (isBroadcastOrMulticast(eth)) {
			// When cross-subnet, host send ARP request to gateway. Gateway need to generate
			// ARP response to host
			if (eth.getEtherType() == EthType.ARP && ((ARP) eth.getPayload()).getOpCode().equals(ARP.OP_REQUEST)
					&& gatewayInstance.isAGatewayIntf(((ARP) eth.getPayload()).getTargetProtocolAddress())) {
				IPacket arpReply = gatewayArpReply(cntx, gatewayMac);
				pushArpReply(arpReply, sw, OFBufferId.NO_BUFFER, OFPort.ANY, inPort);
				log.info("Virtual gateway pushing ARP reply message back to source host");

			} else {
				log.info("Não foi possível gerar um ARP reply, fazendo um flood");
				doFlood(sw, pi, decision, cntx);// Precisa apenas da camada 2 ou das conexão físicas
			}
		} else {
			// This also includes L2 forwarding
			doL3ForwardFlow(sw, pi, decision, cntx, gatewayInstance, false);
		}
	}

	protected void doL3ForwardFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx,
			VirtualGatewayInstance gateway, boolean requestFlowRemovedNotifn) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		OFPort srcPort = OFMessageUtils.getInPort(pi);
		log.info("doL3ForwardFlow:{} to packet-in:{}", sw, pi);
		MacAddress virtualGatewayMac = gateway.getGatewayMac();
		DatapathId srcSw = sw.getId();
		IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
		IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);

		if (dstDevice == null) {
			// Try one more time to retrieve dst device
			if (eth.getEtherType() == EthType.IPv4 && eth.getDestinationMACAddress().equals(virtualGatewayMac)) {
				dstDevice = findDstDeviceForL3Routing(((IPv4) eth.getPayload()).getDestinationAddress());
			}
			if (dstDevice == null) {
				// L3 traffic at 1st hop, virtual gateway creates & floods ARP to learn
				// destination device
				if (eth.getEtherType() == EthType.IPv4 && eth.getDestinationMACAddress().equals(virtualGatewayMac)) {
					log.info("Virtual gateway creates and flood arp request packet for destination host");
					doL3Flood(gateway, sw, pi, cntx);
					l3cache.put(pi, eth);
					log.info("Add new packet-in associate with packet source {} and destination {} to cache",
							((IPv4) eth.getPayload()).getSourceAddress(),
							((IPv4) eth.getPayload()).getDestinationAddress());
				} else {// Normal l2 forward
					log.info("Destination device unknown. Flooding packet");
					doFlood(sw, pi, decision, cntx);
				}

				return;
			}
		}

		if (srcDevice == null) {
			log.error("No device entry found for source device. Is the device manager running? If so, report bug.");
			return;
		}

		/* Some physical switches partially support or do not support ARP flows */
		if (FLOOD_ALL_ARP_PACKETS && IFloodlightProviderService.bcStore
				.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD).getEtherType() == EthType.ARP) {
			log.info("ARP flows disabled in Forwarding. Flooding ARP packet");
			doFlood(sw, pi, decision, cntx);
			return;
		}
		
		/*
		 * This packet-in is from a switch in the path before its flow was installed
		 * along the path
		 */
		if (!topologyService.isEdge(srcSw, srcPort) && !eth.getDestinationMACAddress().equals(virtualGatewayMac)) {
			doFlood(sw, pi, decision, cntx);
			return;
		}
		
		/*
		 * Search for the true attachment point. The true AP is not an endpoint of a
		 * link. It is a switch port w/o an associated link. Note this does not
		 * necessarily hold true for devices that 'live' between OpenFlow islands.
		 *
		 * TODO Account for the case where a device is actually attached between islands
		 * (possibly on a non-OF switch in between two OpenFlow switches).
		 */
		SwitchPort dstAp = null;
		for (SwitchPort ap : dstDevice.getAttachmentPoints()) {
			if (topologyService.isEdge(ap.getNodeId(), ap.getPortId())) {
				dstAp = ap;
				break;
			}
		}
		
		
		/*
		 * This should only happen (perhaps) when the controller is actively learning a
		 * new topology and hasn't discovered all links yet, or a switch was in
		 * standalone mode and the packet in question was captured in flight on the dst
		 * point of a link.
		 */
		if (dstAp == null) {
			if (eth.getDestinationMACAddress().equals(virtualGatewayMac)) { // Try L3 Flood again
				log.debug("Virtual gateway creates arp request packet to destination host and flooding");
				doL3Flood(gateway, sw, pi, cntx);
			} else { // Try L2 Flood again
				log.debug("Could not locate edge attachment point for destination device {}. Flooding packet");
				doFlood(sw, pi, decision, cntx);
			}
			return;
		}
		
		/* Validate that the source and destination are not on the same switch port */
		if (sw.getId().equals(dstAp.getNodeId()) && srcPort.equals(dstAp.getPortId())) {
			log.debug("Both source and destination are on the same switch/port {}/{}. Dropping packet", sw.toString(),
					srcPort);
			return;
		}
		
		// All edge cases excluded, consider adding L3 logic below
				U64 flowSetId = flowSetIdRegistry.generateFlowSetId();
				U64 cookie = makeForwardingCookie(decision, flowSetId);
				Path path = routingEngineService.getPath(srcSw, srcPort, dstAp.getNodeId(), dstAp.getPortId());

				if (!eth.getDestinationMACAddress().equals(virtualGatewayMac)) { // Normal L2 forwarding
					Match m = createMatchFromPacket(sw, srcPort, pi, cntx);

					if (!path.getPath().isEmpty()) {
						if (log.isDebugEnabled()) {
							log.debug("pushRoute inPort={} route={} " + "destination={}:{}",
									new Object[] { srcPort, path, dstAp.getNodeId(), dstAp.getPortId() });
							log.debug("Creating flow rules on the route, match rule: {}", m);
						}

						pushRoute(path, m, pi, sw.getId(), cookie, cntx, requestFlowRemovedNotifn, OFFlowModCommand.ADD, false);

						/*
						 * Register this flowset with ingress and egress ports for link down flow
						 * removal. This is done after we push the path as it is blocking.
						 */
						for (NodePortTuple npt : path.getPath()) {
							flowSetIdRegistry.registerFlowSetId(npt, flowSetId);
						}

					} /* else no path was found */
				} else { // L3 Routing
					boolean packetOutSent = sendPacketToLastHop(eth, dstDevice);

					// L3 rewrite on first hop (in bi-direction)
					IOFSwitch firstHop = switchService.getSwitch(srcSw);
					Match match = createMatchFromPacket(firstHop, srcPort, pi, cntx);

					if (!path.getPath().isEmpty()) {
						log.debug("L3 path is {}", path.getPath());
					}

					OFPort outPort = path.getPath().get(path.getPath().size() - 1).getPortId();

					buildRewriteFlows(pi, match, srcSw, outPort, cookie, virtualGatewayMac, dstDevice.getMACAddress(),
							requestFlowRemovedNotifn);

					// Remove first hop, push routes as normal in the middle
					Path newPath = getNewPath(path);
					pushRoute(newPath, match, pi, sw.getId(), cookie, cntx, requestFlowRemovedNotifn, OFFlowModCommand.ADD,
							packetOutSent);

					/* Register flow sets */
					for (NodePortTuple npt : path.getPath()) {
						flowSetIdRegistry.registerFlowSetId(npt, flowSetId);
					}

				}
	}
	private Path getNewPath(Path oldPath) {
		oldPath.getPath().remove(oldPath.getPath().get(oldPath.getPath().size() - 1));
		oldPath.getPath().remove(oldPath.getPath().get(oldPath.getPath().size() - 1));
		return oldPath;
	}
	protected void buildRewriteFlows(@Nonnull OFPacketIn pi, @Nonnull Match match, @Nonnull DatapathId sw,
			@Nonnull OFPort outPort, @Nonnull U64 cookie, @Nonnull MacAddress gatewayMac, @Nonnull MacAddress hostMac,
			boolean requestFlowRemovedNotification) {
		OFFactory factory = switchService.getSwitch(sw).getOFFactory();
		OFOxms oxms = factory.oxms();
		List<OFAction> actions = new ArrayList<>();
		OFFlowAdd.Builder flowAdd = factory.buildFlowAdd();

		flowAdd.setXid(pi.getXid()).setBufferId(OFBufferId.NO_BUFFER).setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
				.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT).setBufferId(OFBufferId.NO_BUFFER).setCookie(cookie)
				.setOutPort(outPort).setPriority(FLOWMOD_DEFAULT_PRIORITY).setMatch(match);

		if (FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG || requestFlowRemovedNotification) {
			Set<OFFlowModFlags> flags = new HashSet<>();
			flags.add(OFFlowModFlags.SEND_FLOW_REM);
			flowAdd.setFlags(flags);
		}

		OFVersion switchVersion = switchService.getSwitch(sw).getOFFactory().getVersion();
		switch (switchVersion) {
		case OF_10:
		case OF_11:
			actions.add(factory.actions().setDlSrc(gatewayMac));
			actions.add(factory.actions().setDlDst(hostMac));
			break;

		case OF_12:
		case OF_13:
		case OF_14:
		case OF_15:
			actions.add(factory.actions().setField(oxms.ethSrc(gatewayMac)));
			actions.add(factory.actions().setField(oxms.ethDst(hostMac)));
			break;

		default:
			break;

		}

		actions.add(factory.actions().output(outPort, Integer.MAX_VALUE));
		flowAdd.setActions(actions);

		if (log.isTraceEnabled()) {
			log.trace("Pushing flowmod with srcMac={} dstMac={} " + "sw={} inPort={} outPort={}",
					new Object[] { gatewayMac, hostMac, sw, flowAdd.getMatch().get(MatchField.IN_PORT), outPort });
		}

		messageDamper.write(switchService.getSwitch(sw), flowAdd.build());
		return;
	}
	protected void doL3Flood(VirtualGatewayInstance gateway, IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		log.info("doL3Flood");

		MacAddress gatewayMac = gateway.getGatewayMac();
		// generate ARP request to destination host
		IPv4Address dstIP = ((IPv4) eth.getPayload()).getDestinationAddress();
		IPv4Address intfIpAddress = findInterfaceIP(gateway, dstIP);

		// Set src MAC to virtual gateway MAC, set dst MAC to broadcast
		IPacket arpPacket = new Ethernet().setSourceMACAddress(gatewayMac)
				.setDestinationMACAddress(MacAddress.BROADCAST).setEtherType(EthType.ARP).setVlanID(eth.getVlanID())
				.setPriorityCode(eth.getPriorityCode())
				.setPayload(new ARP().setHardwareType(ARP.HW_TYPE_ETHERNET).setProtocolType(ARP.PROTO_TYPE_IP)
						.setOpCode(ARP.OP_REQUEST).setHardwareAddressLength((byte) 6).setProtocolAddressLength((byte) 4)
						.setSenderHardwareAddress(gatewayMac).setSenderProtocolAddress(intfIpAddress)
						.setTargetHardwareAddress(MacAddress.BROADCAST)
						.setTargetProtocolAddress(((IPv4) eth.getPayload()).getDestinationAddress()));
		byte[] data = arpPacket.serialize();
		OFPort inPort = OFMessageUtils.getInPort(pi);
		OFFactory factory = sw.getOFFactory();
		OFPacketOut.Builder packetOut = factory.buildPacketOut();
		// Set Actions
		List<OFAction> actions = new ArrayList<>();
		Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId());
		if (broadcastPorts.isEmpty()) {
			log.debug("No broadcast ports found. Using FLOOD output action");
			broadcastPorts = Collections.singleton(OFPort.FLOOD);
		}
		for (OFPort p : broadcastPorts) {
			if (p.equals(inPort))
				continue;
			actions.add(factory.actions().output(p, Integer.MAX_VALUE));
		}
		packetOut.setActions(actions);
		// set buffer-id, in-port and packet-data based on packet-in
		packetOut.setBufferId(OFBufferId.NO_BUFFER);
		OFMessageUtils.setInPort(packetOut, inPort);
		packetOut.setData(data);
		log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
				new Object[] { sw, pi, packetOut.build() });
		messageDamper.write(sw, packetOut.build());
		return;
	}

	private IDevice findDstDeviceForL3Routing(IPv4Address dstIP) {
		// Fetch all known devices
		Collection<? extends IDevice> allDevices = deviceManagerService.getAllDevices();
		IDevice dstDevice = null;
		for (IDevice d : allDevices) {
			// Casos em que um host tenha muitos IPv4, exemplo: muitas VMs
			for (int i = 0; i < d.getIPv4Addresses().length; ++i) {
				// verifica todos os ips de todas as vms do host
				if (d.getIPv4Addresses()[i].equals(dstIP)) {
					dstDevice = d;
					break;
				}
			}
		}
		return dstDevice;
	}

	public void pushArpReply(IPacket packet, IOFSwitch sw, OFBufferId bufferId, OFPort inPort, OFPort outPort) {
		log.trace("Push ARP PacketOut srcSwitch={} inPort={} outPort={}", new Object[] { sw, inPort, outPort });
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		List<OFAction> actions = new ArrayList<>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(outPort).setMaxLen(Integer.MAX_VALUE).build());
		pob.setActions(actions);
		pob.setBufferId(bufferId);
		OFMessageUtils.setInPort(pob, inPort);
		if (pob.getBufferId() == OFBufferId.NO_BUFFER) {
			if (packet != null) {
				byte[] pktData = packet.serialize();
				pob.setData(pktData); // Copia os dados de packet para reply
			} else {
				log.error("BufferId is not set and packet data is null. " + "Cannot send packetOut. "
						+ "srcSwitch={} inPort={} outPort={}", new Object[] { sw, inPort, outPort });
				return;
			}

		}
		sw.write(pob.build());
	}

	public IPacket gatewayArpReply(FloodlightContext cntx, MacAddress gatewayMac) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		ARP arpRequest = (ARP) eth.getPayload();
		IPacket arpReply = new Ethernet().setSourceMACAddress(gatewayMac)
				.setDestinationMACAddress(eth.getSourceMACAddress()).setEtherType(EthType.ARP)
				.setVlanID(eth.getVlanID()).setPriorityCode(eth.getPriorityCode())
				.setPayload(new ARP().setHardwareType(ARP.HW_TYPE_ETHERNET).setProtocolType(ARP.PROTO_TYPE_IP)
						.setOpCode(ARP.OP_REPLY).setHardwareAddressLength((byte) 6).setProtocolAddressLength((byte) 4)
						.setSenderHardwareAddress(gatewayMac)
						.setSenderProtocolAddress(arpRequest.getTargetProtocolAddress())
						.setTargetHardwareAddress(eth.getSourceMACAddress())
						.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		// generate ARP reply to host
		log.info("Gerando um ARP Reply({}) para o ARP request({})", arpReply, arpRequest);
		return arpReply;
	}

	public IPv4Address findInterfaceIP(VirtualGatewayInstance gateway, IPv4Address dstIP) {
		Optional<VirtualGatewayInterface> intf = gateway.findGatewayInft(dstIP);
		if (intf.isPresent()) {
			return intf.get().getIp();
		} else {
			return null;
		}
	}

	/**
	 * Faz o forward apenas utilizando L2
	 * 
	 * @param eth
	 * @param sw
	 * @param pi
	 * @param decision
	 * @param cntx
	 */
	protected void doL2Forwarding(Ethernet eth, IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
			FloodlightContext cntx) {
		log.info("doL2Forwarding");
		if (isBroadcastOrMulticast(eth)) {
			doFlood(sw, pi, decision, cntx);
		} else {
			doL2ForwardFlow(sw, pi, decision, cntx, false);
		}
	}

	private boolean isBroadcastOrMulticast(Ethernet eth) {
		return eth.isBroadcast() || eth.isMulticast();
	}

	/**
	 * FIXME tentar alterar comparar routing/fowrading
	 * 
	 * @return
	 */
	private IRoutingService.RoutingType determineRoutingType() {
		if (routingEngineService.isL3RoutingEnabled()) {
			return IRoutingService.RoutingType.ROUTING;
		} else {
			return IRoutingService.RoutingType.FORWARDING;
		}
	}

	/**
	 * Insere regras de fluxo para que o switch faça o drop dos pacotes
	 * 
	 * @param sw
	 * @param pi
	 * @param decision
	 * @param cntx
	 */
	protected void doDropFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		OFPort inPort = OFMessageUtils.getInPort(pi);
		Match m = createMatchFromPacket(sw, inPort, pi, cntx);
		OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd();
		List<OFAction> actions = new ArrayList<>(); // set no action to drop
		U64 flowSetId = flowSetIdRegistry.generateFlowSetId();
		U64 cookie = makeForwardingCookie(decision, flowSetId);

		/* If link goes down, we'll remember to remove this flow */
		if (!m.isFullyWildcarded(MatchField.IN_PORT)) {
			flowSetIdRegistry.registerFlowSetId(new NodePortTuple(sw.getId(), m.get(MatchField.IN_PORT)), flowSetId);
		}
		fmb.setCookie(cookie).setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT).setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
				.setBufferId(OFBufferId.NO_BUFFER).setMatch(m).setPriority(FLOWMOD_DEFAULT_PRIORITY);

		FlowModUtils.setActions(fmb, actions, sw);

		/* Configure for particular switch pipeline */
		if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) != 0) {
			fmb.setTableId(FLOWMOD_DEFAULT_TABLE_ID);
		}

		if (log.isDebugEnabled()) {
			log.debug("write drop flow-mod sw={} match={} flow-mod={}", new Object[] { sw, m, fmb.build() });
		}
		boolean dampened = messageDamper.write(sw, fmb.build());
		log.debug("OFMessage dampened: {}", dampened);
	}

	/**
	 * FORWARD_OR_FLOOD/FORWARD
	 * 
	 * Funciona da seguinte forma, caso não tenha informações o suficiente faz o
	 * doFlood(), se tiver informações de origem e destino insere um fluxo para
	 * tratar os demais pacotes
	 * 
	 * Este método cria um fluxo/mach entre origem e destino de acordo com as
	 * camadas especificasdas pelos atributos statcs
	 */
	protected void doL2ForwardFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx,
			boolean requestFlowRemovedNotifn) {
		OFPort srcPort = OFMessageUtils.getInPort(pi);
		DatapathId srcSw = sw.getId();
		IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
		IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
		log.info("doL2ForwardFlow - DatapathId [" + srcSw.toString() + "] src[" + srcDevice + "] dst[" + dstDevice
				+ "]");
		if (dstDevice == null) {
			log.debug("Destination device unknown. Flooding packet");
			doFlood(sw, pi, decision, cntx);
			return;
		}
		if (srcDevice == null) {
			log.error("No device entry found for source device. Is the device manager running? If so, report bug.");
			return;
		}

		if (FLOOD_ALL_ARP_PACKETS && IFloodlightProviderService.bcStore
				.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD).getEtherType() == EthType.ARP) {
			log.info("ARP flows disabled in Forwarding. Flooding ARP packet");
		}
		if (!topologyService.isEdge(srcSw, srcPort)) {
			doFlood(sw, pi, decision, cntx);
			/*
			 * FIXME -- não sei como o destino é conhecido se existe apenas a informação do
			 * SOURCE_DEVICE talvez deveria se verificar o dst não?
			 */
			log.info(
					"Packet destination is known, but packet was not received on an edge port (rx on {}/{}--{}:{}). Flooding packet",
					new Object[] { srcSw, srcPort, srcDevice, dstDevice });
			return;
		}

		SwitchPort dstAp = null;
		// Caso o host destino tenha multiplas saídas
		for (SwitchPort ap : dstDevice.getAttachmentPoints()) {
			// Apenas precisa do primeiro edge que encontrar para usar como AP destino
			if (topologyService.isEdge(ap.getNodeId(), ap.getPortId())) {
				dstAp = ap;
				break;
			}

		}
		/*
		 * Existe a possibilidade de que o host não esteja mais vinculado a topologia
		 * por meio de um AP, pois ficou um longo tempo sem utilização, neste caso é
		 * necessário fazer o flood para reencontrar através de arp request o AP deste
		 * host destino... Uma melhor forma de fazer é repasssar o packet-out para
		 * última porta que se tem informação para evitar pacotes broadcast
		 * desnecessários na rede.
		 * 
		 * Outra forma de fazer poupando o controlador de futuros packets in, é marcar o
		 * pacote com um cookie envia-los para todos edges, verificando as resposta
		 * específica deste cookie e descartando as resposta ARPs desnecessárias. Não é
		 * necessário fazer o ARP circular pela rede inteira, ele pode ser entrege
		 * diretamente aos links que dão a destinos/reply -- Redes de múltiplos caminhos
		 * geram exesso de repocessamento de packet-in por conter loops, neste caso é
		 * necessário manter um cache para não fazer o mesmo flood mais de uma vez
		 */
		if (dstAp == null) {
			log.debug("Could not locate edge attachment point for destination device {}. Flooding packet");
			doFlood(sw, pi, decision, cntx);
			return;
		}
		/* Validate that the source and destination are not on the same switch port */
		if (sw.getId().equals(dstAp.getNodeId()) && srcPort.equals(dstAp.getPortId())) {
			log.debug("Both source and destination are on the same switch/port {}/{}. Dropping packet", sw.toString(),
					srcPort);
			return;
		}
		U64 flowSetId = flowSetIdRegistry.generateFlowSetId();
		U64 cookie = makeForwardingCookie(decision, flowSetId);

		Path path = routingEngineService.getPath(srcSw, srcPort, dstAp.getNodeId(), dstAp.getPortId());
		Match m = createMatchFromPacket(sw, srcPort, pi, cntx);

		if (!path.getPath().isEmpty()) {
			log.info("Creating flow rules on the route, match rule: {}", m);
			// Insere o fluxo em todos os switch do Path Pertence a super classe
			pushRoute(path, m, pi, sw.getId(), cookie, cntx, requestFlowRemovedNotifn, OFFlowModCommand.ADD, false);
			/*
			 * Register this flowset with ingress and egress ports for link down flow
			 * removal. This is done after we push the path as it is blocking.
			 */
			for (NodePortTuple npt : path.getPath()) {
				flowSetIdRegistry.registerFlowSetId(npt, flowSetId);
			}
		}
		/*
		 * FIXME -- O fluxo foi criado, mas e o pacote? o que aconteceu? foi devolvido
		 * para seguir o fluxo ou apenas o usuário terá de fazer uma nova requisição
		 * para seguir o fluxo inserido... Porque o pacote não foi devolvido ao switch?
		 */

	}

	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, OFPacketIn pi, FloodlightContext cntx) {
		log.info("createMatchFromPacket");
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = null;

		if (pi.getVersion().compareTo(OFVersion.OF_11) > 0 && /* 1.0 and 1.1 do not have a match */
				pi.getMatch().get(MatchField.VLAN_VID) != null) {
			vlan = pi.getMatch().get(MatchField.VLAN_VID).getVlanVid(); /* VLAN may have been popped by switch */
		}
		if (vlan == null) {
			vlan = VlanVid.ofVlan(eth.getVlanID()); /* VLAN might still be in packet */
		}
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		Match.Builder mb = sw.getOFFactory().buildMatch();

		if (FLOWMOD_DEFAULT_MATCH_IN_PORT) {
			mb.setExact(MatchField.IN_PORT, inPort);
		}

		if (FLOWMOD_DEFAULT_MATCH_MAC) {
			if (FLOWMOD_DEFAULT_MATCH_MAC_SRC) {
				mb.setExact(MatchField.ETH_SRC, srcMac);
			}
			if (FLOWMOD_DEFAULT_MATCH_MAC_DST) {
				mb.setExact(MatchField.ETH_DST, dstMac);
			}
		}

		if (FLOWMOD_DEFAULT_MATCH_VLAN) {
			if (!vlan.equals(VlanVid.ZERO)) {
				mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
			}
		}
		if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
			IPv4 ip = (IPv4) eth.getPayload();
			IPv4Address srcIp = ip.getSourceAddress();
			IPv4Address dstIp = ip.getDestinationAddress();
			if (FLOWMOD_DEFAULT_MATCH_IP) {
				mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
				if (FLOWMOD_DEFAULT_MATCH_IP_SRC) {
					mb.setExact(MatchField.IPV4_SRC, srcIp);
				}
				if (FLOWMOD_DEFAULT_MATCH_IP_DST) {
					mb.setExact(MatchField.IPV4_DST, dstIp);
				}
				if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
					if (!FLOWMOD_DEFAULT_MATCH_IP) {
						mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
					}
				}
				if (ip.getProtocol().equals(IpProtocol.TCP)) {
					TCP tcp = (TCP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
						mb.setExact(MatchField.TCP_SRC, tcp.getSourcePort());
					}
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
						mb.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
					}
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_15) >= 0) {
						if (FLOWMOD_DEFAULT_MATCH_TCP_FLAG) {
							mb.setExact(MatchField.TCP_FLAGS, U16.of(tcp.getFlags()));
						}
					} else if (sw.getSwitchDescription().getHardwareDescription().toLowerCase().contains("open vswitch")
							&& (Integer
									.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase()
											.split("\\.")[0]) > 2
									|| (Integer
											.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase()
													.split("\\.")[0]) == 2
											&& Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription()
													.toLowerCase().split("\\.")[1]) >= 1))) {
						if (FLOWMOD_DEFAULT_MATCH_TCP_FLAG) {
							mb.setExact(MatchField.OVS_TCP_FLAGS, U16.of(tcp.getFlags()));
						}

					}

				} else if (ip.getProtocol().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
						mb.setExact(MatchField.UDP_SRC, udp.getSourcePort());
					}
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
						mb.setExact(MatchField.UDP_DST, udp.getDestinationPort());
					}
				}
			}
		} else if (eth.getEtherType() == EthType.ARP) { /* shallow check for equality is okay for EthType */
			mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
		} else if (eth.getEtherType() == EthType.IPv6) {
			IPv6 ip = (IPv6) eth.getPayload();
			IPv6Address srcIp = ip.getSourceAddress();
			IPv6Address dstIp = ip.getDestinationAddress();
			if (FLOWMOD_DEFAULT_MATCH_IP) {
				mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
				if (FLOWMOD_DEFAULT_MATCH_IP_SRC) {
					mb.setExact(MatchField.IPV6_SRC, srcIp);
				}
				if (FLOWMOD_DEFAULT_MATCH_IP_DST) {
					mb.setExact(MatchField.IPV6_DST, dstIp);
				}
			}
			if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
				if (!FLOWMOD_DEFAULT_MATCH_IP) {
					mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
				}
				if (ip.getNextHeader().equals(IpProtocol.TCP)) {// Não é em ip.getProtocol como no IPv4
					TCP tcp = (TCP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
						mb.setExact(MatchField.TCP_SRC, tcp.getSourcePort());
					}
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
						mb.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
					}
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_15) >= 0) {
						if (FLOWMOD_DEFAULT_MATCH_TCP_FLAG) {
							mb.setExact(MatchField.TCP_FLAGS, U16.of(tcp.getFlags()));
						}
					} else if (sw.getSwitchDescription().getHardwareDescription().toLowerCase().contains("open vswitch")
							&& (Integer
									.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase()
											.split("\\.")[0]) > 2
									|| (Integer
											.parseInt(sw.getSwitchDescription().getSoftwareDescription().toLowerCase()
													.split("\\.")[0]) == 2
											&& Integer.parseInt(sw.getSwitchDescription().getSoftwareDescription()
													.toLowerCase().split("\\.")[1]) >= 1))) {
						if (FLOWMOD_DEFAULT_MATCH_TCP_FLAG) {
							mb.setExact(MatchField.OVS_TCP_FLAGS, U16.of(tcp.getFlags()));
						}
					}
				} else if (ip.getNextHeader().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC) {
						mb.setExact(MatchField.UDP_SRC, udp.getSourcePort());
					}
					if (FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST) {
						mb.setExact(MatchField.UDP_DST, udp.getDestinationPort());
					}
				}
			}
		}

		return mb.build();
	}

	/*
	 * Cria um cookie para a decisão tomada para este flowId
	 */
	protected U64 makeForwardingCookie(IRoutingDecision decision, U64 flowSetId) {
		long user_fields = 0;
		U64 decision_cookie = (decision == null) ? null : decision.getDescriptor();
		if (decision_cookie != null) {
			user_fields |= AppCookie.extractUser(decision_cookie) & DECISION_MASK;
		}
		if (flowSetId != null) {
			user_fields |= AppCookie.extractUser(flowSetId) & FLOWSET_MASK;
		}
		// TODO: Mask in any other required fields here
		if (user_fields == 0) {
			return DEFAULT_FORWARDING_COOKIE;
		}

		return AppCookie.makeCookie(FORWARDING_APP_ID, user_fields);
	}

	/**
	 * Caso o destino não seja conhecido é realizado um flood para todas as portas
	 * exeto a porta de entrada
	 * 
	 * @param sw
	 * @param pi
	 * @param decision
	 * @param cntx
	 */
	protected void doFlood(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		log.info("doFlood ");
		OFPort inPort = OFMessageUtils.getInPort(pi);
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		List<OFAction> actions = new ArrayList<>();
		// Lista todas as portas do switch que se pode fazer broadcast
		Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId());
		if (broadcastPorts.isEmpty()) {
			log.debug("No broadcast ports found. Using FLOOD output action");
			broadcastPorts = Collections.singleton(OFPort.FLOOD);
		}
		for (OFPort p : broadcastPorts) {
			// Caso a porta seja a mesma de entrada não é necessário enviar
			if (p.equals(inPort))
				continue;
			// Adiciona uma ação para cada porta
			actions.add(sw.getOFFactory().actions().output(p, Integer.MAX_VALUE));
		}
		pob.setActions(actions);
		pob.setBufferId(OFBufferId.NO_BUFFER);
		OFMessageUtils.setInPort(pob, inPort);
		pob.setData(pi.getData());
		log.info("Writing flood PacketOut switch={} packet-in={} packet-out={}", new Object[] { sw, pi, pob.build() });

		// verifica no msgTypesToCache se esse tipo de mensagem pode ser enviada
		messageDamper.write(sw, pob.build());// Macete para enviar o packet-out

		return;
	}

	/**
	 * Aparentemente essa função entrega o pacote diretamente ao gateway/porta do
	 * destino utilizando o tueAp para se certificar que é um gateway válido para
	 * entregar o pacote ao destino
	 * 
	 * @param eth
	 * @param destDevice
	 * @return
	 */
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
	 * o gateway
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

		return l3manager.getAllVirtualGateways();

	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(String name) {

		return l3manager.getVirtualGateway(name);
	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(DatapathId dpid) {
		return l3manager.getVirtualGateway(dpid);
	}

	@Override
	public Optional<VirtualGatewayInstance> getGatewayInstance(NodePortTuple npt) {
		return l3manager.getVirtualGateway(npt);
	}

	@Override
	public Collection<VirtualGatewayInterface> getGatewayInterfaces(VirtualGatewayInstance gateway) {

		return l3manager.getGatewayInterfaces(gateway);
	}

	@Override
	public Optional<VirtualGatewayInterface> getGatewayInterface(String name, VirtualGatewayInstance gateway) {
		return l3manager.getGatewayInterface(name, gateway);
	}

	@Override
	public void addGatewayInstance(VirtualGatewayInstance gateway) {
		log.info("A new virtual gateway {} created", gateway.getName());
		l3manager.addVirtualGateway(gateway);

	}

	@Override
	public void addVirtualInterface(VirtualGatewayInstance gateway, VirtualGatewayInterface intf) {
		log.info("A new virtual interface {} created for gateway {}", intf.getInterfaceName(), gateway.getName());
		l3manager.addVirtualInterface(gateway, intf);

	}

	@Override
	public VirtualGatewayInstance updateVirtualGateway(String name, MacAddress newMac) {
		log.info("Virtual gateway {} updated", name);
		return l3manager.updateVirtualGateway(name, newMac);
	}

	@Override
	public void updateVirtualInterface(VirtualGatewayInstance gateway, VirtualGatewayInterface intf) {
		log.info("Virtual interface {} in gateway {} updated ", intf.getInterfaceName(), gateway.getName());
		l3manager.updateVirtualInterface(gateway, intf);

	}

	@Override
	public void deleteGatewayInstances() {
		log.info("All virtual gateways deleted");
		l3manager.removeAllVirtualGateways();

	}

	@Override
	public boolean deleteGatewayInstance(String name) {
		log.info("Virtual gateway {} deleted", name);
		return l3manager.removeVirtualGateway(name);
	}

	@Override
	public void removeAllVirtualInterfaces(VirtualGatewayInstance gateway) {
		log.info("All virtual interfaces removed from gateway {}", gateway.getName());
		l3manager.removeAllVirtualInterfaces(gateway);

	}

	@Override
	public boolean removeVirtualInterface(String interfaceName, VirtualGatewayInstance gateway) {
		log.info("Virtual gateway {} removed from gateway {}", interfaceName, gateway.getName());
		return l3manager.removeVirtualInterface(interfaceName, gateway);
	}

	@Override
	public void routingDecisionChanged(Iterable<Masked<U64>> changedDecisions) {
		deleteFlowsByDescriptor(changedDecisions);

	}

	/**
	 * Apaga todos os fluxos que dão match com a descrição de cookie/mask de todos
	 * os switches encontrados independente de versões do OpenFLow
	 * 
	 * @param descriptors
	 */
	protected void deleteFlowsByDescriptor(Iterable<Masked<U64>> descriptors) {
		Collection<Masked<U64>> masked_cookies = convertRoutingDecisionDescriptors(descriptors);
		if (masked_cookies != null && !masked_cookies.isEmpty()) {
			Map<OFVersion, List<OFMessage>> cache = Maps.newHashMap();
			for (DatapathId dpid : switchService.getAllSwitchDpids()) {
				IOFSwitch sw = switchService.getActiveSwitch(dpid);
				if (sw == null) {
					continue;
				}

				OFVersion ver = sw.getOFFactory().getVersion();
				if (cache.containsKey(ver)) {
					sw.write(cache.get(ver));
				} else {
					ImmutableList.Builder<OFMessage> msgsBuilder = ImmutableList.builder();
					for (Masked<U64> masked_cookie : masked_cookies) {
						if (ver.compareTo(OFVersion.OF_10) == 0) {
							msgsBuilder.add(
									sw.getOFFactory().buildFlowDelete().setCookie(masked_cookie.getValue()).build());
						} else {
							msgsBuilder.add(sw.getOFFactory().buildFlowDelete().setCookie(masked_cookie.getValue())
									.setCookieMask(masked_cookie.getMask()).build());
						}
					}

					List<OFMessage> msgs = msgsBuilder.build();
					sw.write(msgs);
					cache.put(ver, msgs);

				}
			}

		}
	}

	/**
	 * Converte o descriptor em uma Collection de cookies com máscaras
	 * 
	 * @param maskedDescriptors
	 * @return
	 */
	protected Collection<Masked<U64>> convertRoutingDecisionDescriptors(Iterable<Masked<U64>> maskedDescriptors) {
		if (maskedDescriptors == null) {
			return null;
		}
		ImmutableList.Builder<Masked<U64>> resultBuilder = ImmutableList.builder();
		for (Masked<U64> maskedDescriptor : maskedDescriptors) {
			long user_mask = AppCookie.extractUser(maskedDescriptor.getMask()) & DECISION_MASK;
			long user_value = AppCookie.extractUser(maskedDescriptor.getValue()) & user_mask;
			resultBuilder.add(Masked.of(AppCookie.makeCookie(FORWARDING_APP_ID, user_value),
					AppCookie.getAppFieldMask().or(U64.of(user_mask))));
		}
		return resultBuilder.build();
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		for (LDUpdate u : updateList) {
			if (u.getOperation() == UpdateOperation.LINK_REMOVED || u.getOperation() == UpdateOperation.PORT_DOWN
					|| u.getOperation() == UpdateOperation.TUNNEL_PORT_REMOVED) {

				Set<OFMessage> msgs = new HashSet<OFMessage>();
				if (u.getSrc() != null && u.getSrc().equals(DatapathId.NONE)) {
					IOFSwitch srcSw = switchService.getSwitch(u.getSrc());
					if (srcSw != null) {
						Set<U64> ids = flowSetIdRegistry.getFlowSetIds(new NodePortTuple(u.getSrc(), u.getSrcPort()));
						if (ids != null) {
							Iterator<U64> i = ids.iterator();
							while (i.hasNext()) {
								U64 id = i.next();
								U64 cookie = id.or(DEFAULT_FORWARDING_COOKIE);
								U64 cookieMask = U64.of(FLOWSET_MAX).or(AppCookie.getAppFieldMask());
								// Apaga dodos os fluxos que dão match com SRC PORT e saída para SRC PORT
								msgs = buildDeleteFlows(u.getSrcPort(), msgs, srcSw, cookie, cookieMask);
								messageDamper.write(srcSw, msgs);
								log.debug("src: Removing flows to/from DPID={}, port={}", u.getSrc(), u.getSrcPort());
								log.debug("src: Cookie/mask {}/{}", cookie, cookieMask);

								Set<NodePortTuple> npts = flowSetIdRegistry.getNodePortTuples(id);
								if (npts != null) {
									for (NodePortTuple npt : npts) {
										msgs.clear();
										IOFSwitch sw = switchService.getSwitch(npt.getNodeId());
										if (sw != null) {

											/* Delete flows matching on npt port and outputting to npt port */
											msgs = buildDeleteFlows(npt.getPortId(), msgs, sw, cookie, cookieMask);
											messageDamper.write(sw, msgs);
											log.debug("src: Removing same-cookie flows to/from DPID={}, port={}",
													npt.getNodeId(), npt.getPortId());
											log.debug("src: Cookie/mask {}/{}", cookie, cookieMask);
										}
									}
								}
								flowSetIdRegistry.removeExpiredFlowSetId(id,
										new NodePortTuple(u.getSrc(), u.getSrcPort()), i);
							}
						}
					}
					flowSetIdRegistry.removeNodePortTuple(new NodePortTuple(u.getSrc(), u.getSrcPort()));
				}

				/* must be a link, not just a port down, if we have a dst switch */
				if (u.getDst() != null && !u.getDst().equals(DatapathId.NONE)) {
					/* dst side of link */
					IOFSwitch dstSw = switchService.getSwitch(u.getDst());
					if (dstSw != null) {
						Set<U64> ids = flowSetIdRegistry.getFlowSetIds(new NodePortTuple(u.getDst(), u.getDstPort()));
						if (ids != null) {
							Iterator<U64> i = ids.iterator();
							while (i.hasNext()) {
								U64 id = i.next();
								U64 cookie = id.or(DEFAULT_FORWARDING_COOKIE);
								U64 cookieMask = U64.of(FLOWSET_MASK).or(AppCookie.getAppFieldMask());
								/* Delete flows matching on dst port and outputting to dst port */
								msgs = buildDeleteFlows(u.getDstPort(), msgs, dstSw, cookie, cookieMask);
								messageDamper.write(dstSw, msgs);
								log.debug("dst: Removing flows to/from DPID={}, port={}", u.getDst(), u.getDstPort());
								log.debug("dst: Cookie/mask {}/{}", cookie, cookieMask);

								/*
								 * Now, for each ID on this particular failed link, remove all other flows in
								 * the network using this ID.
								 */
								Set<NodePortTuple> npts = flowSetIdRegistry.getNodePortTuples(id);
								if (npts != null) {
									for (NodePortTuple npt : npts) {
										msgs.clear();
										IOFSwitch sw = switchService.getSwitch(npt.getNodeId());
										if (sw != null) {
											/* Delete flows matching on npt port and outputting on npt port */
											msgs = buildDeleteFlows(npt.getPortId(), msgs, sw, cookie, cookieMask);
											messageDamper.write(sw, msgs);
											log.debug("dst: Removing same-cookie flows to/from DPID={}, port={}",
													npt.getNodeId(), npt.getPortId());
											log.debug("dst: Cookie/mask {}/{}", cookie, cookieMask);
										}
									}
								}
								flowSetIdRegistry.removeExpiredFlowSetId(id,
										new NodePortTuple(u.getDst(), u.getDstPort()), i);
							}
						}
					}
					flowSetIdRegistry.removeNodePortTuple(new NodePortTuple(u.getDst(), u.getDstPort()));
				}

				/* must be a link, not just a port down, if we have a dst switch */
				if (u.getDst() != null && !u.getDst().equals(DatapathId.NONE)) {
					/* dst side of link */
					IOFSwitch dstSw = switchService.getSwitch(u.getDst());
					if (dstSw != null) {
						Set<U64> ids = flowSetIdRegistry.getFlowSetIds(new NodePortTuple(u.getDst(), u.getDstPort()));
						if (ids != null) {
							Iterator<U64> i = ids.iterator();
							while (i.hasNext()) {
								U64 id = i.next();
								U64 cookie = id.or(DEFAULT_FORWARDING_COOKIE);
								U64 cookieMask = U64.of(FLOWSET_MASK).or(AppCookie.getAppFieldMask());
								/* Delete flows matching on dst port and outputting to dst port */
								msgs = buildDeleteFlows(u.getDstPort(), msgs, dstSw, cookie, cookieMask);
								messageDamper.write(dstSw, msgs);
								log.debug("dst: Removing flows to/from DPID={}, port={}", u.getDst(), u.getDstPort());
								log.debug("dst: Cookie/mask {}/{}", cookie, cookieMask);

								/*
								 * Now, for each ID on this particular failed link, remove all other flows in
								 * the network using this ID.
								 */
								Set<NodePortTuple> npts = flowSetIdRegistry.getNodePortTuples(id);
								if (npts != null) {
									for (NodePortTuple npt : npts) {
										msgs.clear();
										IOFSwitch sw = switchService.getSwitch(npt.getNodeId());
										if (sw != null) {
											/* Delete flows matching on npt port and outputting on npt port */
											msgs = buildDeleteFlows(npt.getPortId(), msgs, sw, cookie, cookieMask);
											messageDamper.write(sw, msgs);
											log.debug("dst: Removing same-cookie flows to/from DPID={}, port={}",
													npt.getNodeId(), npt.getPortId());
											log.debug("dst: Cookie/mask {}/{}", cookie, cookieMask);
										}
									}
								}
								flowSetIdRegistry.removeExpiredFlowSetId(id,
										new NodePortTuple(u.getDst(), u.getDstPort()), i);
							}
						}
					}
					flowSetIdRegistry.removeNodePortTuple(new NodePortTuple(u.getDst(), u.getDstPort()));
				}

			}
		}

	}

	/**
	 * Função para criar lista de OFMessages que apagam fluxos de acordo com os
	 * parametros especificados
	 * 
	 * @param port
	 * @param msgs
	 * @param sw
	 * @param cookie
	 * @param cookieMask
	 * @return @Set<OFMessage>
	 */
	private Set<OFMessage> buildDeleteFlows(OFPort port, Set<OFMessage> msgs, IOFSwitch sw, U64 cookie,
			U64 cookieMask) {
		if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) == 0) {
			msgs.add(sw.getOFFactory().buildFlowDelete().setCookie(cookie)
					// cookie mask not supported in OpenFlow 1.0
					.setMatch(sw.getOFFactory().buildMatch().setExact(MatchField.IN_PORT, port).build()).build());

			msgs.add(sw.getOFFactory().buildFlowDelete().setCookie(cookie)
					// cookie mask not supported in OpenFlow 1.0
					.setOutPort(port).build());
		} else {
			msgs.add(sw.getOFFactory().buildFlowDelete().setCookie(cookie).setCookieMask(cookieMask)
					.setMatch(sw.getOFFactory().buildMatch().setExact(MatchField.IN_PORT, port).build()).build());

			msgs.add(sw.getOFFactory().buildFlowDelete().setCookie(cookie).setCookieMask(cookieMask).setOutPort(port)
					.build());
		}

		return msgs;

	}

	@Override
	public void switchAdded(DatapathId switchId) {

	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		l3manager.getAllVirtualGateways().stream().forEach(instance -> instance.removeSwitchFromInstance(switchId));
		log.info("Handle switchRemoved. Switch {} removed from virtual gateway instance", switchId.toString());
	}

	@Override
	public void switchActivated(DatapathId switchId) {
		IOFSwitch sw = switchService.getSwitch(switchId);
		if (sw == null) {
			log.warn(
					"Switch {} was activated but had no switch object in the switch service. Perhaps it quickly disconnected",
					switchId);
			return;
		}
		if (OFDPAUtils.isOFDPASwitch(sw)) {
			messageDamper.write(sw, sw.getOFFactory().buildFlowDelete().setTableId(TableId.ALL).build());
			messageDamper.write(sw,
					sw.getOFFactory().buildGroupDelete().setGroup(OFGroup.ANY).setGroupType(OFGroupType.ALL).build());
			messageDamper.write(sw, sw.getOFFactory().buildGroupDelete().setGroup(OFGroup.ANY)
					.setGroupType(OFGroupType.INDIRECT).build());
			messageDamper.write(sw, sw.getOFFactory().buildBarrierRequest().build());

			List<OFPortModeTuple> portModes = new ArrayList<>();
			for (OFPortDesc p : sw.getPorts()) {
				portModes.add(OFPortModeTuple.of(p.getPortNo(), OFPortMode.ACCESS));
			}
			log.warn("For OF-DPA switch {}, initializing VLAN {} on ports {}",
					new Object[] { switchId, VlanVid.ZERO, portModes });
			OFDPAUtils.addLearningSwitchPrereqs(sw, VlanVid.ZERO, portModes);
		}
	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
		/*
		 * Port down events handled via linkDiscoveryUpdate(), which passes thru all
		 * events
		 */
	}

	@Override
	public void switchChanged(DatapathId switchId) {

	}

	@Override
	public void switchDeactivated(DatapathId switchId) {

	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> s = new HashSet<Class<? extends IFloodlightService>>();
		s.add(IGatewayService.class);
		return s;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IGatewayService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IRoutingService.class);
		lista.add(IRestApiService.class);
		lista.add(IDeviceService.class);
		lista.add(IFloodlightProviderService.class);
		lista.add(ITopologyService.class);
		lista.add(IDebugCounterService.class);
		lista.add(ILinkDiscoveryService.class);

		return lista;
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

		// Get statcs de configurações
		Map<String, String> configParameters = context.getConfigParams(this);

		String tmp = configParameters.get("hard-timeout");
		if (tmp != null) {
			FLOWMOD_DEFAULT_HARD_TIMEOUT = ParseUtils.parseHexOrDecInt(tmp);
		} else {
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
			if (!tmp.contains("in-port") && !tmp.contains("vlan") && !tmp.contains("mac") && !tmp.contains("ip")
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
		log.info("Default flow matches set to: IN_PORT=" + FLOWMOD_DEFAULT_MATCH_IN_PORT + ", VLAN="
				+ FLOWMOD_DEFAULT_MATCH_VLAN + ", MAC=" + FLOWMOD_DEFAULT_MATCH_MAC + ", IP=" + FLOWMOD_DEFAULT_MATCH_IP
				+ ", FLAG=" + FLOWMOD_DEFAULT_MATCH_TCP_FLAG + ", TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT);

		tmp = configParameters.get("detailed-match");
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("src-mac") && !tmp.contains("dst-mac") && !tmp.contains("src-ip")
					&& !tmp.contains("dst-ip") && !tmp.contains("src-transport") && !tmp.contains("dst-transport")) {
				/*
				 * leave the default configuration -- both src and dst for layers defined above
				 */
			} else {
				FLOWMOD_DEFAULT_MATCH_MAC_SRC = tmp.contains("src-mac") ? true : false;
				FLOWMOD_DEFAULT_MATCH_MAC_DST = tmp.contains("dst-mac") ? true : false;
				FLOWMOD_DEFAULT_MATCH_IP_SRC = tmp.contains("src-ip") ? true : false;
				FLOWMOD_DEFAULT_MATCH_IP_DST = tmp.contains("dst-ip") ? true : false;
				FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC = tmp.contains("src-transport") ? true : false;
				FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST = tmp.contains("dst-transport") ? true : false;
			}
		}

		log.info("Default detailed flow matches set to: SRC_MAC=" + FLOWMOD_DEFAULT_MATCH_MAC_SRC + ", DST_MAC="
				+ FLOWMOD_DEFAULT_MATCH_MAC_DST + ", SRC_IP=" + FLOWMOD_DEFAULT_MATCH_IP_SRC + ", DST_IP="
				+ FLOWMOD_DEFAULT_MATCH_IP_DST + ", SRC_TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT_SRC + ", DST_TPPT="
				+ FLOWMOD_DEFAULT_MATCH_TRANSPORT_DST);

		tmp = configParameters.get("flood-arp");
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("yes") && !tmp.contains("yep") && !tmp.contains("true") && !tmp.contains("ja")
					&& !tmp.contains("stimmt")) {
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
		this.switchService.addOFSwitchListener(this);
		deviceManagerService.addListener(this.deviceListener);

		/* Register only if we want to remove stale flows */
		if (REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN) {
			linkService.addListener(this);
		}

	}

	/**
	 * Inner Class
	 * 
	 * @author devairdarolt
	 *
	 *         Classe para tratar mudanças no estado de @IPv4 em @IDevice
	 */
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

	/**
	 * Inner Class
	 * 
	 * @author devairdarolt
	 *
	 *         Classe para armazenar informações de fluxos e SwitchPort
	 */
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
