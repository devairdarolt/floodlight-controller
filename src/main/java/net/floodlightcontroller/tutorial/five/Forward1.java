/**
 * @author devairdarolt
 * 
 * O obejtivo dessa classe é fazer um breve tutorial de forward com apenas 1 switch 
 * 
 */
package net.floodlightcontroller.tutorial.five;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.logging.LogFactory;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
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
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Forward1 implements IOFMessageListener, IFloodlightModule {

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

		Ethernet l2 = floodlightProviderService.bcStore.get(cntx, floodlightProviderService.CONTEXT_PI_PAYLOAD);

		// Retorna os dados separados por camadas... l2, l3, l4
		Map<String, Object> camadas = parseLayers(l2);

		OFFactory factory = sw.getOFFactory();

		Match match = makeMatch(factory, camadas);
		
		ArrayList<OFAction> actions = makeActions(factory,msg/* , Topologia */);// aqui também vai a topologia para determinar as melhores ações para o fluxo
		
		ArrayList<OFInstruction> instructions = makeInstructions(factory,actions);

		OFFlowAdd flowAdd0 = factory.buildFlowAdd().setBufferId(OFBufferId.NO_BUFFER).setHardTimeout(3600)
				.setIdleTimeout(10).setPriority(32768).setInstructions(instructions).setMatch(match).build();
		
		sw.write(flowAdd0);
		return Command.CONTINUE; // Comando para que a menssagem constinue sendo processada por outros listners
	}

	private ArrayList<OFInstruction> makeInstructions(OFFactory factory, ArrayList<OFAction> actions) {
		
		ArrayList<OFInstruction> instructionList = new ArrayList<OFInstruction>();
		OFInstructions instructions = factory.instructions();
		
		OFInstructionApplyActions applyActions = instructions.buildApplyActions().setActions(actions).build();
		instructionList.add(applyActions);
		return instructionList;
	}

	private ArrayList<OFAction> makeActions(OFFactory factory,OFMessage msg) {
		OFPacketIn pktin;
		OFPort myInPort=null;
		
		if (msg.getType().equals(OFType.PACKET_IN)) {
			// primeiro faz o cast
			pktin = OFPacketIn.class.cast(msg);

			// Sempre será necessário fazer esse operador ternário pos a partir do openflow
			// 1.2 o valor da porta fica em OFMatchField
			myInPort = (pktin.getVersion().compareTo(OFVersion.OF_12) < 0) ? pktin.getInPort()
					: pktin.getMatch().get(MatchField.IN_PORT);

			// pktin.getMatch().get(MatchField.IPV4_DST); Usado apenas caso o packet in seja
			// gerado pelo match de IPv4

		}
		
		ArrayList<OFAction> actionList = new ArrayList<OFAction>(); // Lista de ações que sera contruida daqui para		
		OFActions actions = factory.actions();
		
		
		OFActionOutput output = null;
		if(myInPort!=null && myInPort.equals(OFPort.of(1))) {
			output = actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.FLOOD).build(); 
		}else if (myInPort!=null && myInPort.equals(OFPort.of(2))) {
			output = actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.FLOOD).build();
		}		
		actionList.add(output);
		return actionList;
	}

	/**
	 * Cria um match para as camadas informadas
	 * 
	 * @param factory
	 * @param object
	 * @return match
	 */
	private Match makeMatch(OFFactory factory, Map<String, Object> camadas) {
		Match.Builder matchBuilder = factory.buildMatch();
		if (camadas.containsKey("l2")) {
			matchBuilder.setExact(MatchField.ETH_SRC, Ethernet.class.cast(camadas.get("l2")).getSourceMACAddress());
			matchBuilder.setExact(MatchField.ETH_DST,
					Ethernet.class.cast(camadas.get("l2")).getDestinationMACAddress());
			// TODO if brodcast >>> flood
			// TODO if multicast >>> doSomeThing
		}
		if (camadas.containsKey("l3")) {
			matchBuilder.setExact(MatchField.IPV4_SRC, IPv4.class.cast(camadas.get("l3")).getSourceAddress());
			matchBuilder.setExact(MatchField.IPV4_DST, IPv4.class.cast(camadas.get("l3")).getDestinationAddress());
			// TODO if broadcast >>> faça_algo
			// TODO if multicast >>> faça_algo
		}
		if (camadas.containsKey("l4")) {
			if (camadas.get("l4") instanceof TCP) {
				matchBuilder.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
				matchBuilder.setExact(MatchField.TCP_SRC, ((TCP) camadas.get("l4")).getSourcePort());
				matchBuilder.setExact(MatchField.TCP_DST, ((TCP) camadas.get("l4")).getDestinationPort());
			} else if (camadas.get("l4") instanceof UDP) {
				matchBuilder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
				matchBuilder.setExact(MatchField.UDP_SRC, ((UDP) camadas.get("l4")).getSourcePort());
				matchBuilder.setExact(MatchField.UDP_DST, ((UDP) camadas.get("l4")).getDestinationPort());
			}
		}
		Match match = matchBuilder.build();
		return match;
	}

	/**
	 * @param l2
	 * @param camadas
	 */
	private HashMap<String, Object> parseLayers(Ethernet l2) {
		HashMap<String, Object> camadas = new HashMap<String, Object>();
		camadas.put("l2", l2);
		if (l2.getEtherType().equals(EthType.IPv4)) {
			IPv4 l3 = (IPv4) l2.getPayload();
			camadas.put("l3", l3);
			if (l3.getProtocol().equals(IpProtocol.TCP)) {
				TCP l4 = (TCP) l3.getPayload();
				camadas.put("l4", l4);
			} else if (l3.getProtocol().equals(IpProtocol.UDP)) {
				UDP l4 = (UDP) l3.getPayload();
				camadas.put("l4", l4);
			}
		}
		return camadas;
	}

}
