/**
 * Módulo simples de encaminhamento de fluxo
 */
package net.floodlightcontroller.tutorial.five;

import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.MacVlanPair;
import net.floodlightcontroller.debugcounter.IDebugCounter;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugcounter.IDebugCounterService.MetaData;
import net.floodlightcontroller.learningswitch.ILearningSwitchService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageUtils;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.projectfloodlight.openflow.util.LRULinkedHashMap;
import org.python.jline.internal.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author devairdarolt
 */
public class Forward2
		implements IFloodlightModule, ILearningSwitchService, IOFMessageListener, IControllerCompletionListener {

	// flow-mod - for use in the cookie
	public static final int LEARNING_SWITCH_APP_ID = 1;
	// LOOK! This should probably go in some class that encapsulates
	// the app cookie management
	public static final int APP_ID_BITS = 12;
	public static final int APP_ID_SHIFT = (64 - APP_ID_BITS);
	public static final long LEARNING_SWITCH_COOKIE = (long) (LEARNING_SWITCH_APP_ID
			& ((1 << APP_ID_BITS) - 1)) << APP_ID_SHIFT;

	// more flow-mod defaults
	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	protected static short FLOWMOD_PRIORITY = 100;

	// for managing our map sizes
	protected static final int MAX_MACS_PER_SWITCH = 1000;

	// normally, setup reverse flow as well. Disable only for using cbench for
	// comparison with NOX etc.
	protected static final boolean LEARNING_SWITCH_REVERSE_FLOW = true;

	// set this flag to true if you want to see the completion messages and
	// have the switch flushed
	protected final boolean flushAtCompletion = false;

	protected static Logger logger;
	protected Map<IOFSwitch, Map<MacVlanPair, OFPort>> macVlanToSwitchPortMap;// Tabela concorrente para armazenar //
																				// MAC/Porta

	protected IDebugCounterService debugCounterService;
	private IDebugCounter counterFlowMod;
	private IDebugCounter counterPacketOut;

	// Dependencies
	protected IFloodlightProviderService floodlightProviderService;

	@Override
	public String getName() {

		return Forward2.class.getSimpleName();
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		logger = LoggerFactory.getLogger(Forward2.class);
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		macVlanToSwitchPortMap = new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair, OFPort>>();// Essa tabela concorrente
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// add this class to listner messages event's
		floodlightProviderService.addCompletionListener(this);
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.FLOW_REMOVED, this);
		floodlightProviderService.addOFMessageListener(OFType.ERROR, this);

		// read our config options
		Map<String, String> configOptions = context.getConfigParams(this);
		try {
			String idleTimeout = configOptions.get("idletimeout");
			if (idleTimeout != null) {
				FLOWMOD_DEFAULT_IDLE_TIMEOUT = Short.parseShort(idleTimeout);
			}
		} catch (NumberFormatException e) {
			logger.warn("Error parsing flow idle timeout, " + "using default of {} seconds",
					FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		try {
			String hardTimeout = configOptions.get("hardtimeout");
			if (hardTimeout != null) {
				FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
			}
		} catch (NumberFormatException e) {
			logger.warn("Error parsing flow hard timeout, " + "using default of {} seconds",
					FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		try {
			String priority = configOptions.get("priority");
			if (priority != null) {
				FLOWMOD_PRIORITY = Short.parseShort(priority);
			}
		} catch (NumberFormatException e) {
			logger.warn("Error parsing flow priority, " + "using default of {}", FLOWMOD_PRIORITY);
		}

		debugCounterService.registerModule(this.getName());
		counterFlowMod = debugCounterService.registerCounter(this.getName(), "flow-mods-written",
				"Flow mods written to switches by LearningSwitch", MetaData.WARN);
		counterPacketOut = debugCounterService.registerCounter(this.getName(), "packet-outs-written",
				"Packet outs written to switches by LearningSwitch", MetaData.WARN);
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void onMessageConsumed(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		// TODO Auto-generated method stub

	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		logger.info(">>>> RECEIVE SOMTHING");

		switch (msg.getType()) {
		case PACKET_IN:
			logger.info("Process the packet-in message");
			return this.processPacketIn(sw, (OFPacketIn) msg, cntx);

		case FLOW_REMOVED:
			logger.info("Process the FLOW_REMOVED message");
			// process the flow-removed message
			break;
		case ERROR:
			logger.info("Process the ERRO{} from switch{}", msg, sw);
			// process the erro message
			break;

		default:
			break;
		}
		return Command.CONTINUE;
	}

	/**
	 * Função para processamento de um packet-in
	 * 
	 * @param sw   - Switch
	 * @param pi   - Packet-in
	 * @param cntx - Contexto
	 * @return - Command.CONTINUE
	 */
	private Command processPacketIn(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		OFPort portIn = OFMessageUtils.getInPort(pi);

		// Extrate de Header from packet doing a match
		Match match = createMatchFromPacket(sw, portIn, cntx);
		MacAddress srcMac = match.get(MatchField.ETH_SRC);
		MacAddress dstMac = match.get(MatchField.ETH_DST);
		VlanVid vlanVid = match.get(MatchField.VLAN_VID) == null ? VlanVid.ZERO
				: match.get(MatchField.VLAN_VID).getVlanVid();

		if (srcMac == null) {
			srcMac = MacAddress.NONE;
		}
		if (dstMac == null) {
			dstMac = MacAddress.NONE;
		}
		if (vlanVid == null) {
			vlanVid = VlanVid.ZERO;
		}

		// Not process this protocol v
		if ((dstMac.getLong() & 0xfffffffffff0L) == 0x0180c2000000L) {
			if (logger.isTraceEnabled()) {
				logger.info("ignoring packet addressed to 802.1D/Q reserved addr: switch {} vlan {} dest MAC {}",
						new Object[] { sw, vlanVid, dstMac.toString() });
			}
			return Command.STOP;
		}

		// If the source MAC is unicast, put on the learn th port
		if ((srcMac.getLong() & 0x010000000000L) == 0) {
			this.addToPortMap(sw, dstMac, vlanVid, portIn);
		}

		// now out-put flow-mod
		OFPort outPort = getFromPortMap(sw, dstMac, vlanVid);
		if (outPort == null) {
			// Caso o contolador ainda não tenha aprendido, faz um flood
			// Não se pode fazer o flood brodcast
			this.writePacketOutFromPacketIn(sw, pi, OFPort.FLOOD);
		} else if (outPort.equals(portIn)) {
			Log.trace("output-port igual input-port:switch{}, vlan{}, destMAC{}, porta{}",
					new Object[] { sw, vlanVid, dstMac, outPort });
		} else {
			// Adiciona a entrada de fluxo com match para srcMac, dstMac, vlan, inPort
			// Adiciona também entrada de fluxo com srcMac e dstMac invertidas, assim como
			// inPort e outPort
			this.pushPacket(sw, match, pi, outPort);
			this.writeFlowMod(sw, OFFlowModCommand.ADD, OFBufferId.NO_BUFFER, match, outPort);
		}

		return Command.CONTINUE;
	}

	private void writeFlowMod(IOFSwitch sw, OFFlowModCommand add, OFBufferId noBuffer, Match match, OFPort outPort) {
		// from openflow 1.0 spec - need to set these on a struct ofp_flow_mod:
		// struct ofp_flow_mod {
		// struct ofp_header header;
		// struct ofp_match match; /* Fields to match */
		// uint64_t cookie; /* Opaque controller-issued identifier. */
		//
		// /* Flow actions. */
		// uint16_t command; /* One of OFPFC_*. */
		// uint16_t idle_timeout; /* Idle time before discarding (seconds). */
		// uint16_t hard_timeout; /* Max time before discarding (seconds). */
		// uint16_t priority; /* Priority level of flow entry. */
		// uint32_t buffer_id; /* Buffered packet to apply to (or -1).
		// Not meaningful for OFPFC_DELETE*. */
		// uint16_t out_port; /* For OFPFC_DELETE* commands, require
		// matching entries to include this as an
		// output port. A value of OFPP_NONE
		// indicates no restriction. */
		// uint16_t flags; /* One of OFPFF_*. */
		// struct ofp_action_header actions[0]; /* The action length is inferred
		// from the length field in the
		// header. */
		// };
		
		

	}

	private void pushPacket(IOFSwitch sw, Match match, OFPacketIn pi, OFPort outPort) {
		if (pi == null) {
			return;
		}
		OFPort inPort = OFMessageUtils.getInPort(pi);

		// Aqui é assumido que o sw é o switch que gerou o packet-in,
		// então se a inPort == outPort o packet-out é ignorado
		if (inPort.equals(outPort)) {
			logger.trace("Packet-out para a mesma interface packet-in. Drop packet. srcSwitch{}, macth{}, packet-in{}",
					new Object[] { sw, match, pi });
			return;
		}

		// contruindo o packet-out
		OFPacketOut.Builder packetOutBuilder = sw.getOFFactory().buildPacketOut();
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setMaxLen(0xffFFffFF).build());
		packetOutBuilder.setActions(actions);

		// caso o switch não suporte buffering, set buffer id para none
		// caso superte então o buffer id é o mesmo do packet-in
		if (sw.getBuffers() == 0) {
			pi = pi.createBuilder().setBufferId(OFBufferId.NO_BUFFER).build();
			packetOutBuilder.setBufferId(OFBufferId.NO_BUFFER);
		} else {
			packetOutBuilder.setBufferId(pi.getBufferId());
		}
		OFMessageUtils.setInPort(packetOutBuilder, inPort);

		// se o buffer id é none, ou o switch não suporta buffer id
		// se envia os dados com um packet-out
		if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
			byte[] packetData = pi.getData();
			packetOutBuilder.setData(packetData);
		}
		counterPacketOut.increment();
		sw.write(packetOutBuilder.build());

	}

	private void writePacketOutFromPacketIn(IOFSwitch sw, OFPacketIn pi, OFPort outPort) {
		OFMessageUtils.writePacketOutForPacketIn(sw, pi, outPort);

	}

	/**
	 * Este método procura em um Map de MACs conhecidos pra qual porta deve enviar
	 * 
	 * @param sw
	 * @param dstMac
	 * @param vlanVid
	 * @return
	 */
	private OFPort getFromPortMap(IOFSwitch sw, MacAddress dstMac, VlanVid vlanVid) {

		if (vlanVid == null || VlanVid.FULL_MASK.equals(vlanVid)) {
			vlanVid = VlanVid.ZERO;
		}
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		if (swMap != null) {
			// return de port of dstMac
			return swMap.get(new MacVlanPair(dstMac, vlanVid));
		}
		return null;
	}

	/**
	 * Adiciona o mac em uma lista de learn
	 * 
	 * @param sw
	 * @param dstMac
	 * @param vlanVid
	 * 
	 */
	protected void addToPortMap(IOFSwitch sw, MacAddress dstMac, VlanVid vlanVid, OFPort port) {
		// swMap = {{mac,vlan},{porta}};
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		if (vlanVid == null || VlanVid.FULL_MASK.equals(vlanVid)) {
			vlanVid = VlanVid.ofVlan(0);
		}
		if (swMap == null) {
			swMap = Collections.synchronizedMap(new LRULinkedHashMap<MacVlanPair, OFPort>(MAX_MACS_PER_SWITCH));
			macVlanToSwitchPortMap.put(sw, swMap);
		}
		swMap.put(new MacVlanPair(dstMac, vlanVid), port);

	}

	/**
	 * Cria regras simples de match a partir do cabeçalho ethernetII
	 * 
	 * @param sw
	 * @param portIn
	 * @param cntx
	 * @return
	 */
	protected Match createMatchFromPacket(IOFSwitch sw, OFPort portIn, FloodlightContext cntx) {
		// Get the data from bcStre in context
		Ethernet ethernet = floodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(ethernet.getVlanID());
		MacAddress srcMac = ethernet.getSourceMACAddress();
		MacAddress dstMac = ethernet.getDestinationMACAddress();

		// The match will be have only this four match-field to forward to output
		Match.Builder mb = sw.getOFFactory().buildMatch();// Init process of create match-filds
		mb.setExact(MatchField.IN_PORT, portIn);
		mb.setExact(MatchField.ETH_SRC, srcMac);
		mb.setExact(MatchField.ETH_DST, dstMac);
		// case vlan exists them join vlan to a match-field
		if (vlan != null && !vlan.equals(VlanVid.ZERO)) {
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
		}

		return mb.build();
	}

	@Override
	public Map<IOFSwitch, Map<MacVlanPair, OFPort>> getTable() {
		// TODO Auto-generated method stub
		return null;
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
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		return lista;
	}

}
