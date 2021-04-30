/**
 * @author devairdarolt
 * 
 * O obejtivo dessa classe é fazer um breve tutorial de implementação das interfaces IOFMessageListener e IFloodlightProviderService 
 * 
 */
package net.floodlightcontroller.deva;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.logging.LogFactory;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MACTracker implements IOFMessageListener, IFloodlightModule {

	// ANSI escape code para colorir o log
	public static final String ANSI_RESET = "\u001B[0m";
	public static final String ANSI_BLACK = "\u001B[30m";
	public static final String ANSI_RED = "\u001B[31m";
	public static final String ANSI_GREEN = "\u001B[32m";
	public static final String ANSI_YELLOW = "\u001B[33m";
	public static final String ANSI_BLUE = "\u001B[34m";
	public static final String ANSI_PURPLE = "\u001B[35m";
	public static final String ANSI_CYAN = "\u001B[36m";
	public static final String ANSI_WHITE = "\u001B[37m";

	protected IFloodlightProviderService floodlightProviderService;
	protected Set<String> listaMACs;
	protected static Logger logger;
	protected StringBuilder stringBuilder;

	/**
	 * Agora precisamos conectá-lo ao sistema de carregamento do módulo. Dizemos ao
	 * carregador de módulo que dependemos dele, modificando a função
	 * getModuleDependencies ().
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// IFloodlightService lista = {}
		Collection<Class<? extends IFloodlightService>> lista = new ArrayList<Class<? extends IFloodlightService>>();
		lista.add(IFloodlightProviderService.class);
		return lista;
		
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		//TODO Auto-generated method stub
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
		logger = LoggerFactory.getLogger(MACTracker.class);
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
		stringBuilder = new StringBuilder();
		stringBuilder.append(ANSI_YELLOW);
		stringBuilder.append("MACTracker adicionado a lista de listners para PACKET_IN\n");
		stringBuilder.append(ANSI_RESET);
		logger.info(stringBuilder.toString());
		// agora MACTracker será avisado toda vez que um PACKET_IN chegar ao controlador

	}

	/**
	 * Também precisamos inserir um ID para nosso listner. Isso é feito na chamada
	 * getName ().
	 */
	@Override
	public String getName() {
		return MACTracker.class.getSimpleName();
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
		// Ethernet = |Campos de match|
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		String srcMACHash = eth.getSourceMACAddress().toString();

		// Caso o MAC src desse pacote ainda não tenha sido visto antes então print no
		// log.
		if (!listaMACs.contains(srcMACHash)) {
			listaMACs.add(srcMACHash);
			stringBuilder = new StringBuilder();
			stringBuilder.append(ANSI_BLUE);
			stringBuilder.append("MAC: " + eth.getSourceMACAddress().toString() + " visto no Switch: "
					+ sw.getId().toString() + " Total:" + listaMACs.size() + "\n");
			stringBuilder.append(ANSI_RESET);
			logger.info(stringBuilder.toString());
		}

		return Command.CONTINUE; // Comando para que a menssagem constinue sendo processada por outros listners
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

}
