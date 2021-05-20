/**
 * 
 * 
 * 
 */
package net.floodlightcontroller.tutorial.five;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;

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

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.IDeviceService.DeviceField;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;
//Outras dependencias
import net.floodlightcontroller.core.IFloodlightProviderService;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author devairdarolt
 *
 */
public class Forward3 implements IOFMessageListener, IFloodlightModule {

	// more flow-mod defaults
	public static final long COOKIE = 200;
	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 20; // in seconds
	protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	protected static short FLOWMOD_PRIORITY = 100;

	// dependencies
	protected IFloodlightProviderService serviceFloodlightProvider;
	protected IRoutingService serviceRoutingEngine;
	protected IOFSwitchService serviceSwitch;
	protected IDeviceService serviceDeviceManager;
	protected ITopologyService serviceTopology;
	protected ILinkDiscoveryService serviceLink;

	// Uteis
	protected int countARPflood;
	protected int countARPdrop;
	protected Map<IOFSwitch, Set<OFBufferId>> mapSwitchBufferId;
	protected Map<MacAddress, Map<IOFSwitch, OFPort>> gateWays;

	protected Set<String> listaMACs;
	protected static Logger logger;

	/**
	 * Agora precisamos conectá-lo ao sistema de carregamento do módulo. Dizemos ao
	 * carregador de módulo que dependemos dele, modificando a função
	 * getModuleDependencies ().
	 */
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
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {

		return null;
	}

