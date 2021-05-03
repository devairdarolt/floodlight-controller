/**
 * Tutoria para estudar o uso do OFFactory para diferentes versões do protocolo openflow
 */
package net.floodlightcontroller.tutorial.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.packet.*;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowModify;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupMod;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionPopVlan;

import org.projectfloodlight.openflow.protocol.action.OFActionSetField;

import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.Match.Builder;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxm;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmIpv4Dst;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.module.FloodlightModulePriority.Priority;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.UtilLog;

/**
 * @author devairdarolt
 *
 */
public class FactoryVersion implements IFloodlightModule, IOFMessageListener {

	protected IFloodlightProviderService floodlitghtProvideService;// provedor de serviços do floodlight
	protected Set<String> versions; // Conjunto de versões únicas encontradas nos switches
	protected static Logger logger;

	@Override
	public String getName() {

		return FactoryVersion.class.getSimpleName();
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

	/**
	 * Aqui verificamos todas os PACKET_IN que chegam ao controlador e armazenamos a
	 * versão do protocolo openflow que eles usam
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

		/** Recuperar um Factory a partir de um IOFSwitch */
		OFFactory swfactory = sw.getOFFactory();

		/** Recuperar um factory a partir da menssagem */
		OFVersion msVersion = msg.getVersion();
		OFFactory msfactory = OFFactories.getFactory(msVersion);

		versions.add("switchId:" + sw.getId() + ",openflowVersion:" + swfactory.getVersion().toString());

		switch (swfactory.getVersion()) {
		case OF_10:
			/**
			 * Caso o packet in seja gerado por um switch antigo que utiliza o openflow 1.0
			 * não podemos criar match com informações de buckets e groups ou multitable por
			 * exemplo
			 **/
			// TODO: Fazer tratamento para o openflow 1.0 aqui
			break;

		case OF_11:
			// TODO: Fazer tratamento para o openflow 1.1 aqui
			break;

		case OF_12:
			// TODO: Fazer tratamento para o openflow 1.2 aqui
			break;

		case OF_13:
			// TODO: Fazer tratamento para o openflow 1.3 aqui
			break;

		case OF_14:
			// TODO: Fazer tratamento para o openflow 1.4 aqui
			break;

		case OF_15:
			// TODO: Fazer tratamento para o openflow 1.5 aqui
			break;

		default:
			break;
		}

		/**
		 * MATCH Como o OFFactory retornado por OFFactories a partir de OFVersion então
		 * podemos fazer de forma genéria A criação de matches
		 */
		Ethernet l2 = floodlitghtProvideService.bcStore.get(cntx, floodlitghtProvideService.CONTEXT_PI_PAYLOAD);

		// Trata apenas pacotes IPV4 nesse exemplo
		if (!l2.getEtherType().equals(EthType.IPv4)) {
			return Command.CONTINUE;
		}
		Map<String, Object> camadas = new HashMap<String, Object>();
		camadas.put("L2", l2);
		if (l2.getEtherType().equals(EthType.IPv4)) {
			IPv4 l3 = (IPv4) l2.getPayload();
			camadas.put("L3", l3);
			if (l3.getProtocol().equals(IpProtocol.TCP)) {
				TCP l4 = (TCP) l3.getPayload();
				camadas.put("L4", l4);
			} else if (l3.getProtocol().equals(IpProtocol.UDP)) {
				UDP l4 = (UDP) l3.getPayload();
				camadas.put("L4", l4);
			}
		}

		// Apenas com as camadas recuperadas acima
		if (camadas != null) {

			Match.Builder matchBuilder = msfactory.buildMatch();
			// match.setExact(MatchField.IN_PORT, OFPort.of(1));
			if (camadas.containsKey("L2")) {
				matchBuilder.setExact(MatchField.ETH_SRC, ((Ethernet) camadas.get("L2")).getSourceMACAddress());
				matchBuilder.setExact(MatchField.ETH_DST, ((Ethernet) camadas.get("L2")).getDestinationMACAddress());
			}
			if (camadas.containsKey("L3")) {
				matchBuilder.setExact(MatchField.IPV4_SRC, ((IPv4) camadas.get("L3")).getSourceAddress());
				matchBuilder.setExact(MatchField.IPV4_DST, ((IPv4) camadas.get("L3")).getDestinationAddress());
			}
			if (camadas.containsKey("L4")) {
				IpProtocol teste = IpProtocol.TCP;
				if (camadas.get("L4") instanceof TCP) {
					matchBuilder.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
					matchBuilder.setExact(MatchField.TCP_SRC, ((TCP) camadas.get("L4")).getSourcePort());
					matchBuilder.setExact(MatchField.TCP_DST, ((TCP) camadas.get("L4")).getDestinationPort());
				} else if (camadas.get("L4") instanceof UDP) {
					matchBuilder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
					matchBuilder.setExact(MatchField.UDP_SRC, ((UDP) camadas.get("L4")).getSourcePort());
					matchBuilder.setExact(MatchField.UDP_DST, ((UDP) camadas.get("L4")).getDestinationPort());
				}
				/**
				 * Também é possivel utilizar camadas mais altas para formar um match, assim
				 * como L5, L6...
				 */

			}
			Match match = matchBuilder.build();// Aqui se encerra a criação das regras gerando o MATCH através do
												// builder específico desse switch.
			/**
			 * É possível fazer a verificação de prerequisito, para validar o match
			 * MatchField.ETH_DST.arePrerequisitesOK((Match)match) <==>
			 * match.contains(ETH_DST) O ideal seria colocar todos os MatchFild utilizados
			 * em uma lista, e percorrer essa lista verificando todos os requisitos Ex:
			 */

			logger.info(UtilLog.blue(MatchField.ETH_DST.arePrerequisitesOK(match) ? "Cumpriu os requisitos"
					: "nao cumprio os requisitos"));

			/**
			 * Verificar se o protocolo atual suporta o MatchField, assim como nos
			 * requisitos o ideal seria percorrer a lista verificando se o protocolo do
			 * switch suporta o MatchField ex:
			 */

			logger.info(UtilLog.blue("BSN_IP_FRAGMENTATION:"
					+ (matchBuilder.supports(MatchField.BSN_IP_FRAGMENTATION) ? "Suportado" : "Não suportado!")));

			/**
			 * ACTIONS Depois de criar o match é preciso definir quais ações esse match
			 * resultará sobre o pacote. Como as ações também variam de acordo com a versão
			 * do protocolo openflow o Factory do Loxygen ja esta preparado para lidar com
			 * isso.
			 */

			ArrayList<OFAction> actionList = new ArrayList<OFAction>(); // Lista de ações que sera contruida daqui para
																		// o match..
			OFActions actions = msfactory.actions(); // Actions específico para o protocolo desta mensagen

			/**
			 * A partir do openflow 1.2 temos ações que alteram os MatchField, porém essas
			 * ações apenas podem ser contruidas pelos factory de openflows apartir do 1.2
			 * Essa funcionalidade pode ser utilizada com o OFOxms (OpenFlow Extensible
			 * Match)
			 */

			// usar oxms para modificar L2 dest fild
			OFOxms oxms = msfactory.oxms();
			OFActionSetField action5 = actions.buildSetField()
					.setField(oxms.buildEthDst().setValue(MacAddress.of("ff:ff:ff:ff:ff:ff")).build()).build();
			actionList.add(action5);

			// Usar oxms para modificar L3 dest fild
			IPv4Address paramIPv4Address = IPv4Address.of("255.255.255.255");
			OFOxmIpv4Dst fieldIpv4 = oxms.buildIpv4Dst().setValue(paramIPv4Address).build();
			OFActionSetField action6 = actions.buildSetField().setField(fieldIpv4).build();
			actionList.add(action6);

			//
			OFActionPopVlan popVlan = actions.popVlan();
			actionList.add(popVlan);

			OFActionOutput output = actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.of(1)).build();
			actionList.add(output);

			/**
			 * INSTRUCTIONS A partir do openflow 1.3 foram instroduzido as instruções Todas
			 * as ações são aplicadas aos fluxos por instruções
			 * 
			 * OFInstructionApplyActions: Modifique imediatamente um pacote com base em uma
			 * lista fornecida de OFActions OFInstructionWriteActions: associe uma lista de
			 * ações a um pacote, mas espere para executá-las OFInstructionClearActions:
			 * Limpar uma lista de ações pendentes associada a um pacote
			 * OFInstructionGotoTable: Envie um pacote para uma determinada tabela de fluxo
			 * OFInstructionWriteMetadata: Salve alguns metadados com o pacote conforme ele
			 * avança no pipeline de processamento OFInstructionExperimenter: Permitir
			 * extensões para o conjunto OFInstruction OFInstructionMeter: Envie um pacote
			 * para um medidor
			 */

			ArrayList<OFInstruction> instructionList = new ArrayList<OFInstruction>();
			OFInstructions instructions = msfactory.instructions();

			// instructions aplica as ações contidas em actionList
			// Dessa forma as ações são aplicadas para execução imediata
			OFInstructionApplyActions applyActions = instructions.buildApplyActions().setActions(actionList).build();
			OFInstructionApplyActions applyActions2 = instructions.applyActions(actionList);

			if (applyActions.equals(applyActions2)) {
				logger.info(UtilLog.blue("As duas formas de criaram o objetos iguais"));
			} else {
				logger.info(UtilLog.blue("As duas formas de criaram o objetos diferentes"));
			}

			/**
			 * FLOW MODS Biblioteca de criação de fluxos
			 * 
			 * 1. OFFlowAdd 2. OFFlowModify 3. OFFlowModifyStrict 4. OFFlowDelete 5.
			 * OFFlowDeleteStrict
			 */

			// cada ítem acima pode ser criado pelos factories respectivos de cada versão
			OFFlowAdd flowAdd0 = msfactory.buildFlowAdd().setInstructions(instructionList).setMatch(match).build();

			// Cada ítem acima pode ser convertido em outro usando FlowModUtils
			OFFlowModify flowModify = FlowModUtils.toFlowModify(flowAdd0);

			// Outro exemplo de criação de fluxo específico para o switch
			OFFlowAdd flowAdd = swfactory.buildFlowAdd().setBufferId(OFBufferId.NO_BUFFER).setHardTimeout(3600)
					.setIdleTimeout(10).setPriority(32768).setMatch(match).setInstructions(instructionList)
					.setTableId(TableId.of(1)).build();

			// Todos os OFlowMods são também OFmessage e podem ser enviadas para um switch
			// Também é possivel criar um novo fluxo atráves do ja existentes
			// new
			// v
			OFFlowAdd flowAdd1 = flowAdd.createBuilder().setInstructions(instructionList).build();

			/**
			 * OFGroup
			 * 
			 * Grupos podem ser utilizados a partir de OF_13, aplicação de diferentes
			 * conjuntos de OFAction em um único pacote, balanceamento de carga e detecção e
			 * tratamento de falhas de link.
			 * 
			 * OFGroupType.ALL: Fornece a cada OFBucket uma cópia do pacote e aplica a lista
			 * de OFAction dentro de cada OFBucket à cópia do OFBucket do pacote.
			 * 
			 * OFGroupType.SELECT: Use uma abordagem determinada por switch (normalmente
			 * round-robin) para balancear a carga do pacote entre todos os OFBuckets. Pesos
			 * podem ser atribuídos para uma distribuição de pacotes round-robin ponderada.
			 * 
			 * OFGroupType.INDIRECT: Apenas um único OFBucket é permitido e todos os
			 * OFAction são aplicados. Isso permite um encaminhamento mais eficiente quando
			 * muitos fluxos contêm o mesmo conjunto de ações. Idêntico a TODOS com um único
			 * OFBucket.
			 * 
			 * OFGroupType.FF: Fast-Failover. Use um único OFBucket e mude automaticamente
			 * para o próximo OFBucket no OFGroup se um link específico ou um link no
			 * OFGroup especificado falhar para o OFBucket ativo.
			 * 
			 * OFGroupAdd OFGroupModify OFGroupDelete
			 */

			// Esses OFGroupMods são OFMessages que podem ser compostos e gravados em um
			// switch, assim como OFFlowMods
			if (!sw.getOFFactory().getVersion().equals(OFVersion.OF_15)) {
				ArrayList<OFGroupMod> groupModList = new ArrayList<OFGroupMod>();

				OFGroupAdd addGroup = swfactory.buildGroupAdd().setGroup(OFGroup.of(1)).setGroupType(OFGroupType.ALL)
						.build();// Cria o grupo 1
				groupModList.add(addGroup);

				if (sw.write(addGroup)) { // Insere apenas uma vez, caso ja exista retorna 0
					logger.info("Grupo inserido");
				} else {
					logger.info("Grupo NÃO inserido");
				}

				List<OFBucket> bucketList = new ArrayList<OFBucket>();
				OFBucket myBucket = sw.getOFFactory().buildBucket().setActions(actionList).setWatchGroup(OFGroup.ANY)
						.setWatchPort(OFPort.ANY).build();
				bucketList.add(myBucket);

				OFBucket myBucket2 = myBucket.createBuilder() /* Builder contains all fields as set in myBucket. */
						.setActions(actionList).build();
				bucketList.add(myBucket2);

				// Adicionando uma lista com 2 bucket em um grupo
				OFGroupAdd addGroup1 = sw.getOFFactory().buildGroupAdd().setGroupType(OFGroupType.ALL)
						.setGroup(OFGroup.of(50)).setBuckets(bucketList).build();

				/**
				 * Outra forma de processar um PACKE_IN pode ser feito atraves da OFMessage msg
				 * da seguinte maneira
				 * 
				 * Como o listner foi adicionado no metodo startup apenas para essas menssagens
				 * então podemos garantir que o metodo receiv apenas recebera mensagens de
				 * packet in
				 * 
				 */
				// Ethernet l2 = floodlitghtProvideService.bcStore.get(cntx,
				// floodlitghtProvideService.CONTEXT_PI_PAYLOAD);

				if (msg.getType().equals(OFType.PACKET_IN)) {
					// primeiro faz o cast
					OFPacketIn pktin = OFPacketIn.class.cast(msg);

					// Sempre será necessário fazer esse operador ternário pos a partir do openflow
					// 1.2 o valor da porta fica em OFMatchField
					OFPort myInPort = (pktin.getVersion().compareTo(OFVersion.OF_12) < 0) ? pktin.getInPort()
							: pktin.getMatch().get(MatchField.IN_PORT);

					// pktin.getMatch().get(MatchField.IPV4_DST); Usado apenas caso o packet in seja
					// gerado pelo match de IPv4

				}
			}
			/**
			 * PACKET_OUT
			 * 
			 */
			// Criando L2
			Ethernet eth = new Ethernet();
			eth.setSourceMACAddress(MacAddress.of(1));
			eth.setDestinationMACAddress(MacAddress.of(2));

			OFPacketIn pktin = OFPacketIn.class.cast(msg);

			/*
			 * . . . . . .
			 */
			// L3
			IPv4 ipv4 = new IPv4();
			ipv4.setSourceAddress(IPv4Address.of("10.0.0.1"));
			ipv4.setDestinationAddress(IPv4Address.of("10.0.0.2"));

			// set L2.payload = L3
			eth.setPayload(ipv4);
			eth.setEtherType(EthType.IPv4); // Obrigatório para não ocorrer pointer null

			byte prioritycode = eth.getPriorityCode();

			Priority[] values = Priority.NORMAL.values();

			// Set src MAC to virtual gateway MAC, set dst MAC to broadcast
			IPacket arpPacket = new Ethernet().setSourceMACAddress(MacAddress.of(1))
					.setDestinationMACAddress(MacAddress.of(2)).setEtherType(EthType.IPv4)
					.setVlanID(VlanVid.ZERO.getVlan()).setPriorityCode(eth.getPriorityCode()).setPayload(eth);

			byte[] data = arpPacket.serialize();
			// Especifica em qual porta o packet_out sera enviado

			OFActionOutput actionOutput = sw.getOFFactory().actions().buildOutput().setPort(OFPort.FLOOD).build();

			// Para carregar o payload ao pacote byte[] bytes = eth.serialize();
			OFPacketOut myPacketOut = sw.getOFFactory().buildPacketOut().setData(eth.serialize())
					.setActions(Collections.singletonList(OFAction.class.cast(actionOutput))).build();

			// outra forma de acessar o witch
			// floodlitghtProvideService.getc
			// IOFSwitch mySwitch =
			// switchService.getSwitch(DatapathId.of("00:00:00:00:00:00:00:01"));
			// Pode ocorrer timeout causando um Exeption
			if (sw.write(myPacketOut)) {
				logger.info("PACKET_OUT enviado!");
			}
			//

		}

		return Command.CONTINUE;
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

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlitghtProvideService = context.getServiceImpl(IFloodlightProviderService.class); // pegue do contexo a
																								// implementação atual
																								// dessa interface
		versions = new ConcurrentSkipListSet<String>(); // Inicializa o Set que trata região de disputa (read/write)
		logger = LoggerFactory.getLogger(this.getClass());

	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlitghtProvideService.addOFMessageListener(OFType.PACKET_IN, this);
		logger.info(UtilLog.blue("FactoryVersion adicionado em listners"));
	}

}
