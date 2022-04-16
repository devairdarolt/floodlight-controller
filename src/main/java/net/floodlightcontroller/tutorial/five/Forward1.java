/**
 * @author devairdarolt
 * 
 * O obejtivo dessa classe é fazer um breve tutorial de forward 
 * na qual o controlador insere um flood de switch em switch
 * fazendo o pacote ir e voltar 
 * 
 */
package net.floodlightcontroller.tutorial.five;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.logging.LogFactory;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.python.bouncycastle.pqc.math.linearalgebra.RandUtils;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

//Outras dependencias
import net.floodlightcontroller.core.IFloodlightProviderService;
import java.util.ArrayList;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Forward1 implements IOFMessageListener, IFloodlightModule {

	// more flow-mod defaults
	public static final long COOKIE = 200;
	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	protected static short FLOWMOD_PRIORITY = 100;

	protected IFloodlightProviderService floodlightProviderService;
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
		return lista;

	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Inicializa as variáveis da nossa classe... Init é chamado no início do
	 * processo de inicialização do controlador. Ele é executado principalmente para
	 * carregar dependências e inicializar estruturas de dados.
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		listaMACs = new ConcurrentSkipListSet<String>();
		logger = LoggerFactory.getLogger(Forward1.class);
	}

	/**
	 * Tratamento da mensagem de entrada de PACKET_IN. Agora é hora de implementar o
	 * listner básico. Vamos registrar as mensagens PACKET_IN em nosso método de
	 * inicialização. Nesse método temos a garantia de que outros módulos dos quais
	 * dependemos já foram inicializados.
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.FLOW_REMOVED, this);
		floodlightProviderService.addOFMessageListener(OFType.EXPERIMENTER, this);
		logger.info("Forward1 adicionado aos listner");
		// agora Forward1 será avisado toda vez que um PACKET_IN chegar ao controlador

	}

	/**
	 * Também precisamos inserir um ID para nosso listner. Isso é feito na chamada
	 * getName ().
	 */
	@Override
	public String getName() {
		return Forward1.class.getSimpleName();
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
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
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
			logger.info("packet-in recebido de {}", sw);
			return processPacketIn(sw, OFPacketIn.class.cast(msg), cntx);
		case FLOW_REMOVED:
			logger.info("FLOW_REMOVED recebido de {}", sw);
			break;
		case ERROR:
			logger.info("ERRO recebido de switch {}: {}", sw, msg);
			break;		
		default:
			logger.info("Mensagem de inesperada de switch {}: {}", sw, msg);			
			break;
		}

		return Command.CONTINUE; // Comando para que a menssagem constinue sendo processada por outros listners

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
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort).setExact(MatchField.ETH_SRC, srcMac).setExact(MatchField.ETH_DST,
				dstMac);
		Match match = mb.build();

		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		// GET ROUTE
		//////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		// Como este caso possui um único switch, quando o pacote vem da porta 1, envia
		// para a porta 2 e vice-versa

		// OFMessageUtils.writePacketOutForPacketIn(sw, pi, OFPort.FLOOD);// Cria um
		// packet-out flood -- gera muitos packet-in

		OFPort outport = null;

		if (inPort.equals(OFPort.of(1))) {
			outport = OFPort.of(2);
		} else {
			outport = OFPort.of(1);
		}
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
			flowBuilder.setIdleTimeout(this.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
			flowBuilder.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
			flowBuilder.setBufferId(OFBufferId.NO_BUFFER);
			flowBuilder.setPriority(FLOWMOD_PRIORITY);
			flowBuilder.setOutPort(outport);
			Set<OFFlowModFlags> flags = new HashSet<OFFlowModFlags>();
			flags.add(OFFlowModFlags.SEND_FLOW_REM);// Flag para marcar o fluxo par ser removido quando o idl-timeout ocorrer
			flowBuilder.setFlags(flags);
			
			List<OFAction> al = new ArrayList<OFAction>();
			al.add(sw.getOFFactory().actions().buildOutput().setPort(outport).setMaxLen(0xffFFffFF).build());
			FlowModUtils.setActions(flowBuilder, actions, sw);
			sw.write(flowBuilder.build());
		}
		
		return Command.CONTINUE;
	}
}