	/**
	 * Inicializa as variáveis da nossa classe... Init é chamado no início do
	 * processo de inicialização do controlador. Ele é executado principalmente para
	 * carregar dependências e inicializar estruturas de dados.
	 */
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
		countARPflood = 0;
		countARPdrop = 0;
	}

	/**
	 * Tratamento da mensagem de entrada de PACKET_IN. Agora é hora de implementar o
	 * listner básico. Vamos registrar as mensagens PACKET_IN em nosso método de
	 * inicialização. Nesse método temos a garantia de que outros módulos dos quais
	 * dependemos já foram inicializados.
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		serviceFloodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
		serviceFloodlightProvider.addOFMessageListener(OFType.EXPERIMENTER, this);
		logger.info("{} adicionado aos listner", this.getClass().getSimpleName());
		// agora Forward1 será avisado toda vez que um PACKET_IN chegar ao controlador

	}

	/**
	 * Também precisamos inserir um ID para nosso listner. Isso é feito na chamada
	 * getName ().
	 */
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
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {

		return null;
	}

	/**
	 * Agora temos que definir o comportamento que queremos para as mensagens
	 * PACKET_IN. Observe que retornamos Command.CONTINUE para permitir que esta
	 * mensagem continue a ser tratada por outros manipuladores PACKET_IN também.
	 * 
	 * @param IOFSwitch sw - switch que gerou o packet in
	 * @param OFMessage msg
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

		switch (msg.getType()) {
		case PACKET_IN:
			OFPort inPort = OFMessageUtils.getInPort(OFPacketIn.class.cast(msg));
			logger.info("packet-in recebido de {} porta {}", sw, inPort);
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

	private void mapGateways(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		//
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		if (!gateWays.containsKey(srcMac)) {
			gateWays.put(srcMac, new ConcurrentHashMap<IOFSwitch, OFPort>());
			if (gateWays.get(srcMac).size() == 0) {
				OFPort port = OFMessageUtils.getInPort(pi);
				gateWays.get(srcMac).put(sw, port);

				logger.info("MAC{} visto primeiro em {}", srcMac, sw);
			}
		}

	}

	/**
	 * Esta função repassa todos os pacotes com multicast e bradcast diretamente
	 * para os gateways que possuem hosts ativos. Caso o controlador ainda não
	 * conheça o host então ele não envia O correto seria o pacote ser enviado em
	 * todas as portas dos switches para verificar se existe algum host nessa porta,
	 * porém ao fazer um flood em rede de muitos caminhos ocorre muita multiplicação
	 * de pacote
	 * 
	 * FIX 1 -- É possível resolver este problea da seguinte forma:
	 * 
	 * 1. gerando um cookie aleatório para marcar o pacote
	 * 
	 * 2. criando uma lista para cada switch que memoriza os cookies
	 * 
	 * 3. se o switch S ja processou esse cookie uma vez então descarta
	 * 
	 * 4. se ainda não processou então faz o flood
	 * 
	 * 
	 * @param sw
	 * @param packetIn
	 * @param cntx
	 * @return
	 */
	private boolean routerARP(IOFSwitch sw, OFPacketIn packetIn, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		boolean arp = false;
		IDevice srcDevice = serviceDeviceManager.fcStore.get(cntx, serviceDeviceManager.CONTEXT_SRC_DEVICE);
		if (dstMac.isMulticast() || dstMac.isBroadcast()) {
			Collection<? extends IDevice> devices = serviceDeviceManager.getAllDevices();
			for (IDevice device : devices) {
				if (!device.equals(srcDevice)) {
					SwitchPort[] attachPoints = device.getAttachmentPoints();
					if (attachPoints != null && attachPoints.length > 0) {
						DatapathId gateway = attachPoints[0].getNodeId();
						OFPort portId = attachPoints[0].getPortId();
						IOFSwitch swt = serviceSwitch.getSwitch(gateway);
						OFMessageUtils.writePacketOutForPacketIn(swt, packetIn, portId);
						logger.info("Pacote repassado para switch{} porta{}", swt, portId);
						logger.info("destino:{}", device.getMACAddress());
					}
				}
			}
		}

		
		return arp;
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

		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_SRC, srcMac).setExact(MatchField.ETH_DST, dstMac);
		Match match = mb.build();
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// GET ROUTE --
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		logger.info("Criando fluxo entre {} {} ", srcMac, dstMac);

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
		OFPort dstPort = srcAttachPoints[0].getPortId();

		Path shortestPath = serviceRoutingEngine.getPath(srcDataPath, dstDataPath);

		NodePortTuple dstNode = new NodePortTuple(dstDataPath, dstPort);
		logger.info("Path {}", shortestPath);
		Set<DatapathId> added = new HashSet<DatapathId>();
		List<NodePortTuple> nodePaths = shortestPath.getPath();
		
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MAKE FLOW -- para cada node de route
		////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		
		if(nodePaths.isEmpty()) {
			nodePaths.add(dstNode);
		}
		for (NodePortTuple node : nodePaths) {
			if (node.getNodeId().equals(dstNode.getNodeId())) {
				node = dstNode;
			}
			if (!added.contains(node.getNodeId())) {
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
				actions.add(sw.getOFFactory().actions().buildOutput().setPort(node.getPortId()).setMaxLen(0xffFFffFF)
						.build());

				// INSERT IN SWITCH OF PATH
				IOFSwitch swit = serviceSwitch.getSwitch(node.getNodeId());
				FlowModUtils.setActions(flowBuilder, actions, swit);
				swit.write(flowBuilder.build());
				logger.info("Flow ADD node{} port{}", swit, node.getNodeId());
			}
			
			OFMessageUtils.writePacketOutForPacketIn(srcSwitch, packetIn, nodePaths.get(0).getPortId());
			logger.info("Pacote repassado para switch{} porta{}", srcSwitch, nodePaths.get(0).getPortId());
		}
		

		return Command.CONTINUE;

	}

	/**
	 * Função para processar o packet-in
	 * 
	 * @param sw
	 * @param cast
	 * @param cntx
	 * @return
	 */
	private Command processPacketIn(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		OFPort inPort = OFMessageUtils.getInPort(pi);

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MATCH -- Utiliza apenas MAC_SRC e MAC_DST
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort).setExact(MatchField.ETH_SRC, srcMac).setExact(MatchField.ETH_DST,
				dstMac);
		Match match = mb.build();

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// MAKE ACTIONS TO REACTIVE PATH
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		OFPort outport = null;

		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(outport).setMaxLen(0xffFFffFF).build());

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// PACKET-OUT --- Cria um packet out para a porta
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		boolean packetOut = true; // Devolve o pacote ao switche com ações do que deve ser feito
		if (packetOut) {
			OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			pob.setActions(actions);

			// If the switch doens't support buffering set the buffer id to be none
			// otherwise it'll be the the buffer id of the PacketIn
			if (sw.getBuffers() == 0) {
				// We set the PI buffer id here so we don't have to check again below
				pi = pi.createBuilder().setBufferId(OFBufferId.NO_BUFFER).build();
				pob.setBufferId(OFBufferId.NO_BUFFER);
			} else {
				pob.setBufferId(pi.getBufferId());
			}
			// If the buffer id is none or the switch doesn's support buffering
			// we send the data with the packet out
			if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
				byte[] packetData = pi.getData();
				pob.setData(packetData);
			}

			OFMessageUtils.setInPort(pob, inPort);
			sw.write(pob.build());
		}
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// ADD-FLOW
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		boolean addFlow = true;// Adiciona fluxo na tabela de fluxo para tratar os demais pacotes
		if (addFlow) {
			OFFlowMod.Builder flowBuilder;
			flowBuilder = sw.getOFFactory().buildFlowAdd();
			flowBuilder.setMatch(match);
			flowBuilder.setCookie(U64.of(COOKIE));
			flowBuilder.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT);
			flowBuilder.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
			flowBuilder.setBufferId(OFBufferId.NO_BUFFER);
			flowBuilder.setPriority(FLOWMOD_PRIORITY);
			flowBuilder.setOutPort(outport);
			/*
			 * Set<OFFlowModFlags> flags = new HashSet<OFFlowModFlags>();
			 * flags.add(OFFlowModFlags.SEND_FLOW_REM);// Flag para marcar o fluxo par ser
			 * removido quando o idl-timeout // ocorrer flowBuilder.setFlags(flags);
			 */

			List<OFAction> al = new ArrayList<OFAction>();
			al.add(sw.getOFFactory().actions().buildOutput().setPort(outport).setMaxLen(0xffFFffFF).build());
			FlowModUtils.setActions(flowBuilder, actions, sw);
			sw.write(flowBuilder.build());
		}

		return Command.CONTINUE;
	}
}
