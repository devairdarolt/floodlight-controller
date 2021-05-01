/**
 * Tutoria para estudar o uso do OFFactory para diferentes versões do protocolo openflow
 */
package net.floodlightcontroller.tutorial.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionPopVlan;
import org.projectfloodlight.openflow.protocol.action.OFActionSetDlDst;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActionSetNwDst;
import org.projectfloodlight.openflow.protocol.action.OFActionStripVlan;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.Match.Builder;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxm;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmIpv4Dst;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
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
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
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
		 * Como o OFFactory retornado por OFFactories a partir de OFVersion então
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
			 * Depois de criar o match é preciso definir quais ações esse match resultará
			 * sobre o pacote. Como as ações também variam de acordo com a versão do
			 * protocolo openflow o Factory do Loxygen ja esta preparado para lidar com
			 * isso.
			 */

			ArrayList<OFAction> actionList = new ArrayList<OFAction>(); // Lista de ações que sera contruida daqui para
																		// o match..
			OFActions actions = msfactory.actions(); // Actions específico para o protocolo desta mensagen

			// Usando builder actions
			/*
			 * OFActionSetDlDst action1 =
			 * actions.buildSetDlDst().setDlAddr(MacAddress.of("ff:ff:ff:ff:ff:ff")).build()
			 * ; actionList.add(action1);
			 */

			// usando diretamente o actions
			/*
			 * OFActionSetNwDst action2 =
			 * actions.setNwDst(IPv4Address.of("255.255.255.255")); actionList.add(action2);
			 */

			// Algumas ações como strip vlan não possuem build por não terem dados
			/*
			 * OFActionStripVlan action3 = actions.stripVlan(); actionList.add(action3);
			 */

			/*
			 * OFActionOutput action4 =
			 * actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.of(1)).build();
			 * actionList.add(action4);
			 */

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
			
			//TODO: Continue @ https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/1343547/How+to+use+OpenFlowJ-Loxigen

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
